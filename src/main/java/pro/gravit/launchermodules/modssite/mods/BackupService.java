package pro.gravit.launchermodules.modssite.mods;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pro.gravit.launcher.base.profiles.ClientProfile;
import pro.gravit.launchermodules.modssite.ModsSiteConfig;
import pro.gravit.launchermodules.modssite.Utils;
import pro.gravit.launchermodules.modssite.security.AuditLogger;
import pro.gravit.utils.helper.IOHelper;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Система бекапов профилей (вкладка «Бекапы»). Бекап — НАСТОЯЩИЙ .mrpack.
 *
 * <p>Формат полностью совместим со спецификацией Modrinth Modpack Format:
 * <pre>
 *   modrinth.index.json   — манифест: formatVersion=1, game="minecraft",
 *                           versionId, name, summary, files[], dependencies.
 *                           Каждый files[]-элемент — контент с привязкой
 *                           к Modrinth/CurseForge: путь, sha1+sha512 (обязательны
 *                           по спецификации), env (client/server), downloads[],
 *                           fileSize. Сам файл мода в архив НЕ кладётся.
 *   overrides/&lt;путь&gt;    — весь остальной контент выбранных папок
 *                           (локальные моды, конфиги, прочие файлы).
 *   modssite.index.json   — сайдкар модуля (сторонние mrpack-клиенты его
 *                           игнорируют): id/имя/дата, профиль, список папок
 *                           бекапа и снапшот состояния модпака.
 * </pre>
 *
 * <p>Создание не ходит в сеть: URL Modrinth строится по детерминированному
 * формату CDN ({@code cdn.modrinth.com/data/<проект>/versions/<версия>/<файл>}),
 * CurseForge — по публичному редиректу
 * ({@code curseforge.com/api/v1/mods/<id>/files/<fileId>/download}).
 *
 * <p>Восстановление: валидация формата и принадлежности бекапа профилю,
 * проверка безопасности всех путей (никаких ../ и абсолютных путей — защита
 * от zip-slip), прямой хост скачивания — только из allowlist; затем:
 * удаление файлов выбранных папок, которых нет в бекапе (честный снапшот),
 * распаковка overrides/, скачивание files[] с обязательной сверкой
 * sha1/sha512 (с fallback на повторный запрос версии у источника по
 * versionId), возврат состояния модпака, согласование updateOptional
 * и пересчёт хэшей профиля.
 *
 * <p>Хранение: config/ModsSite/backups/&lt;профиль&gt;/&lt;uuid&gt;.mrpack +
 * сторонний &lt;uuid&gt;.json (имя/дата/заморозка — чтобы переименование и
 * заморозка не перепаковывали архив). Замороженный бекап нельзя удалить
 * (ни кнопкой, ни авто-очисткой по лимиту).
 *
 * <p>ЧУЖИЕ mrpack: в папку бекапов профиля можно положить ЛЮБОЙ валидный
 * .mrpack (в том числе скачанный с Modrinth пакет ДРУГОЙ сборки) — при
 * просмотре вкладки он «усыновляется» (мета строится из modrinth.index.json)
 * и восстанавливается как обычный бекап: overrides/ и client-overrides/
 * распаковываются, files[] скачиваются с проверкой sha1/sha512, папки
 * покрытия (верхние сегменты путей манифеста) очищаются от лишнего —
 * сборка становится равной чужому пакету. Состояние модпака собирается
 * из манифеста (привязки по URL cdn.modrinth.com / curseforge.com;
 * остальное — локальные файлы, которые дальше можно принять через
 * «импорт осиротевших»).
 */
public class BackupService {
    private static final Logger logger = LogManager.getLogger(BackupService.class);

    /** Версия нашего сайдкара modssite.index.json. */
    public static final int SIDECAR_VERSION = 1;
    /** Версия формата modrinth.index.json (спецификация mrpack). */
    public static final int MRPACK_FORMAT_VERSION = 1;
    public static final String INDEX_ENTRY = "modrinth.index.json";
    public static final String SIDECAR_ENTRY = "modssite.index.json";
    public static final String OVERRIDES_PREFIX = "overrides/";
    public static final String CLIENT_OVERRIDES_PREFIX = "client-overrides/";
    private static final DateTimeFormatter DEFAULT_NAME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter VERSION_ID =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /**
     * Разрешённые хосты прямого скачивания files[] при восстановлении.
     * Файлы бекапа лежат в админской зоне, но манифест — обычный JSON:
     * подменённый бекап не должен превращать модуль в SSRF-прокси.
     * Список покрывает и чужие mrpack (Modrinth CDN, зеркала CurseForge,
     * GitHub — так пакуют пакеты вне Modrinth). Прочие источники
     * недоступны напрямую — только fallback по versionId через API
     * провайдера (Modrinth/CurseForge).
     */
    private static final Set<String> ALLOWED_DOWNLOAD_HOSTS = Set.of(
            "cdn.modrinth.com",
            "mediafilez.forgecdn.net",
            "edge.forgecdn.net",
            "www.curseforge.com",
            "curseforge.com",
            "github.com",
            "raw.githubusercontent.com");

    /** Конфиг не final: mods reload подменяет его живым значением. */
    private volatile ModsSiteConfig config;
    private final ModpackService modpacks;
    private final UpdateService updates;
    private final AuditLogger audit;
    /** Корень хранилища бекапов (config/ModsSite/backups). */
    private final Path backupsDir;
    /** Версия модуля (для манифестов бекапов). */
    private final String moduleVersion;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "ModsSite-Backup");
        thread.setDaemon(true);
        return thread;
    });

    /** Активная задача профиля (create/restore). */
    public static class Job {
        public final String type;
        public final long startedAt = System.currentTimeMillis();
        public volatile String message = "";

        Job(String type) {
            this.type = type;
        }
    }

    /** Итог последней завершённой задачи (для UI после исчезновения job). */
    public static class JobResult {
        public final String type;
        public final boolean ok;
        public final String message;
        public final long finishedAt = System.currentTimeMillis();

        JobResult(String type, boolean ok, String message) {
            this.type = type;
            this.ok = ok;
            this.message = message;
        }
    }

    private final ConcurrentHashMap<String, Job> activeJobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, JobResult> lastResults = new ConcurrentHashMap<>();

    /** Мета бекапа (стороны, &lt;id&gt;.json рядом с архивом). */
    public static class BackupMeta {
        public String id;
        public String name;
        public long createdAt;
        public String profile;
        public String moduleVersion;
        public boolean frozen;
        public int remoteCount;
        public int fileCount;
        /** true — чужой mrpack (без modssite.index.json): состояние
         *  модпака при восстановлении собирается из манифеста. */
        public boolean foreign;
    }

    /**
     * modrinth.index.json — манифест по спецификации .mrpack.
     * Имена полей — camelCase, как в формате.
     */
    public static class MrpackIndex {
        public int formatVersion = MRPACK_FORMAT_VERSION;
        public String game = "minecraft";
        public String versionId;
        public String name;
        public String summary;
        public List<MrpackFile> files = new ArrayList<>();
        public Map<String, String> dependencies = new LinkedHashMap<>();
    }

    /** Элемент files[] манифеста mrpack. */
    public static class MrpackFile {
        /** Относительный путь (разделитель "/"). */
        public String path;
        /** Обязательные по спецификации хэши: sha1 и sha512. */
        public Map<String, String> hashes = new LinkedHashMap<>();
        /** Среда: client/server → required | optional | unsupported. */
        public Map<String, String> env = new LinkedHashMap<>();
        /** Прямые ссылки скачивания (мы пишем одну). */
        public List<String> downloads = new ArrayList<>();
        public long fileSize;
    }

    /** modssite.index.json — сайдкар модуля внутри архива. */
    public static class BackupSidecar {
        public int formatVersion = SIDECAR_VERSION;
        public String id;
        public String name;
        public String moduleVersion;
        public long createdAt;
        public String profileDir;
        public String profileTitle;
        /** Папки (относительно папки профиля), попавшие в бекап. */
        public List<String> folders = new ArrayList<>();
        /** Снапшот состояния модпака (config/ModsSite/modpacks/&lt;профиль&gt;.json). */
        public Modpack modpack;
    }

    /** Локальный файл, уходящий в overrides/ (внутренняя запись сборки). */
    private static class OverrideFile {
        final String path;
        final String zipPath;
        final long size;

        OverrideFile(String path, String zipPath, long size) {
            this.path = path;
            this.zipPath = zipPath;
            this.size = size;
        }
    }

    public BackupService(ModsSiteConfig config, ModpackService modpacks, UpdateService updates,
                         AuditLogger audit, Path backupsDir, String moduleVersion) {
        this.config = config;
        this.modpacks = modpacks;
        this.updates = updates;
        this.audit = audit;
        this.backupsDir = backupsDir;
        this.moduleVersion = moduleVersion;
    }

    /** mods reload: подменить конфиг. */
    public void updateConfig(ModsSiteConfig newConfig) {
        if (newConfig != null) this.config = newConfig;
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    // ======================= Папки профиля =======================

    /** Нормализовать имя папки бекапа: относительный путь без ".." и мусора;
     *  null — папка невалидна. */
    public static String normalizeFolder(String folder) {
        if (folder == null) return null;
        String s = folder.trim().replace('\\', '/');
        while (s.startsWith("/")) s = s.substring(1);
        while (s.endsWith("/") && !s.isEmpty()) s = s.substring(0, s.length() - 1);
        if (s.isEmpty() || s.equals(".") || s.length() > 100) return null;
        if (s.contains("..") || s.contains("\u0000") || s.contains("//")) return null;
        // Буквы/цифры (включая национальные)/точки/дефисы/подчёркивания/слэши —
        // как в путях обновлений; .. и абсолютные пути отсечены выше
        if (!s.matches("[\\p{L}\\p{N}_.\\- /]+")) return null;
        return s;
    }

    /** Папки бекапа профиля: явный список из модпака или три стандартные
     *  папки контента (с учётом переопределений). */
    public List<String> resolveFolders(ClientProfile profile, Modpack modpack) {
        List<String> result = new ArrayList<>();
        if (modpack != null && modpack.backupFolders != null) {
            for (String folder : modpack.backupFolders) {
                String normalized = normalizeFolder(folder);
                if (normalized != null && !result.contains(normalized)) {
                    result.add(normalized);
                }
            }
        }
        if (result.isEmpty()) {
            for (String kind : Kinds.ALL) {
                String subdir = Kinds.subdir(config, modpack, kind);
                if (!subdir.isEmpty() && !result.contains(subdir)) {
                    result.add(subdir);
                }
            }
        }
        return result;
    }

    /** Сохранить список папок бекапа в модпак профиля. */
    public void saveFolders(ClientProfile profile, List<String> folders) throws IOException {
        var lock = modpacks.lock(profile.getDir());
        lock.lock();
        try {
            Modpack modpack = modpacks.load(profile);
            modpack.backupFolders = new ArrayList<>(folders);
            modpacks.save(profile, modpack);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Папки сборки профиля для чекбоксов во вкладке «Бекапы»: реально
     * существующие верхнеуровневые папки updates/&lt;профиль&gt;/ + дефолтные
     * папки контента (могут ещё не существовать) + сохранённый выбор.
     *
     * @return отсортированный список {name, exists}
     */
    public List<Map<String, Object>> profileFolders(ClientProfile profile, Modpack modpack) {
        Set<String> names = new TreeSet<>();
        Path dir = modpacks.profileDir(profile);
        if (Files.isDirectory(dir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path entry : stream) {
                    if (!Files.isDirectory(entry)) continue;
                    // Симлинки не бекапятся — не предлагаем их для выбора
                    if (Files.isSymbolicLink(entry)) continue;
                    String name = entry.getFileName().toString();
                    if (normalizeFolder(name) != null) names.add(name);
                }
            } catch (IOException e) {
                logger.warn("Cannot scan profile folders of {}", profile.getDir(), e);
            }
        }
        // Дефолтные папки контента показываем всегда — их можно выбрать заранее
        for (String kind : Kinds.ALL) {
            String subdir = Kinds.subdir(config, modpack, kind);
            if (!subdir.isEmpty() && subdir.indexOf('/') < 0 && normalizeFolder(subdir) != null) {
                names.add(subdir);
            }
        }
        // Сохранённый выбор, даже если папки на диске уже нет
        for (String folder : resolveFolders(profile, modpack)) {
            if (folder.indexOf('/') < 0 || Files.isDirectory(dir.resolve(folder))) {
                names.add(folder);
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (String name : names) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("exists", Files.isDirectory(dir.resolve(name)));
            result.add(item);
        }
        return result;
    }

    // ======================= Хранилище =======================

    private Path profileBackupsDir(String profileDir) {
        return backupsDir.resolve(ModpackService.safeFileName(profileDir));
    }

    private Path archivePath(String profileDir, String id) {
        return profileBackupsDir(profileDir).resolve(id + ".mrpack");
    }

    private Path metaPath(String profileDir, String id) {
        return profileBackupsDir(profileDir).resolve(id + ".json");
    }

    /** Все мета бекапов профиля (свежие сверху). Перед чтением — усыновление
     *  архивов без меты (потерянная мета или чужой .mrpack). */
    public List<BackupMeta> list(String profileDir) throws IOException {
        List<BackupMeta> result = new ArrayList<>();
        Path dir = profileBackupsDir(profileDir);
        if (!Files.isDirectory(dir)) return result;
        adoptOrphans(profileDir);
        try (var stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path metaFile : stream) {
                try {
                    BackupMeta meta = Utils.readJson(metaFile, BackupMeta.class);
                    if (meta == null || meta.id == null) continue;
                    Path archive = archivePath(profileDir, meta.id);
                    if (!Files.isRegularFile(archive)) {
                        logger.warn("[ModsSite] Backup {} exists without archive — skipped", meta.id);
                        continue;
                    }
                    result.add(meta);
                } catch (Exception e) {
                    logger.warn("Cannot read backup meta {}", metaFile, e);
                }
            }
        }
        result.sort(Comparator.comparingLong((BackupMeta m) -> m.createdAt).reversed());
        return result;
    }

    /** Суммарный размер всех бекапов профиля (фактические размеры архивов). */
    public long totalSize(String profileDir) throws IOException {
        long total = 0;
        for (BackupMeta meta : list(profileDir)) {
            try {
                total += Files.size(archivePath(profileDir, meta.id));
            } catch (IOException ignored) {
            }
        }
        return total;
    }

    /** Мета одного бекапа (null — не найден). */
    public BackupMeta meta(String profileDir, String id) throws IOException {
        for (BackupMeta meta : list(profileDir)) {
            if (meta.id.equals(id)) return meta;
        }
        return null;
    }

    /** Путь к архиву для скачивания. */
    public Path zipOf(String profileDir, String id) throws IOException {
        BackupMeta meta = meta(profileDir, id);
        if (meta == null) return null;
        Path archive = archivePath(profileDir, id);
        return Files.isRegularFile(archive) ? archive : null;
    }

    /** Размер архива бекапа (фактический) или -1, если архива нет. */
    public long size(String profileDir, String id) {
        try {
            Path archive = archivePath(profileDir, id);
            return Files.isRegularFile(archive) ? Files.size(archive) : -1;
        } catch (IOException e) {
            return -1;
        }
    }

    // ======================= Операции =======================

    public static class BusyException extends Exception {
        BusyException(String message) {
            super(message);
        }
    }

    /** Задача уже выполняется для профиля? */
    public Job job(String profileDir) {
        return activeJobs.get(profileDir);
    }

    /** Итог последней завершённой задачи (для тоста в UI). */
    public JobResult lastResult(String profileDir) {
        return lastResults.get(profileDir);
    }

    private Job tryStart(String profileDir, String type) throws BusyException {
        Job created = new Job(type);
        if (activeJobs.putIfAbsent(profileDir, created) != null) {
            throw new BusyException("Backup job already running for profile " + profileDir);
        }
        return created;
    }

    private void finish(String profileDir, String type, boolean ok, String message) {
        activeJobs.remove(profileDir);
        lastResults.put(profileDir, new JobResult(type, ok, message));
    }

    /** Запустить создание бекапа (асинхронно). */
    public void startCreate(ClientProfile profile, String name, String ip, String login)
            throws BusyException {
        if (!config.backup.enabled) throw new BusyException("Backups disabled in config");
        String key = profile.getDir();
        // фоновая задача обновления держит лок профиля до самого конца —
        // бекап просто ждал бы её молча; откажем сразу с понятной причиной
        if (updates.activeUpdateJob(key) != null) {
            throw new BusyException("Update job is running for profile " + key + " — try again later");
        }
        Job job = tryStart(key, "create");
        executor.submit(() -> {
            boolean ok = false;
            String message = "";
            try {
                BackupMeta meta = create0(profile, name, job);
                message = "ok: " + meta.remoteCount + " remote / " + meta.fileCount + " files";
                ok = true;
                audit.log("backup_create", ip, login, "ok",
                        Map.of("profile", key, "id", meta.id, "name", meta.name));
            } catch (Throwable t) {
                message = String.valueOf(t.getMessage());
                logger.error("Backup create failed for {}", key, t);
                audit.log("backup_create", ip, login, "error: " + message, Map.of("profile", key));
            } finally {
                finish(key, "create", ok, message);
            }
        });
    }

    /** Запустить восстановление (асинхронно). */
    public void startRestore(ClientProfile profile, String id, String ip, String login)
            throws BusyException, IOException {
        if (!config.backup.enabled) throw new BusyException("Backups disabled in config");
        String key = profile.getDir();
        if (meta(key, id) == null) {
            throw new IOException("Backup not found: " + id);
        }
        // Чужой .mrpack мог появиться между list() и restore() —
        // архив обязан существовать в момент старта задачи
        if (!Files.isRegularFile(archivePath(key, id))) {
            throw new IOException("Backup not found: " + id);
        }
        // восстановление переписывает файлы сборки — не пересекаемся с
        // фоновой задачей обновления, которая делает то же самое
        if (updates.activeUpdateJob(key) != null) {
            throw new BusyException("Update job is running for profile " + key + " — try again later");
        }
        Job job = tryStart(key, "restore");
        executor.submit(() -> {
            boolean ok = false;
            String message = "";
            try {
                message = restore0(profile, id, job);
                ok = true;
                audit.log("backup_restore", ip, login, "ok", Map.of("profile", key, "id", id));
            } catch (Throwable t) {
                message = String.valueOf(t.getMessage());
                logger.error("Backup restore failed for {}", key, t);
                audit.log("backup_restore", ip, login, "error: " + message,
                        Map.of("profile", key, "id", id));
            } finally {
                finish(key, "restore", ok, message);
            }
        });
    }

    /** Переименовать бекап (сторона переписывается, архив не трогаем). */
    public void rename(String profileDir, String id, String newName) throws IOException {
        Path metaFile = metaPath(profileDir, id);
        if (!Files.isRegularFile(metaFile)) throw new IOException("Backup not found: " + id);
        BackupMeta meta = Utils.readJson(metaFile, BackupMeta.class);
        if (meta == null) throw new IOException("Backup not found: " + id);
        meta.name = newName;
        Utils.writeJsonAtomic(metaFile, meta);
    }

    /** Заморозить/разморозить бекап. */
    public void setFrozen(String profileDir, String id, boolean frozen) throws IOException {
        Path metaFile = metaPath(profileDir, id);
        if (!Files.isRegularFile(metaFile)) throw new IOException("Backup not found: " + id);
        BackupMeta meta = Utils.readJson(metaFile, BackupMeta.class);
        if (meta == null) throw new IOException("Backup not found: " + id);
        meta.frozen = frozen;
        Utils.writeJsonAtomic(metaFile, meta);
    }

    /** Удалить бекап (замороженные не удаляются). */
    public void delete(String profileDir, String id) throws IOException {
        Path metaFile = metaPath(profileDir, id);
        if (!Files.isRegularFile(metaFile)) throw new IOException("Backup not found: " + id);
        BackupMeta meta = Utils.readJson(metaFile, BackupMeta.class);
        if (meta != null && meta.frozen) {
            throw new IOException("Backup is frozen — unfreeze it first");
        }
        Files.deleteIfExists(metaFile);
        Files.deleteIfExists(archivePath(profileDir, id));
    }

    // ======================= Создание =======================

    /** Имя по умолчанию: дата и время создания. */
    public static String defaultName() {
        return LocalDateTime.now().format(DEFAULT_NAME);
    }

    /** Детерминированная прямая ссылка скачивания по привязке ModEntry
     *  (без похода в API). null — ссылку построить нельзя. */
    static String downloadUrl(ModEntry entry) {
        String fileName = UpdateService.sanitizeFileName(entry.installedFileName);
        String versionId = entry.installedVersionId;
        String projectId = entry.projectId;
        if (versionId == null || versionId.isBlank() || projectId == null || projectId.isBlank()) {
            return null;
        }
        if ("modrinth".equals(entry.source)) {
            // Формат CDN Modrinth (совпадает с files[].url из API)
            return "https://cdn.modrinth.com/data/" + pathSegment(projectId)
                    + "/versions/" + pathSegment(versionId)
                    + "/" + pathSegment(fileName);
        }
        if ("curseforge".equals(entry.source)) {
            // projectId = modId, versionId = fileId (числа). Публичный редирект CF.
            if (!projectId.matches("\\d+") || !versionId.matches("\\d+")) return null;
            return "https://www.curseforge.com/api/v1/mods/" + projectId
                    + "/files/" + versionId + "/download";
        }
        return null;
    }

    /** Кодирование сегмента URL-пути (пробел — %20, а не «+»). */
    private static String pathSegment(String segment) {
        return java.net.URLEncoder.encode(segment, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    /** env по спецификации mrpack: моды нужны обеим сторонам, ресурспаки
     *  и шейдеры — только клиенту. */
    private static Map<String, String> envFor(String kind) {
        Map<String, String> env = new LinkedHashMap<>();
        if (Kinds.RESOURCEPACK.equals(Kinds.normalize(kind))
                || Kinds.SHADER.equals(Kinds.normalize(kind))) {
            env.put("client", "required");
            env.put("server", "unsupported");
        } else {
            env.put("client", "required");
            env.put("server", "required");
        }
        return env;
    }

    private BackupMeta create0(ClientProfile profile, String name, Job job) throws Exception {
        String key = profile.getDir();
        var lock = modpacks.lock(key);
        lock.lock();
        try {
            Modpack modpack = modpacks.load(profile);
            Path profileDir = modpacks.profileDir(profile);
            List<String> folders = resolveFolders(profile, modpack);
            long createdAt = System.currentTimeMillis();
            String backupName = name != null && !name.isBlank() ? name : defaultName();
            String id = UUID.randomUUID().toString();

            MrpackIndex index = new MrpackIndex();
            index.versionId = LocalDateTime.now().format(VERSION_ID);
            index.name = backupName;
            index.summary = "ModsSite backup of profile '" + key + "'";
            String gameVersion = modpacks.gameVersion(profile, modpack);
            if (gameVersion != null && !gameVersion.isBlank()) {
                index.dependencies.put("minecraft", gameVersion);
            }

            // 1. Контент с привязкой к Modrinth/CurseForge — в files[] манифеста,
            //    сам файл в архив не кладём
            Set<String> linkedPaths = new LinkedHashSet<>();
            for (ModEntry entry : modpack.mods) {
                if (entry == null) continue;
                boolean bound = "modrinth".equals(entry.source) || "curseforge".equals(entry.source);
                if (!bound || entry.installedFileName == null || entry.installedFileName.isBlank()
                        || entry.installedVersionId == null || entry.installedVersionId.isBlank()) {
                    continue; // локальные/принятые из папок — уйдут в overrides
                }
                String subdir = Kinds.subdir(config, modpack, entry.kindOrMod());
                String fileName = UpdateService.sanitizeFileName(entry.installedFileName);
                String relPath = subdir.isEmpty() ? fileName : subdir + "/" + fileName;
                if (!isSafeRelativePath(relPath) || linkedPaths.contains(relPath)) continue;
                Path file = profileDir.resolve(relPath);
                if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)) continue;
                String url = downloadUrl(entry);
                if (url == null) continue; // без ссылки не бывает files[] — файл уйдёт в overrides
                String sha1 = UpdateService.hashFile(file, "SHA-1");
                // Файл изменён локально относительно установленной версии —
                // он больше не «тот файл с источника»: честно архивируем его
                if (entry.installedSha1 != null && !entry.installedSha1.isBlank()
                        && !sha1.equalsIgnoreCase(entry.installedSha1)) {
                    continue;
                }
                job.message = relPath;
                MrpackFile mf = new MrpackFile();
                mf.path = relPath;
                mf.hashes.put("sha1", sha1);
                mf.hashes.put("sha512", UpdateService.hashFile(file, "SHA-512"));
                mf.env = envFor(entry.kindOrMod());
                mf.downloads.add(url);
                mf.fileSize = Files.size(file);
                index.files.add(mf);
                linkedPaths.add(relPath);
            }

            // 2. Обход выбранных папок: всё, что не покрыто files[], — в overrides/
            List<OverrideFile> overrides = new ArrayList<>();
            Set<String> overridePaths = new LinkedHashSet<>();
            long totalBytes = 0;
            long limitBytes = config.backup.maxTotalMb * 1024L * 1024L;
            for (String folder : folders) {
                Path dir = profileDir.resolve(folder);
                if (!Files.isDirectory(dir)) continue; // папки может не быть — это норма
                totalBytes = walkFolder(profileDir, dir, folder, modpack, overrides, overridePaths,
                        linkedPaths, totalBytes, limitBytes, job);
                if (overrides.size() >= config.backup.maxFiles) {
                    throw new IOException("Backup aborted: more than " + config.backup.maxFiles
                            + " files (raise mods backup maxFiles or exclude folders)");
                }
            }

            // 3. Сборка .mrpack
            BackupSidecar sidecar = new BackupSidecar();
            sidecar.id = id;
            sidecar.name = backupName;
            sidecar.createdAt = createdAt;
            sidecar.moduleVersion = moduleVersion;
            sidecar.profileDir = key;
            sidecar.profileTitle = profile.getTitle();
            sidecar.folders = folders;
            sidecar.modpack = modpack;

            Path dir = profileBackupsDir(key);
            IOHelper.createParentDirs(dir.resolve("x"));
            Path tmp = dir.resolve(id + ".mrpack.part");
            try (ZipOutputStream zip = new ZipOutputStream(
                    Files.newOutputStream(tmp), StandardCharsets.UTF_8)) {
                zip.setLevel(java.util.zip.Deflater.BEST_SPEED);
                putJsonEntry(zip, INDEX_ENTRY, index);
                putJsonEntry(zip, SIDECAR_ENTRY, sidecar);
                for (OverrideFile file : overrides) {
                    job.message = file.path;
                    ZipEntry entry = new ZipEntry(file.zipPath);
                    entry.setTime(createdAt);
                    zip.putNextEntry(entry);
                    try (InputStream input = Files.newInputStream(profileDir.resolve(file.path))) {
                        input.transferTo(zip);
                    }
                    zip.closeEntry();
                }
            }
            Path archive = archivePath(key, id);
            try {
                Files.move(tmp, archive, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmp, archive, StandardCopyOption.REPLACE_EXISTING);
            }

            // 4. Мета
            BackupMeta meta = new BackupMeta();
            meta.id = id;
            meta.name = backupName;
            meta.createdAt = createdAt;
            meta.profile = key;
            meta.moduleVersion = moduleVersion;
            meta.frozen = false;
            meta.remoteCount = index.files.size();
            meta.fileCount = overrides.size();
            Utils.writeJsonAtomic(metaPath(key, meta.id), meta);

            // 5. Авто-очистка по лимиту (замороженные не трогаем)
            prune(key);
            logger.info("[ModsSite] Backup '{}' (mrpack) created for {}: {} remote refs, {} overrides, {} bytes",
                    backupName, key, meta.remoteCount, meta.fileCount, Files.size(archive));
            return meta;
        } finally {
            lock.unlock();
        }
    }

    /** Обход одной папки бекапа: собирает overrides-записи. Возвращает новый totalBytes.
     *  Папки могут пересекаться (напр. mods и mods/sub) — дедуп по пути. */
    private long walkFolder(Path profileDir, Path dir, String folderName, Modpack modpack,
                            List<OverrideFile> overrides, Set<String> overridePaths,
                            Set<String> linkedPaths,
                            long totalBytes, long limitBytes, Job job) throws IOException {
        // ignore-шаблоны действуют только на три стандартные папки контента
        String kindOfFolder = kindFolderName(folderName, modpack);
        IgnoreMatcher ignore = kindOfFolder == null ? null
                : IgnoreMatcher.forKind(config, kindOfFolder);
        List<OverrideFile> collected = new ArrayList<>();
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                // Симлинки не архивируем: могут указывать наружу профиля
                if (Files.isSymbolicLink(d)) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(file)) return FileVisitResult.CONTINUE;
                if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                String name = file.getFileName().toString();
                if (name.endsWith(".part") || name.endsWith(".tmp")) return FileVisitResult.CONTINUE;
                String relPath = profileDir.relativize(file).toString().replace('\\', '/');
                if (!isSafeRelativePath(relPath)) return FileVisitResult.CONTINUE;
                if (linkedPaths.contains(relPath)) return FileVisitResult.CONTINUE; // умный дедуп
                if (overridePaths.contains(relPath)) return FileVisitResult.CONTINUE; // пересечение папок
                if (ignore != null && ignore.matchesName(name, false)) return FileVisitResult.CONTINUE;
                collected.add(new OverrideFile(relPath, OVERRIDES_PREFIX + relPath, attrs.size()));
                return FileVisitResult.CONTINUE;
            }
        });
        for (OverrideFile file : collected) {
            if (overrides.size() >= config.backup.maxFiles) {
                throw new IOException("Backup aborted: more than " + config.backup.maxFiles
                        + " files (raise mods backup maxFiles or exclude folders)");
            }
            totalBytes += file.size;
            if (totalBytes > limitBytes) {
                throw new IOException("Backup aborted: folders exceed " + config.backup.maxTotalMb
                        + " MB (raise backup.maxTotalMb or exclude folders)");
            }
            overrides.add(file);
            overridePaths.add(file.path);
        }
        return totalBytes;
    }

    /** Какому типу контента соответствует папка (для ignore-шаблонов), или null. */
    private String kindFolderName(String folder, Modpack modpack) {
        for (String kind : Kinds.ALL) {
            String subdir = Kinds.subdir(config, modpack, kind);
            if (!subdir.isEmpty() && subdir.equals(folder)) return kind;
        }
        return null;
    }

    private void putJsonEntry(ZipOutputStream zip, String name, Object value) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(System.currentTimeMillis());
        zip.putNextEntry(entry);
        zip.write(Utils.GSON.toJson(value).getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /** Удалить старые НЕзамороженные бекапы сверх лимита maxBackups. */
    private void prune(String profileDir) {
        // Сначала мусор от прерванных созданий и невалидные архивы —
        // чистится всегда, независимо от лимита
        try {
            Path dir = profileBackupsDir(profileDir);
            if (Files.isDirectory(dir)) {
                try (var stream = Files.newDirectoryStream(dir, "*.part")) {
                    for (Path part : stream) {
                        try {
                            Files.deleteIfExists(part);
                        } catch (IOException ignored) {
                        }
                    }
                }
                // Осиротевшие архивы без меты: сначала пробуем усыновить
                // (потерянная мета своего бекапа или ЧУЖОЙ mrpack, положенный
                // администратором вручную), невалидные — удаляем как мусор
                adoptOrphans(profileDir);
                try (var stream = Files.newDirectoryStream(dir, "*.mrpack")) {
                    for (Path archive : stream) {
                        String name = archive.getFileName().toString();
                        String id = idFromArchiveName(name);
                        if (id != null && Files.exists(dir.resolve(id + ".json"))) continue;
                        try {
                            Files.deleteIfExists(archive);
                            logger.warn("[ModsSite] Removed orphan backup archive {}", name);
                        } catch (IOException ignored) {
                        }
                    }
                }
            }
        } catch (IOException ignored) {
        }
        // Затем — лимит числа бекапов
        try {
            List<BackupMeta> all = list(profileDir);
            // Лимит считается только по АВТОУПРАВЛЯЕМЫМ бекапам: СВОИ и НЕ
            // замороженные. Чужие mrpack (положенные администратором вручную)
            // и замороженные (защищённые админом) квоту не занимают и
            // автоматически не удаляются — иначе лимит 1 при одном замороженном
            // бекапе удалял бы каждый свежесозданный бекап
            int ownCount = 0;
            for (BackupMeta meta : all) {
                if (!meta.foreign && !meta.frozen) ownCount++;
            }
            int over = ownCount - config.backup.maxBackups;
            if (over <= 0) return;
            // list — свежие сверху; удаляем самые старые НЕзамороженные СВОИ
            // бекапы. Чужие mrpack (положенные администратором вручную) не
            // трогаем: их автоскрытие из папки бекапов было бы неожиданным —
            // удаляются только вручную
            List<BackupMeta> deletable = new ArrayList<>();
            for (int i = all.size() - 1; i >= 0 && over > 0; i--) {
                if (!all.get(i).frozen && !all.get(i).foreign) {
                    deletable.add(all.get(i));
                    over--;
                }
            }
            for (BackupMeta meta : deletable) {
                try {
                    delete(profileDir, meta.id);
                    logger.info("[ModsSite] Backup pruned (limit {}): {} {}",
                            config.backup.maxBackups, meta.createdAt, meta.name);
                } catch (IOException e) {
                    logger.warn("Cannot prune backup {}", meta.id, e);
                }
            }
        } catch (IOException e) {
            logger.warn("Cannot prune backups for {}", profileDir, e);
        }
    }

    // ======================= Чужие .mrpack (усыновление) =======================

    /**
     * Усыновить .mrpack-архивы, лежащие в папке бекапов без меты. Это могут
     * быть и потерянные меты своих бекапов, и ЧУЖИЕ mrpack-файлы другой
     * сборки, положенные администратором вручную. Мета строится из
     * modrinth.index.json (спецификация Modrinth) или из сайдкара; архив
     * без валидного манифеста не усыновляется (prune удалит его как мусор).
     */
    private void adoptOrphans(String profileDir) {
        Path dir = profileBackupsDir(profileDir);
        if (!Files.isDirectory(dir)) return;
        try (var stream = Files.newDirectoryStream(dir, "*.mrpack")) {
            for (Path archive : stream) {
                String fileName = archive.getFileName().toString();
                String id = idFromArchiveName(fileName);
                if (id == null) continue;
                Path metaFile = dir.resolve(id + ".json");
                if (Files.exists(metaFile)) continue;
                // Имя файла должно совпадать с id (пути к архиву строятся по id):
                // файлы с пробелами/юникодом приводим к безопасному имени
                String stem = fileName.substring(0, fileName.length() - ".mrpack".length());
                if (!stem.equals(id)) {
                    Path renamed = dir.resolve(id + ".mrpack");
                    if (Files.exists(renamed)) {
                        logger.warn("[ModsSite] Cannot adopt mrpack '{}': '{}' already exists",
                                fileName, renamed.getFileName());
                        continue;
                    }
                    try {
                        Files.move(archive, renamed);
                        archive = renamed;
                        logger.info("[ModsSite] Foreign mrpack renamed to safe id: {}", id);
                    } catch (IOException e) {
                        logger.warn("[ModsSite] Cannot normalize mrpack name '{}'", fileName, e);
                        continue;
                    }
                }
                BackupMeta meta = buildForeignMeta(profileDir, id, archive);
                if (meta == null) continue;
                try {
                    Utils.writeJsonAtomic(dir.resolve(id + ".json"), meta);
                    logger.info("[ModsSite] Adopted {}mrpack backup '{}' ({}): {} remote refs, {} overrides",
                            meta.foreign ? "foreign " : "", meta.name, id, meta.remoteCount, meta.fileCount);
                } catch (IOException e) {
                    logger.warn("[ModsSite] Cannot adopt mrpack {}", archive.getFileName(), e);
                }
            }
        } catch (IOException e) {
            logger.warn("Cannot scan for unadopted mrpack backups in {}", dir, e);
        }
    }

    /** id бекапа из имени архива (null — имя небезопасно/не .mrpack). */
    static String idFromArchiveName(String fileName) {
        if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".mrpack")) return null;
        String stem = fileName.substring(0, fileName.length() - ".mrpack".length());
        String safe = ModpackService.safeFileName(stem);
        if (safe.isBlank() || safe.length() > 100 || safe.equals(".") || safe.contains("..")) return null;
        return safe;
    }

    /** Мета для усыновлённого mrpack (null — архив не валиден). */
    private BackupMeta buildForeignMeta(String profileDir, String id, Path archive) {
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            MrpackIndex index = readEntry(zip, INDEX_ENTRY, MrpackIndex.class);
            BackupSidecar sidecar = readEntry(zip, SIDECAR_ENTRY, BackupSidecar.class);
            if (index == null || index.formatVersion != MRPACK_FORMAT_VERSION
                    || !"minecraft".equals(index.game)) {
                return null;
            }
            int overrideCount = 0;
            for (var e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                if (!entry.isDirectory() && overridePath(entry.getName()) != null) overrideCount++;
            }
            BackupMeta meta = new BackupMeta();
            meta.id = id;
            meta.profile = profileDir;
            meta.frozen = false;
            meta.foreign = sidecar == null;
            meta.remoteCount = index.files == null ? 0 : index.files.size();
            meta.fileCount = overrideCount;
            if (sidecar != null) {
                // Свой бекап с потерянной метой — восстанавливаем её данные
                meta.name = sidecar.name != null && !sidecar.name.isBlank()
                        ? sidecar.name
                        : (index.name != null && !index.name.isBlank() ? index.name : id);
                meta.createdAt = sidecar.createdAt > 0 ? sidecar.createdAt : mtimeOf(archive);
                meta.moduleVersion = sidecar.moduleVersion;
            } else {
                meta.name = index.name != null && !index.name.isBlank() ? index.name : id;
                meta.createdAt = mtimeOf(archive);
                meta.moduleVersion = null;
            }
            return meta;
        } catch (Exception e) {
            logger.warn("[ModsSite] Not a valid mrpack backup: {}", archive.getFileName(), e);
            return null;
        }
    }

    private static long mtimeOf(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return System.currentTimeMillis();
        }
    }

    /**
     * Состояние модпака для восстановления ЧУЖОГО mrpack: записи создаются
     * для files[] из стандартных папок контента (mods/resourcepacks/shaderpacks
     * с учётом переопределений текущего модпака); привязка к источнику —
     * по прямой ссылке из манифеста (cdn.modrinth.com или curseforge.com);
     * файлы без распознанной привязки становятся локальными записями —
     * дальше админ может принять их «импортом осиротевших». Файлы в других
     * папках остаются без записей (восстанавливаются и бекапятся как overrides).
     */
    private Modpack buildForeignSnapshot(ClientProfile profile, MrpackIndex index, Modpack current) {
        Modpack rebuilt = new Modpack();
        // Переопределения папок/версии и выбор папок бекапа — текущие
        rebuilt.gameVersion = current.gameVersion;
        rebuilt.loader = current.loader;
        rebuilt.modsDir = current.modsDir;
        rebuilt.resourcepacksDir = current.resourcepacksDir;
        rebuilt.shaderpacksDir = current.shaderpacksDir;
        rebuilt.backupFolders = current.backupFolders == null
                ? new ArrayList<>() : new ArrayList<>(current.backupFolders);
        Set<String> seenIds = new java.util.HashSet<>();
        for (MrpackFile file : index.files) {
            if (file == null || file.path == null) continue;
            // Серверный контент (env.client=unsupported) в клиентскую сборку
            // не ставится — записи для него тоже не создаём
            if ("unsupported".equalsIgnoreCase(file.env != null ? file.env.get("client") : null)) continue;
            int slash = file.path.lastIndexOf('/');
            if (slash <= 0) continue; // файл в корне профиля — не контент-папка
            String folder = file.path.substring(0, slash);
            String kind = kindFolderName(folder, rebuilt);
            if (kind == null) continue;
            String fileName = UpdateService.sanitizeFileName(file.path.substring(slash + 1));
            if (fileName.isBlank() || "mod.jar".equals(fileName)) continue;
            ModEntry entry = new ModEntry();
            String[] binding = parseBindingFromUrl(
                    file.downloads != null && !file.downloads.isEmpty() ? file.downloads.get(0) : null);
            if (binding != null) {
                entry.source = binding[0];
                entry.projectId = binding[1];
                entry.installedVersionId = binding[2];
                entry.id = binding[1];
            } else {
                entry.id = fileName.replaceFirst("\\.[^.]+$", "");
            }
            // Локальный id обязан быть уникальным в модпаке
            String base = entry.id;
            int n = 2;
            while (!seenIds.add(entry.id)) {
                entry.id = base + "-" + (n++);
            }
            entry.kind = kind;
            entry.name = fileName.replaceFirst("\\.[^.]+$", "").replace('_', ' ');
            entry.optional = "optional".equalsIgnoreCase(file.env != null ? file.env.get("client") : null);
            entry.installedFileName = fileName;
            entry.installedSha1 = file.hashes != null ? file.hashes.get("sha1") : null;
            entry.installedAt = System.currentTimeMillis();
            rebuilt.mods.add(entry);
        }
        return rebuilt;
    }

    /** Привязка files[] чужого mrpack к источнику — только для fallback-скачивания
     *  (id/имя/тип в снапшот проставляет buildForeignSnapshot). */
    private ModEntry foreignBinding(MrpackFile file) {
        if (file == null || file.path == null) return null;
        String[] binding = parseBindingFromUrl(
                file.downloads != null && !file.downloads.isEmpty() ? file.downloads.get(0) : null);
        if (binding == null) return null;
        ModEntry entry = new ModEntry();
        entry.source = binding[0];
        entry.projectId = binding[1];
        entry.installedVersionId = binding[2];
        return entry;
    }

    /**
     * Привязка {source, projectId, versionId} по прямой ссылке (null — не распознана):
     * cdn.modrinth.com/data/&lt;проект&gt;/versions/&lt;версия&gt;/&lt;файл&gt; и
     * curseforge.com/api/v1/mods/&lt;modId&gt;/files/&lt;fileId&gt;/download.
     */
    static String[] parseBindingFromUrl(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI uri = new URI(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())) return null;
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath();
            String[] parts = path.split("/");
            if ("cdn.modrinth.com".equals(host)) {
                // /data/<проект>/versions/<версия>/<файл>
                if (parts.length >= 6 && "data".equals(parts[1]) && "versions".equals(parts[3])
                        && !parts[2].isBlank() && !parts[4].isBlank()) {
                    return new String[]{"modrinth", parts[2], parts[4]};
                }
            } else if ("www.curseforge.com".equals(host) || "curseforge.com".equals(host)) {
                // /api/v1/mods/<modId>/files/<fileId>/download
                if (parts.length >= 7 && "api".equals(parts[1]) && "v1".equals(parts[2])
                        && "mods".equals(parts[3]) && "files".equals(parts[5])
                        && parts[4].matches("\\d+") && parts[6].matches("\\d+")) {
                    return new String[]{"curseforge", parts[4], parts[6]};
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Папки покрытия чужого бекапа: валидные верхние сегменты путей files[]
     *  и overrides (по ним removeStale удаляет файлы, отсутствующие в пакете). */
    private static List<String> coveredFolders(Set<String> backedUpPaths) {
        Set<String> names = new LinkedHashSet<>();
        for (String path : backedUpPaths) {
            if (path == null) continue;
            int slash = path.indexOf('/');
            if (slash <= 0) continue; // файлы в корне профиля не покрывают папок
            String folder = normalizeFolder(path.substring(0, slash));
            if (folder != null) names.add(folder);
        }
        return new ArrayList<>(names);
    }

    // ======================= Восстановление =======================

    private String restore0(ClientProfile profile, String id, Job job) throws Exception {
        String key = profile.getDir();
        Path archive = archivePath(key, id);
        if (!Files.isRegularFile(archive)) throw new IOException("Backup not found: " + id);

        // 1. Читаем манифест и сайдкар
        MrpackIndex index;
        BackupSidecar sidecar;
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            index = readEntry(zip, INDEX_ENTRY, MrpackIndex.class);
            sidecar = readEntry(zip, SIDECAR_ENTRY, BackupSidecar.class);
        }
        if (index == null) throw new IOException("Broken backup: " + INDEX_ENTRY
                + " is missing, empty or corrupted");
        if (index.formatVersion != MRPACK_FORMAT_VERSION) {
            throw new IOException("Unsupported mrpack formatVersion: " + index.formatVersion);
        }
        if (!"minecraft".equals(index.game)) {
            throw new IOException("Backup is not a minecraft modpack (game=" + index.game + ")");
        }
        if (index.files == null) index.files = new ArrayList<>();
        // Санити-лимит на число привязанных файлов (наш бекап ограничен
        // размером модпака; защищает от абсурдных подменённых манифестов)
        if (index.files.size() > 10_000) {
            throw new IOException("Broken backup: too many file entries (" + index.files.size() + ")");
        }

        // Чужой .mrpack (без modssite.index.json) или свой бекап
        boolean foreign = sidecar == null;
        Modpack current = modpacks.load(profile);
        Modpack snapshot;                          // состояние модпака после восстановления
        List<String> folders = null;               // папки покрытия (по ним удаляется лишнее)
        Map<String, ModEntry> linkedByPath = new LinkedHashMap<>(); // путь → привязка к источнику
        if (foreign) {
            // Состояние — из манифеста: записи создаются для files[] в
            // стандартных папках контента, переопределения папок/версии
            // и выбор папок бекапа сохраняются из текущего модпака
            snapshot = buildForeignSnapshot(profile, index, current);
            for (MrpackFile file : index.files) {
                ModEntry binding = foreignBinding(file);
                if (binding != null) linkedByPath.putIfAbsent(file.path, binding);
            }
        } else {
            if (sidecar.formatVersion != SIDECAR_VERSION) {
                throw new IOException("Unsupported backup sidecar version: " + sidecar.formatVersion);
            }
            if (!key.equals(sidecar.profileDir)) {
                throw new IOException("Backup belongs to profile '" + sidecar.profileDir
                        + "' and cannot be restored into '" + key + "'");
            }
            if (sidecar.modpack == null) throw new IOException("Broken backup: no modpack snapshot");
            if (sidecar.modpack.mods == null) sidecar.modpack.mods = new ArrayList<>();
            if (sidecar.folders == null) sidecar.folders = new ArrayList<>();
            // Папки сайдкара используются для обхода диска (удаление лишнего) —
            // каждая обязана пройти валидатор путей, иначе подменённый архив
            // смог бы вылезти за папку профиля
            for (String folder : sidecar.folders) {
                if (normalizeFolder(folder) == null) {
                    throw new IOException("Broken backup: unsafe folder '" + folder + "'");
                }
            }
            snapshot = sidecar.modpack;
            folders = sidecar.folders;
            for (ModEntry entry : snapshot.mods) {
                if (entry == null || entry.installedFileName == null || entry.installedFileName.isBlank()) {
                    continue;
                }
                String subdir = Kinds.subdir(config, snapshot, entry.kindOrMod());
                String fileName = UpdateService.sanitizeFileName(entry.installedFileName);
                String relPath = subdir.isEmpty() ? fileName : subdir + "/" + fileName;
                linkedByPath.putIfAbsent(relPath, entry);
            }
        }

        // 2. Валидация всех путей ДО начала записи
        Set<String> backedUpPaths = new LinkedHashSet<>();
        for (MrpackFile file : index.files) {
            if (file == null || file.path == null) throw new IOException("Broken backup: file entry without path");
            if (!isSafeRelativePath(file.path)) {
                throw new IOException("Broken backup: unsafe path '" + file.path + "'");
            }
            if (!backedUpPaths.add(file.path)) {
                throw new IOException("Broken backup: duplicate path '" + file.path + "'");
            }
        }
        List<String[]> overridePaths = new ArrayList<>(); // {zipEntryName, relPath}
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            for (var e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                String name = entry.getName();
                if (entry.isDirectory()) continue;
                String path = overridePath(name);
                if (path == null) continue;
                if (!isSafeRelativePath(path)) {
                    throw new IOException("Broken backup: unsafe path '" + path + "'");
                }
                if (!backedUpPaths.add(path)) {
                    // overrides и files[] не должны пересекаться; files[] победит,
                    // но конфликтного файла в overrides быть не должно — ломаный архив
                    throw new IOException("Broken backup: path conflict '" + path + "'");
                }
                overridePaths.add(new String[]{name, path});
                if (overridePaths.size() > config.backup.maxFiles) {
                    throw new IOException("Broken backup: more than " + config.backup.maxFiles
                            + " overrides (raise mods backup maxFiles)");
                }
            }
        }
        // У чужого бекапа папки покрытия выводятся из самого архива
        // (валидные верхние сегменты путей files[] и overrides)
        if (foreign) folders = coveredFolders(backedUpPaths);

        Path profileDir = modpacks.profileDir(profile);
        int filesRestored = 0;
        int remoteRestored = 0;
        int removedCount = 0;
        int skippedClient = 0;   // env.client=unsupported — серверный контент
        List<String> failures = new ArrayList<>();

        var lock = modpacks.lock(key);
        lock.lock();
        try {
            // 3. Честный снапшот: удалить в папках покрытия файлы, которых нет в бекапе
            try {
                removedCount = removeStale(profileDir, folders, snapshot, backedUpPaths, job);
            } catch (IOException e) {
                failures.add("stale cleanup: " + e.getMessage());
            }

            // 4. Распаковка overrides/ и client-overrides/ (с общим бюджетом
            //    байтов — защита от «zip-бомбы»: столько же, сколько разрешено
            //    при создании)
            long extractBudget = config.backup.maxTotalMb * 1024L * 1024L;
            try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
                for (String[] pair : overridePaths) {
                    job.message = pair[1];
                    ZipEntry entry = zip.getEntry(pair[0]);
                    if (entry == null) {
                        failures.add(pair[1] + " (missing in archive)");
                        continue;
                    }
                    try {
                        extractBudget = extractAtomic(zip, entry, profileDir.resolve(pair[1]), extractBudget);
                        filesRestored++;
                    } catch (IOException e) {
                        failures.add(pair[1] + " (" + e.getMessage() + ")");
                    }
                }
            }

            // 5. Привязанный контент — скачиваем заново с того же источника.
            //    Файлы с env.client=unsupported пропускаем: профиль LaunchServer —
            //    клиентская сборка (как клиентская установка у Modrinth App)
            for (MrpackFile file : index.files) {
                job.message = file.path;
                String clientEnv = file.env != null ? file.env.get("client") : null;
                if ("unsupported".equalsIgnoreCase(clientEnv)) {
                    skippedClient++;
                    continue;
                }
                try {
                    restoreRemote(profile, file, linkedByPath.get(file.path));
                    remoteRestored++;
                } catch (Exception e) {
                    failures.add(file.path + " (" + e.getMessage() + ")");
                }
            }

            // 6. Состояние модпака — из снапшота
            modpacks.save(profile, snapshot);

            // 7. Согласование updateOptional и пересчёт хэшей
            try {
                if (config.mods.syncProfileOptional) {
                    modpacks.optionalSync().reconcile(profile, snapshot);
                }
            } catch (Exception e) {
                failures.add("updateOptional sync: " + e.getMessage());
            }
            if (config.mods.autoRehash && modpacks.rehashSupported()) {
                try {
                    modpacks.rehash(key);
                } catch (Exception e) {
                    failures.add("rehash: " + e.getMessage());
                }
            }
        } finally {
            lock.unlock();
        }

        String backupTitle = foreign
                ? (index.name != null && !index.name.isBlank() ? index.name : id)
                : sidecar.name;
        logger.info("[ModsSite] Backup '{}' ({}mrpack) restored to {}: {} overrides, {} remote, "
                        + "{} removed, {} skipped(client-unsupported), {} failed",
                backupTitle, foreign ? "foreign " : "", key, filesRestored, remoteRestored,
                removedCount, skippedClient, failures.size());
        String summary = filesRestored + " files / " + remoteRestored + " remote / " + removedCount + " removed"
                + (skippedClient > 0 ? " / " + skippedClient + " server-only skipped" : "")
                + (failures.isEmpty() ? "" : " / " + failures.size() + " failed");
        if (foreign) summary = "foreign mrpack: " + summary;
        // Чужой пакет собран под другую версию Minecraft — не фатально,
        // но администратор должен увидеть это сразу
        String gameDep = index.dependencies != null ? index.dependencies.get("minecraft") : null;
        if (gameDep != null && !gameDep.isBlank()) {
            String profileVersion = modpacks.gameVersion(profile, current);
            if (profileVersion != null && !profileVersion.isBlank() && !gameDep.equals(profileVersion)) {
                summary += "; game " + gameDep + " != profile " + profileVersion;
                logger.warn("[ModsSite] Restored mrpack targets Minecraft {} but profile is {}",
                        gameDep, profileVersion);
            }
        }
        if (!failures.isEmpty()) {
            for (String failure : failures) logger.warn("[ModsSite] Restore failure: {}", failure);
        }
        return summary;
    }

    /** Прочитать JSON-элемент архива (null — отсутствует). */
    static <T> T readEntry(ZipFile zip, String name, Class<T> type) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) return null;
        try (Reader reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
            return Utils.GSON.fromJson(reader, type);
        }
    }

    /** Относительный путь overrides-записи (null — не overrides).
     *  server-overrides/ сознательно не применяется: профиль LaunchServer —
     *  клиентская сборка (как у Modrinth App при клиентской установке). */
    static String overridePath(String zipEntryName) {
        if (zipEntryName == null) return null;
        if (zipEntryName.startsWith(OVERRIDES_PREFIX)) {
            return zipEntryName.substring(OVERRIDES_PREFIX.length());
        }
        if (zipEntryName.startsWith(CLIENT_OVERRIDES_PREFIX)) {
            return zipEntryName.substring(CLIENT_OVERRIDES_PREFIX.length());
        }
        return null;
    }

    /** Распаковка с атомарной заменой: сначала .tmp, потом move.
     *  Возвращает остаток бюджета байтов (защита от zip-бомбы: копирование
     *  прерывается, как только лимит исчерпан). */
    private static long extractAtomic(ZipFile zip, ZipEntry entry, Path target, long budgetLeft)
            throws IOException {
        IOHelper.createParentDirs(target);
        Path tmp = target.resolveSibling(target.getFileName() + ".restore.tmp");
        long left = budgetLeft;
        try (InputStream input = zip.getInputStream(entry);
             OutputStream output = Files.newOutputStream(tmp)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                left -= read;
                if (left < 0) {
                    throw new IOException("archive exceeds the backup size limit ("
                            + "mods backup maxTotalMb)");
                }
                output.write(buffer, 0, read);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
            throw e;
        }
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return left;
    }

    /**
     * Удалить в папках покрытия файлы, которых нет в самом бекапе:
     * восстановление возвращает состояние снапшота, а не «накатывает поверх».
     * Игнорируются симлинки, .part/.tmp и ignore-шаблоны (их никогда не бекапили).
     */
    private int removeStale(Path profileDir, List<String> folders, Modpack modpack,
                            Set<String> backedUpPaths, Job job) throws IOException {
        int[] removed = {0};
        for (String folder : folders) {
            if (folder == null || folder.isBlank()) continue;
            Path dir = profileDir.resolve(folder);
            if (!Files.isDirectory(dir)) continue;
            String kindOfFolder = kindFolderName(folder, modpack);
            IgnoreMatcher ignore = kindOfFolder == null ? null
                    : IgnoreMatcher.forKind(config, kindOfFolder);
            job.message = folder;
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                    if (Files.isSymbolicLink(d)) return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (Files.isSymbolicLink(file)) return FileVisitResult.CONTINUE;
                    if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                    String name = file.getFileName().toString();
                    if (name.endsWith(".part") || name.endsWith(".tmp")) return FileVisitResult.CONTINUE;
                    String relPath = profileDir.relativize(file).toString().replace('\\', '/');
                    if (backedUpPaths.contains(relPath)) return FileVisitResult.CONTINUE;
                    if (ignore != null && ignore.matchesName(name, false)) return FileVisitResult.CONTINUE;
                    try {
                        Files.deleteIfExists(file);
                        removed[0]++;
                    } catch (IOException e) {
                        logger.warn("[ModsSite] Restore cleanup: cannot delete {}", relPath, e);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return removed[0];
    }

    /**
     * Скачать один привязанный файл из files[] манифеста.
     *
     * <p>Сначала — прямая ссылка downloads[0] (только https и только хосты из
     * allowlist), с обязательной сверкой sha1/sha512 из манифеста. Если не
     * вышло (файл переехал, projectId в привязке — слаг) — fallback: повторный
     * запрос версии по versionId через API источника (как при обычной установке).
     */
    private void restoreRemote(ClientProfile profile, MrpackFile file, ModEntry entry) throws Exception {
        Path target = modpacks.profileDir(profile).resolve(file.path);
        IOHelper.createParentDirs(target);
        Path temp = target.resolveSibling("." + target.getFileName() + ".restore.part");
        String manifestSha1 = file.hashes != null ? file.hashes.get("sha1") : null;
        String manifestSha512 = file.hashes != null ? file.hashes.get("sha512") : null;

        // 1. Прямая ссылка из манифеста
        String url = file.downloads != null && !file.downloads.isEmpty() ? file.downloads.get(0) : null;
        Exception directError = null;
        if (url != null && isAllowedDownloadUrl(url)) {
            try {
                updates.downloadToFile(url, temp, remoteVersionStub(url));
                verifyManifestHashes(temp, manifestSha1, manifestSha512, file.path);
                moveAtomic(temp, target);
                return;
            } catch (Exception e) {
                directError = e;
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                }
            }
        }

        // 2. Fallback: та же версия через API источника (по снапшоту модпака)
        if (entry != null && entry.installedVersionId != null && !entry.installedVersionId.isBlank()
                && ("modrinth".equals(entry.source) || "curseforge".equals(entry.source))) {
            ModsApiProvider provider = updates.provider(entry.source);
            if (provider != null && provider.available()) {
                try {
                    RemoteVersionInfo version = provider.versionById(entry.installedVersionId);
                    if (version != null && version.url != null && !version.url.isBlank()) {
                        updates.downloadToFile(version.url, temp, version);
                        // Файл обязан быть байт-в-байт тем, что был в бекапе
                        verifyManifestHashes(temp, manifestSha1, manifestSha512, file.path);
                        moveAtomic(temp, target);
                        return;
                    }
                } catch (Exception e) {
                    if (directError != null) e.addSuppressed(directError);
                    try {
                        Files.deleteIfExists(temp);
                    } catch (IOException ignored) {
                    }
                    throw new IOException("download failed: " + e.getMessage()
                            + (isBlockedUrl(url) ? " (host not allowed: " + hostOf(url) + ")" : ""), e);
                }
            }
        }
        if (directError != null) {
            throw new IOException("download failed: " + directError.getMessage()
                    + (isBlockedUrl(url) ? " (host not allowed: " + hostOf(url) + ")" : ""), directError);
        }
        throw new IOException("no allowed download URL and no source binding for this file");
    }

    /** Заглушка версии для downloadToFile по прямой ссылке (хэшей нет —
     *  сверяем вручную по манифесту). */
    private static RemoteVersionInfo remoteVersionStub(String url) {
        RemoteVersionInfo stub = new RemoteVersionInfo();
        stub.url = url;
        return stub;
    }

    /** Обязательная сверка скачанного файла с хэшами манифеста (sha1+sha512). */
    private static void verifyManifestHashes(Path file, String sha1, String sha512, String path)
            throws IOException {
        if (sha1 != null && !sha1.isBlank()) {
            String actual = UpdateService.hashFile(file, "SHA-1");
            if (!actual.equalsIgnoreCase(sha1)) {
                throw new IOException("SHA-1 mismatch: file changed at the source (" + path + ")");
            }
        } else if (sha512 == null || sha512.isBlank()) {
            // По спецификации mrpack хэши обязательны; без них восстановление
            // привязанного файла небезопасно
            throw new IOException("no hashes in manifest for " + path);
        }
        if (sha512 != null && !sha512.isBlank()) {
            String actual = UpdateService.hashFile(file, "SHA-512");
            if (!actual.equalsIgnoreCase(sha512)) {
                throw new IOException("SHA-512 mismatch: file changed at the source (" + path + ")");
            }
        }
    }

    private static void moveAtomic(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Разрешена ли прямая ссылка скачивания: https + хост из allowlist. */
    static boolean isAllowedDownloadUrl(String url) {
        try {
            URI uri = new URI(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
            String host = uri.getHost();
            return host != null && ALLOWED_DOWNLOAD_HOSTS.contains(host.toLowerCase(Locale.ROOT));
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isBlockedUrl(String url) {
        return url != null && !url.isBlank() && !isAllowedDownloadUrl(url);
    }

    private static String hostOf(String url) {
        try {
            String host = new URI(url).getHost();
            return host == null ? "?" : host;
        } catch (Exception e) {
            return "?";
        }
    }

    /** Безопасен ли относительный путь (внутри папки профиля, без ../ и абсолютов). */
    public static boolean isSafeRelativePath(String path) {
        if (path == null || path.isEmpty() || path.length() > 512) return false;
        if (path.startsWith("/") || path.contains("\\") || path.contains("\u0000")) return false;
        if (path.contains("//") || path.contains("..")) return false;
        Path normalized = Path.of(path);
        if (normalized.isAbsolute() || normalized.getNameCount() == 0) return false;
        for (int i = 0; i < normalized.getNameCount(); i++) {
            String part = normalized.getName(i).toString();
            if (part.equals(".") || part.equals("..") || part.isEmpty()) return false;
        }
        return true;
    }
}
