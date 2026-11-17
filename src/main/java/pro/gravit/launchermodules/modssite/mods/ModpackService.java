package pro.gravit.launchermodules.modssite.mods;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pro.gravit.launcher.base.profiles.ClientProfile;
import pro.gravit.launchermodules.modssite.ModsSiteConfig;
import pro.gravit.launchermodules.modssite.Utils;
import pro.gravit.launchserver.LaunchServer;
import pro.gravit.launchserver.auth.profiles.LocalProfilesProvider;
import pro.gravit.launchserver.auth.profiles.ProfilesProvider;
import pro.gravit.utils.helper.IOHelper;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Модпаки = профили LaunchServer. Файлы модов кладутся в
 * updates/&lt;профиль&gt;/mods (настраивается), состояние — в
 * config/ModsSite/modpacks/&lt;профиль&gt;.json.
 */
public class ModpackService {
    private static final Logger logger = LogManager.getLogger(ModpackService.class);

    private final LaunchServer server;
    /** Конфиг не final: mods reload подменяет его живым значением. */
    private volatile ModsSiteConfig config;
    private final Path modpacksDir;
    private final ProfileOptionalSync optionalSync;
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public ModpackService(LaunchServer server, ModsSiteConfig config, Path dataDir) {
        this.server = server;
        this.config = config;
        this.modpacksDir = dataDir.resolve("modpacks");
        this.optionalSync = new ProfileOptionalSync(server, config);
        try {
            IOHelper.createParentDirs(modpacksDir.resolve("x"));
        } catch (IOException e) {
            logger.error("Cannot create modpacks dir", e);
        }
    }

    /** mods reload: подменить конфиг (поддиректории, лимиты и т.п.). */
    public void updateConfig(ModsSiteConfig newConfig) {
        if (newConfig == null) return;
        this.config = newConfig;
        this.optionalSync.updateConfig(newConfig);
    }

    // ======================= Профили =======================

    /** Все профили лаунчера. */
    public List<ClientProfile> profiles() {
        List<ClientProfile> result = new ArrayList<>();
        try {
            for (ProfilesProvider.UncompletedProfile p : server.config.profilesProvider.getProfiles(null)) {
                if (p instanceof ProfilesProvider.CompletedProfile completed) {
                    if (completed.getProfile() != null) {
                        result.add(completed.getProfile());
                    }
                }
            }
        } catch (Throwable e) {
            logger.error("Cannot list profiles", e);
        }
        result.sort(java.util.Comparator.comparing(ClientProfile::getSortIndex));
        return result;
    }

    public ClientProfile findProfile(String dir) {
        if (dir == null) return null;
        for (ClientProfile profile : profiles()) {
            if (dir.equals(profile.getDir())) return profile;
        }
        return null;
    }

    // ======================= Директории =======================

    /** Базовая папка updates (обычно "updates"). */
    public Path updatesBaseDir() {
        if (server.config.profilesProvider instanceof LocalProfilesProvider local) {
            Path path = Path.of(local.updatesDir);
            if (!path.isAbsolute()) {
                return server.dir.resolve(path);
            }
            return path;
        }
        return server.updatesDir;
    }

    /** Папка модов профиля: updates/&lt;профиль&gt;/mods. */
    public Path modsDir(ClientProfile profile, Modpack modpack) {
        return kindDir(profile, modpack, Kinds.MOD);
    }

    /** Папка контента типа (mod/resourcepack/shader): updates/&lt;профиль&gt;/&lt;поддиректория типа&gt;. */
    public Path kindDir(ClientProfile profile, Modpack modpack, String kind) {
        String subdir = Kinds.subdir(config, modpack, kind);
        Path base = updatesBaseDir().resolve(profile.getDir());
        return subdir.isEmpty() ? base : base.resolve(subdir);
    }

    public Path profileDir(ClientProfile profile) {
        return updatesBaseDir().resolve(profile.getDir());
    }

    // ======================= Загрузка/сохранение =======================

    public ReentrantLock lock(String profileDirName) {
        return locks.computeIfAbsent(profileDirName, k -> new ReentrantLock());
    }

    public Modpack load(ClientProfile profile) {
        Path file = modpackFile(profile.getDir());
        if (Files.exists(file)) {
            try {
                Modpack modpack = Utils.readJson(file, Modpack.class);
                if (modpack != null) {
                    if (modpack.mods == null) modpack.mods = new ArrayList<>();
                    // 1.1.0: включение/выключение модов убрано — «скачан,
                    // значит нужен»: всё в модпаке входит в состав. Поле enabled
                    // остаётся в JSON для совместимости и всегда истинно.
                    for (ModEntry entry : modpack.mods) entry.enabled = true;
                    return modpack;
                }
            } catch (Exception e) {
                logger.error("Cannot read modpack {}", file, e);
            }
        }
        return new Modpack();
    }

    public void save(ClientProfile profile, Modpack modpack) {
        try {
            Utils.writeJsonAtomic(modpackFile(profile.getDir()), modpack);
        } catch (IOException e) {
            logger.error("Cannot save modpack {}", profile.getDir(), e);
        }
    }

    private Path modpackFile(String profileDir) {
        return modpacksDir.resolve(safeFileName(profileDir) + ".json");
    }

    /** Защита от traversal в имени профиля. */
    public static String safeFileName(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (char c : name.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        if (sb.isEmpty()) sb.append("unnamed");
        return sb.toString();
    }

    // ======================= Версия игры и загрузчик =======================

    /** Версия игры с учётом переопределений. */
    public String gameVersion(ClientProfile profile, Modpack modpack) {
        if (modpack != null && modpack.gameVersion != null && !modpack.gameVersion.isBlank()) {
            return modpack.gameVersion;
        }
        String property = profile.getProperty("modsGameVersion");
        if (property != null && !property.isBlank()) return property;
        String version = profile.getMinecraftVersion();
        return version == null ? "" : version;
    }

    /** Загрузчик модов с учётом переопределений. */
    public String loader(ClientProfile profile, Modpack modpack) {
        if (modpack != null && modpack.loader != null && !modpack.loader.isBlank()) {
            return modpack.loader;
        }
        String property = profile.getProperty("modsLoader");
        if (property == null || property.isBlank()) {
            property = profile.getProperty("loader");
        }
        if (property != null && !property.isBlank()) return property.toLowerCase();
        String haystack = ((profile.getTitle() == null ? "" : profile.getTitle()) + " "
                + (profile.getDir() == null ? "" : profile.getDir())).toLowerCase();
        if (haystack.contains("fabric")) return "fabric";
        if (haystack.contains("neoforge") || haystack.contains("neo-forge")) return "neoforge";
        if (haystack.contains("forge")) return "forge";
        if (haystack.contains("quilt")) return "quilt";
        return "";
    }

    // ======================= Файловые операции =======================

    /** Удалить файл установленного мода (имя могло измениться после обновления!). */
    public void deleteInstalledFile(ClientProfile profile, Modpack modpack, ModEntry entry) throws IOException {
        Path dir = kindDir(profile, modpack, entry.kindOrMod());
        if (entry.installedFileName != null && !entry.installedFileName.isBlank()) {
            // Запомнить имя перед очисткой: по нему перепривязывается updateOptional
            entry.pushPreviousFileName(entry.installedFileName);
            Path file = dir.resolve(UpdateService.sanitizeFileName(entry.installedFileName));
            if (Files.exists(file)) {
                Files.deleteIfExists(file);
            }
        }
        entry.installedFileName = null;
        entry.installedVersionId = null;
        entry.installedVersionNumber = null;
        entry.installedSha1 = null;
        entry.installedModId = null;
        entry.installedModName = null;
        entry.installedAt = 0;
    }

    /** «Лишний» файл в папке контента: имя + тип (папка, в которой найден). */
    public static class OrphanFile {
        public String kind;
        public String name;

        public OrphanFile() {
        }

        public OrphanFile(String kind, String name) {
            this.kind = Kinds.normalize(kind);
            this.name = name;
        }

        /** Ключ сравнения (используется и в equals: экземпляры создаются и сканом, и из JSON). */
        public String key() {
            return kind + ":" + name;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof OrphanFile other)) return false;
            return key().equals(other.key());
        }

        @Override
        public int hashCode() {
            return key().hashCode();
        }
    }

    /**
     * Перечислить файлы в папках контента (mods/resourcepacks/shaderpacks),
     * не принадлежащие ни одной записи модпака СВОЕГО типа
     * И не упомянутые в updateOptional профиля (ручные настройки опциональных —
     * это осознанная работа администратора, а не мусор). Для прочих — «лишние»:
     * их можно импортировать в модпак (кнопка «Импорт») или удалить.
     * Файлы/папки из шаблонов игнорирования (настройки «Игнорируемые файлы»,
     * синтаксис .gitignore — например, .index от Sinytra Connector) не считаются
     * «лишними»: это служебный мусор других инструментов, его не трогаем.
     */
    public List<OrphanFile> findOrphans(ClientProfile profile, Modpack modpack) {
        List<OrphanFile> orphans = new ArrayList<>();
        Set<String> bound = optionalSync.allBoundFileNames(profile);
        for (String kind : Kinds.ALL) {
            Path dir = kindDir(profile, modpack, kind);
            if (!Files.isDirectory(dir)) continue;
            IgnoreMatcher ignore = IgnoreMatcher.forKind(config, kind);
            // имена, известные модпаку, — записи ЭТОГО типа (запись другого типа
            // владеет файлом в своей папке, а не в этой)
            Set<String> known = new HashSet<>();
            for (ModEntry entry : modpack.mods) {
                if (entry != null && entry.installedFileName != null
                        && kind.equals(entry.kindOrMod())) {
                    known.add(entry.installedFileName);
                }
            }
            known.addAll(bound);
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path p : stream) {
                    String name = p.getFileName().toString();
                    if (name.endsWith(".part") || name.endsWith(".tmp")) continue;
                    if (!known.contains(name)
                            && !ignore.matchesName(name, Files.isDirectory(p))) {
                        orphans.add(new OrphanFile(kind, name));
                    }
                }
            } catch (IOException e) {
                logger.error("Cannot list {} dir", kind, e);
            }
        }
        return orphans;
    }

    /**
     * Удалить «лишние» файлы. Если files != null — только указанные
     * (и только те, что действительно сейчас «лишние» — защита от гонок).
     */
    public int cleanOrphans(ClientProfile profile, Modpack modpack, List<OrphanFile> files) {
        int removed = 0;
        List<OrphanFile> targets = files == null || files.isEmpty()
                ? findOrphans(profile, modpack) : files;
        Set<String> valid = null;
        if (files != null && !files.isEmpty()) {
            valid = new HashSet<>();
            for (OrphanFile orphan : findOrphans(profile, modpack)) {
                valid.add(orphan.key());
            }
        }
        for (OrphanFile orphan : targets) {
            if (orphan == null || orphan.name == null) continue;
            if (valid != null && !valid.contains(orphan.key())) continue;
            try {
                Path dir = kindDir(profile, modpack, orphan.kind);
                if (Files.deleteIfExists(dir.resolve(UpdateService.sanitizeFileName(orphan.name)))) {
                    removed++;
                }
            } catch (IOException e) {
                logger.error("Cannot delete orphan {}", orphan.name, e);
            }
        }
        return removed;
    }

    // ======================= Подхват опциональных из профиля =======================

    /**
     * Подхватить в модпак файлы, которые привязаны в updateOptional профиля
     * (ручными записями админа или конвертером модпака), физически лежат
     * в папке контента (mods/resourcepacks/shaderpacks), но ещё НЕ имеют
     * записи в модпаке.
     *
     * <p>Зачем: {@link #findOrphans} исключает привязанные файлы из «лишних»
     * (чтобы их не удалили как мусор), поэтому кнопка «Импорт» их никогда
     * не предлагала — моды, заявленные в профиле опциональными, НАВСЕГДА
     * оставались невидимыми для сайта (типичный симптом: в модпаке 198
     * записей, а jar в папке — 217: ровно разница в опциональных файлах).
     * Теперь такие файлы при открытии вкладки «Моды» сами становятся
     * записями модпака — опциональными и включёнными (файл уже на сервере;
     * выключить сможет игрок или админ).
     *
     * <p>Ресурспаки и шейдеры подхватываются из своих папок БЕЗ привязки в
     * updateOptional: для них другого пути в модпак нет (папки ресурс-
     * паков/шейдеров в профиле вручную никто не настраивает), а «лишними»
     * они быть не должны — файл, положенный в папку контента, и есть
     * решение администратора включить его в состав. Записи создаются
     * ОБЯЗАТЕЛЬНЫМИ (в состав входят всегда, в updateOptional сразу НЕ
     * попадают): опциональность — осознанное решение админа в настройках
     * записи (галочка «Опциональный» → запись появится в updateOptional
     * при следующем согласовании). Моды по-прежнему подхватываются только
     * по привязке (консервативно: посторонние jar остаются «лишними»
     * до явного решения админа).
     *
     * <p>Записи создаются БЕЗ сетевых запросов (source=local, метаданные из
     * jar) — быстро даже для сотен файлов; при следующей проверке обновлений
     * мод сопоставится с Modrinth/CurseForge по хэшам/поиску и перепривяжется.
     * Профиль НЕ изменяется: если файл уже привязан ручной записью — она
     * остаётся как есть; согласование выполнит reconcile при следующей
     * установке/импорте/проверке.
     *
     * @return число добавленных записей
     */
    public int adoptUnimportedOptionals(ClientProfile profile, Modpack modpack) {
        int adopted = 0;
        Set<String> boundLower = new HashSet<>();
        for (String name : optionalSync.allBoundFileNames(profile)) {
            if (name != null && !name.isBlank()) {
                boundLower.add(name.trim().toLowerCase(java.util.Locale.ROOT));
            }
        }
        for (String kind : Kinds.ALL) {
            Path dir = kindDir(profile, modpack, kind);
            adopted += adoptUnimportedOptionals(dir, kind, modpack, boundLower,
                    config.mods.defaultChannel, IgnoreMatcher.forKind(config, kind));
        }
        return adopted;
    }

    /** Ядро подхвата (отделено от LaunchServer для тестируемости): смотрит
     *  только на папку контента одного типа, модпак и набор привязанных имён файлов.
     *  Моды — только по привязке (опциональными: они заявлены в updateOptional);
     *  ресурспаки/шейдеры — все неизвестные модпаку файлы папки (иначе они не
     *  попадают в модпак никогда: updateOptional для этих папок никто не
     *  настраивает), ОБЯЗАТЕЛЬНЫМИ — опциональность задаёт админ в настройках.
     *  Совместимость со старыми вызовами: без шаблонов игнорирования. */
    public static int adoptUnimportedOptionals(Path dir, String kind, Modpack modpack,
                                               Set<String> boundFileNames, String defaultChannel) {
        return adoptUnimportedOptionals(dir, kind, modpack, boundFileNames, defaultChannel, null);
    }

    /** Ядро подхвата с шаблонами игнорирования (.gitignore): игнорируемые файлы
     *  пропускаются — метаданные jar не читаются и записи не создаются. */
    public static int adoptUnimportedOptionals(Path dir, String kind, Modpack modpack,
                                               Set<String> boundFileNames, String defaultChannel,
                                               IgnoreMatcher ignore) {
        if (dir == null || modpack == null || !Files.isDirectory(dir)) return 0;
        boolean requireBound = Kinds.MOD.equals(Kinds.normalize(kind));
        Set<String> boundLower = new HashSet<>();
        for (String name : boundFileNames) {
            if (name != null && !name.isBlank()) {
                boundLower.add(name.trim().toLowerCase(java.util.Locale.ROOT));
            }
        }
        if (requireBound && boundLower.isEmpty()) return 0;
        // Имена, уже известные модпаку (текущие и прежние) — без учёта регистра
        Set<String> knownLower = new HashSet<>();
        for (ModEntry entry : modpack.mods) {
            if (entry == null) continue;
            if (entry.installedFileName != null && !entry.installedFileName.isBlank()) {
                knownLower.add(entry.installedFileName.trim().toLowerCase(java.util.Locale.ROOT));
            }
            if (entry.previousFileNames != null) {
                for (String previous : entry.previousFileNames) {
                    if (previous != null && !previous.isBlank()) {
                        knownLower.add(previous.trim().toLowerCase(java.util.Locale.ROOT));
                    }
                }
            }
        }
        List<String> adoptedNames = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                if (!Files.isRegularFile(p)) continue;
                if (name.endsWith(".part") || name.endsWith(".tmp")) continue;
                // Шаблоны игнорирования (.gitignore): служебные файлы других
                // инструментов (.index от Sinytra Connector, кэши и т.п.)
                // пропускаем — не тратим на них ни чтение, ни записи модпака
                if (ignore != null && ignore.matchesName(name, false)) continue;
                String lower = name.toLowerCase(java.util.Locale.ROOT);
                if (knownLower.contains(lower)) continue;
                if (requireBound && !boundLower.contains(lower)) continue;
                ModEntry entry = new ModEntry();
                entry.kind = kind;
                entry.installedFileName = name;
                entry.installedAt = System.currentTimeMillis();
                ModJarInfo.ModMeta meta = ModJarInfo.read(p);
                entry.installedModId = meta == null ? null : meta.id;
                entry.installedModName = meta == null ? null : meta.name;
                entry.source = "local";
                entry.projectId = null;
                String jarId = ModJarInfo.sanitizeId(meta == null ? null : meta.id);
                String stem = name;
                int dot = stem.lastIndexOf('.');
                if (dot > 0) stem = stem.substring(0, dot);
                entry.id = jarId != null ? jarId : ModJarInfo.sanitizeId(stem);
                if (entry.id == null || entry.id.isBlank()) entry.id = "local";
                if (modpack.find(entry.id) != null) {
                    String base = entry.id;
                    for (int i = 2; i < 100_000; i++) {
                        String candidate = base + "-" + i;
                        if (modpack.find(candidate) == null) {
                            entry.id = candidate;
                            break;
                        }
                    }
                }
                entry.name = meta != null && meta.name != null && !meta.name.isBlank()
                        ? meta.name : stem;
                entry.channel = defaultChannel;
                // Моды (привязанные в updateOptional) — опциональные и включённые:
                // входит в состав по умолчанию, выключить сможет игрок или админ.
                // Ресурспаки/шейдеры — ОБЯЗАТЕЛЬНЫЕ: файл в папке контента =
                // решение включить в состав; в updateOptional сразу не пишем —
                // сделать запись опциональной админ может в её настройках
                entry.optional = Kinds.MOD.equals(Kinds.normalize(kind));
                entry.enabled = true;
                modpack.mods.add(entry);
                adoptedNames.add(name);
            }
        } catch (IOException e) {
            logger.error("Cannot scan mods dir for unimported optionals", e);
        }
        if (!adoptedNames.isEmpty()) {
            logger.info("[ModsSite] Adopted {} file(s) missing from modpack (kind={}): {}",
                    adoptedNames.size(), Kinds.normalize(kind), adoptedNames);
        }
        return adoptedNames.size();
    }

    // ======================= Хэши =======================

    /**
     * Пересчитать хэши (HashedDir) профилей. Это то, что делает команда sync:
     * клиенты увидят изменения при следующем запуске.
     *
     * @param dirName имя директории профиля или null — все профили
     */
    public void rehash(String dirName) throws IOException {
        if (!(server.config.profilesProvider instanceof LocalProfilesProvider local)) {
            throw new IOException("Rehash supported only for local profiles provider");
        }
        if (dirName == null) {
            local.sync(null);
        } else {
            local.sync(List.of(dirName));
        }
    }

    public boolean rehashSupported() {
        return server.config.profilesProvider instanceof LocalProfilesProvider;
    }

    // ======================= Добавление/удаление модов =======================

    public static class AddResult {
        public ModEntry entry;
        public List<ModsApiProvider.ProjectInfo> missingDependencies = new ArrayList<>();
        public List<ModsApiProvider.ProjectInfo> missingConflicts = new ArrayList<>();
    }

    /**
     * Добавить мод в модпак. Подтягивает имя/иконку/описание и, если включён
     * autoResolveDeps, связи с уже существующими модами + подсказки недостающих.
     */
    public AddResult add(ClientProfile profile, Modpack modpack, ModsApiProvider provider,
                         String projectId, boolean optional, String channel, UpdateService updateService) throws Exception {
        return add(profile, modpack, provider, projectId, optional, channel, updateService, Kinds.MOD);
    }

    /** Добавление с указанием типа контента (mod/resourcepack/shader). */
    public AddResult add(ClientProfile profile, Modpack modpack, ModsApiProvider provider,
                         String projectId, boolean optional, String channel, UpdateService updateService,
                         String kind) throws Exception {
        if (modpack.mods.size() >= config.mods.maxModsPerProfile) {
            throw new IOException("Max mods per profile reached (" + config.mods.maxModsPerProfile + ")");
        }
        ModsApiProvider.ProjectInfo info = provider.project(projectId);
        String id = info.slug != null && !info.slug.isBlank() ? info.slug : (provider.id() + "-" + info.projectId);
        if (modpack.find(id) != null) {
            throw new IOException("Mod already in modpack: " + id);
        }
        ModEntry entry = new ModEntry();
        entry.id = id;
        entry.kind = Kinds.normalize(kind);
        entry.source = provider.id();
        entry.projectId = info.projectId != null ? info.projectId : projectId;
        entry.name = info.title != null ? info.title : id;
        entry.description = info.description;
        entry.iconUrl = info.iconUrl;
        entry.optional = optional;
        // скачан — значит нужен: выключения модов больше нет, состав = весь модпак
        entry.enabled = true;
        entry.channel = channel != null && !channel.isBlank() ? channel : config.mods.defaultChannel;
        modpack.mods.add(entry);

        AddResult result = new AddResult();
        result.entry = entry;
        if (config.mods.autoResolveDeps) {
            linkDependencies(profile, modpack, entry, provider, updateService, result);
        }
        save(profile, modpack);
        return result;
    }

    /**
     * Заполнить dependencies/conflicts для мода на основе данных API последней версии
     * (Modrinth dependency_type / CurseForge relationType). Возвращает подсказки
     * о проектах, которых ещё нет в модпаке.
     */
    public void linkDependencies(ClientProfile profile, Modpack modpack, ModEntry entry,
                                 ModsApiProvider provider, UpdateService updateService, AddResult out) throws Exception {
        String gameVersion = gameVersion(profile, modpack);
        // Фильтр загрузчика применим только к модам: у ресурспаков на Modrinth
        // загрузчик "minecraft", у шейдеров — iris/optifine; фильтр профиля
        // (fabric/forge/…) отсекал бы от них все версии
        String loader = Kinds.loaderApplicable(entry.kindOrMod()) ? loader(profile, modpack) : null;
        RemoteVersionInfo latest = updateService.pickVersion(provider, entry.projectId, gameVersion, loader, entry.channel);
        if (latest == null) return;
        List<String> unknownDeps = new ArrayList<>();
        List<String> unknownConflicts = new ArrayList<>();
        for (RemoteVersionInfo.RemoteDependency dep : latest.dependencies) {
            if (!"required".equals(dep.type) && !"incompatible".equals(dep.type)) continue;
            if (entry.source.equals(provider.id()) && !entry.source.equals(dep.source)) {
                // Мод с Modrinth может ссылаться на CF-проект и наоборот — пробуем оба источника
            }
            ModEntry existing = modpack.findByProject(dep.source, dep.projectId);
            if (existing == null) {
                existing = modpack.findByProject(otherSource(dep.source), dep.projectId);
            }
            if (existing != null && !existing.id.equals(entry.id)) {
                if ("required".equals(dep.type)) {
                    // Связь вычищена админом на сайте (могильник) — не возвращать
                    if (entry.hasLinkTombstone("dep", existing.id)) continue;
                    if (!entry.dependencies.contains(existing.id)) entry.dependencies.add(existing.id);
                } else {
                    // Конфликт может быть вычищен с любой из двух сторон
                    if (entry.hasLinkTombstone("conf", existing.id)
                            || existing.hasLinkTombstone("conf", entry.id)) continue;
                    if (!entry.conflicts.contains(existing.id)) entry.conflicts.add(existing.id);
                    if (!existing.conflicts.contains(entry.id)) existing.conflicts.add(entry.id);
                }
            } else {
                if ("required".equals(dep.type)) unknownDeps.add(dep.source + ":" + dep.projectId);
                else unknownConflicts.add(dep.source + ":" + dep.projectId);
            }
        }
        for (List<String> group : List.of(unknownDeps, unknownConflicts)) {
            if (group.isEmpty()) continue;
            boolean conflict = group == unknownConflicts;
            for (String key : group) {
                String[] parts = key.split(":", 2);
                ModsApiProvider p = updateService.provider(parts[0]);
                if (p == null || !p.available()) continue;
                try {
                    ModsApiProvider.ProjectInfo info = p.project(parts[1]);
                    if (conflict) out.missingConflicts.add(info);
                    else out.missingDependencies.add(info);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static String otherSource(String source) {
        return "modrinth".equals(source) ? "curseforge" : "modrinth";
    }

    /**
     * Применить правку зависимостей/конфликтов одного мода из настроек на сайте.
     * <p>
     * Правила:
     * <ul>
     *   <li>цели ссылок — ТОЛЬКО опциональные моды этого модпака: обязательные
     *       всегда включены, ссылки на них не имеют смысла и игнорируются
     *       (ссылка на сам мод тоже);</li>
     *   <li>конфликты симметричны: снятая у одного мода галочка убирает конфликт
     *       у ОБОИХ;</li>
     *   <li>вычищенные связи получают могильники {@link ModEntry#linkTombstones} —
     *       автоподтяжка из API источников ({@link #linkDependencies}) больше
     *       НЕ возвращает их; заново поставленная связь могильник снимает.</li>
     * </ul>
     * Списки заменяются целиком: галочки в настройках — полный снимок состояния.
     */
    public static void applyRelationEdit(Modpack modpack, ModEntry entry,
                                         List<String> newDependencies, List<String> newConflicts) {
        List<String> deps = filterRelationTargets(modpack, entry,
                newDependencies != null ? newDependencies : entry.dependencies);
        List<String> conflicts = filterRelationTargets(modpack, entry,
                newConflicts != null ? newConflicts : entry.conflicts);
        // Могильники: удалённые админом связи больше не подтягиваются автоматически
        for (String old : entry.dependencies) {
            if (!deps.contains(old)) entry.addLinkTombstone("dep", old);
        }
        for (String id : deps) {
            entry.removeLinkTombstone("dep", id);
        }
        for (String old : entry.conflicts) {
            if (!conflicts.contains(old)) {
                entry.addLinkTombstone("conf", old);
                ModEntry other = modpack.find(old);
                if (other != null) other.addLinkTombstone("conf", entry.id);
            }
        }
        for (String id : conflicts) {
            entry.removeLinkTombstone("conf", id);
            ModEntry other = modpack.find(id);
            if (other != null) other.removeLinkTombstone("conf", entry.id);
        }
        // Симметрия конфликтов: у второго мода та же связь ставится/убирается
        for (String id : conflicts) {
            ModEntry other = modpack.find(id);
            if (other != null && !other.conflicts.contains(entry.id)) {
                other.conflicts.add(entry.id);
            }
        }
        for (ModEntry other : modpack.mods) {
            if (other == entry) continue;
            if (other.conflicts.contains(entry.id) && !conflicts.contains(other.id)) {
                other.conflicts.remove(entry.id);
            }
        }
        entry.dependencies = deps;
        entry.conflicts = conflicts;
    }

    /**
     * Смена группы опциональных модов администратором (настройки мода на сайте).
     * Кроме самой записи {@link ModEntry#optionalGroup} ведёт могильники групп
     * {@link ModEntry#groupTombstones}: мод, удалённый из группы «X» («— Без
     * группы —» или перевод в другую), НЕ возвращается в неё автоподхватом из
     * профиля — файл мода остаётся в групповой записи updateOptional до
     * согласования, и без могильника adoptProfileOptionals тут же «усыновлял»
     * бы мод обратно. Заново выбранная группа могильник снимает.
     * Заодно сбрасывает зафиксированное имя записи, если оно указывало на
     * групповую запись прежней группы (при выходе: имя записи = имя группы).
     */
    public static void applyGroupChange(ModEntry entry, String newGroup) {
        String old = entry.optionalGroup;
        String normalized = newGroup == null || newGroup.isBlank() ? null : newGroup;
        if (normalized != null && normalized.length() > 100) {
            normalized = normalized.substring(0, 100);
        }
        if (!java.util.Objects.equals(old, normalized)) {
            if (old != null && !old.isBlank()) {
                entry.addGroupTombstone(old);
            }
            if (normalized != null) {
                entry.removeGroupTombstone(normalized);
            }
            entry.optionalGroup = normalized;
            // profileOptionalName при выходе указывает на запись группы — сбросить,
            // чтобы upsert не «перепривязывал» мод в групповую запись по этому имени
            if (normalized == null && entry.profileOptionalName != null
                    && entry.profileOptionalName.equals(old)) {
                entry.profileOptionalName = null;
            }
        } else if (normalized != null) {
            // та же группа — могильник всё равно снят (админ подтвердил выбор)
            entry.removeGroupTombstone(normalized);
        }
    }

    /** Отфильтровать цели ссылок: только опциональные моды модпака, не сам мод,
     *  без повторов. Ссылки на обязательные моды игнорируются (обязательные
     *  всегда включены — зависимости/конфликты с ними не работают). */
    private static List<String> filterRelationTargets(Modpack modpack, ModEntry entry, List<String> ids) {
        List<String> result = new ArrayList<>();
        if (ids == null) return result;
        for (String id : ids) {
            if (id == null || id.isBlank()) continue;
            String trimmed = id.trim();
            if (trimmed.equals(entry.id)) continue;
            ModEntry target = modpack.find(trimmed);
            if (target == null || !target.optional) {
                logger.info("[ModsSite] Relation target '{}' skipped for '{}': not an optional mod of this modpack",
                        trimmed, entry.id);
                continue;
            }
            if (!result.contains(trimmed)) result.add(trimmed);
        }
        return result;
    }

    /** Удалить моды: удалить их файлы и записи. */
    public List<String> remove(ClientProfile profile, Modpack modpack, List<String> ids) throws IOException {
        List<String> removed = new ArrayList<>();
        for (String id : ids) {
            ModEntry entry = modpack.find(id);
            if (entry == null) continue;
            deleteInstalledFile(profile, modpack, entry);
            // Убрать и запись из updateOptional профиля, если была
            optionalSync.removeEntry(profile, modpack, entry);
            modpack.mods.remove(entry);
            // Почистить ссылки у остальных
            for (ModEntry other : modpack.mods) {
                other.dependencies.removeIf(id::equals);
                other.conflicts.removeIf(id::equals);
            }
            removed.add(id);
        }
        save(profile, modpack);
        return removed;
    }

    /** Синхронизатор updateOptional профиля (опциональные моды для игроков). */
    public ProfileOptionalSync optionalSync() {
        return optionalSync;
    }

    /** Состав модпака — все его моды: включение/выключение убрано
     *  («скачан — значит нужен»), поэтому состав = весь модпак целиком. */
    public Set<String> enabledIds(Modpack modpack) {
        Set<String> ids = new TreeSet<>();
        for (ModEntry entry : modpack.mods) {
            ids.add(entry.id);
        }
        return ids;
    }
}
