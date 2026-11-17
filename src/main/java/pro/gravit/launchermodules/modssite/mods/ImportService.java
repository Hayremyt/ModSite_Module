package pro.gravit.launchermodules.modssite.mods;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pro.gravit.launcher.base.profiles.ClientProfile;
import pro.gravit.launchermodules.modssite.ModsSiteConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Импорт уже лежащих в папке mods jar-файлов в модпак (кнопка «Импорт»
 * у блока «лишних файлов»). Типичный сценарий: готовый модпак залили на
 * сервер вручную и/или настроили updateOptional в профиле — сайт о таких
 * файлах не знает и помечает их как «лишние». Импорт сопоставляет каждый
 * файл с Modrinth/CurseForge ПО ХЭШАМ (Modrinth — SHA-1 файла, CurseForge —
 * murmur2-отпечаток /v1/fingerprints) и создаёт записи модпака, НЕ скачивая
 * файлы заново. После импорта мод участвует в проверке обновлений, а для
 * файлов из updateOptional профиля сохраняется опциональность (существующие
 * ручные записи подхватываются, выборы игроков не теряются).
 *
 * Файлы, которые не удалось сопоставить ни с одним сервисом, тоже
 * импортируются — как «локальные» (source=local, без проверки обновлений).
 */
public class ImportService {
    private static final Logger logger = LogManager.getLogger(ImportService.class);

    /** Пакет отпечатков за один вызов /v1/fingerprints (спека не задаёт лимит — берём с запасом). */
    private static final int FINGERPRINT_CHUNK = 100;
    /** Потоки для параллельных поисков по хэшам (Modrinth не умеет батч по SHA-1). */
    private static final int LOOKUP_THREADS = 4;

    /** Конфиг не final: mods reload подменяет его живым значением. */
    private volatile ModsSiteConfig config;
    private final ModpackService modpacks;
    private final UpdateService updates;
    private final ExecutorService executor;

    public ImportService(ModsSiteConfig config, ModpackService modpacks, UpdateService updates) {
        this.config = config;
        this.modpacks = modpacks;
        this.updates = updates;
        this.executor = Executors.newFixedThreadPool(LOOKUP_THREADS, r -> {
            Thread thread = new Thread(r, "ModsSite-Import");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    /** mods reload: подменить конфиг (шаблоны игнорирования, лимиты). */
    public void updateConfig(ModsSiteConfig newConfig) {
        if (newConfig == null) return;
        this.config = newConfig;
    }

    // ======================= Результаты =======================

    /** Итог по одному файлу: status = imported | local | failed | skipped | limit. */
    public static class FileResult {
        public String file;
        public String status;
        public String source;
        public String id;
        public String name;
        public String version;
        public String error;
    }

    public static class Report {
        public List<FileResult> results = new ArrayList<>();
        public int imported;
        public int local;
        public int failed;
        public int skipped;
        public int limit;
        /** Предупреждения для интерфейса: сбои источников, границы поиска и т.п. */
        public List<String> warnings = new ArrayList<>();
    }

    // ======================= Импорт =======================

    /** Совместимость: имена файлов без типа — все считаются модами. */
    public Report importFiles(ClientProfile profile, Modpack modpack, List<String> fileNames) throws IOException {
        List<ModpackService.OrphanFile> files = new ArrayList<>();
        for (String name : fileNames == null ? List.<String>of() : fileNames) {
            files.add(new ModpackService.OrphanFile(Kinds.MOD, name));
        }
        return importOrphans(profile, modpack, files);
    }

    public Report importOrphans(ClientProfile profile, Modpack modpack,
                                 List<ModpackService.OrphanFile> files) throws IOException {
        Report report = new Report();
        if (files == null || files.isEmpty()) return report;
        // 1. Валидация имён и вычисление хэшей
        List<Job> jobs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ModpackService.OrphanFile orphan : files) {
            FileResult result = new FileResult();
            result.file = orphan == null ? null : orphan.name;
            String raw = orphan == null ? null : orphan.name;
            String kind = Kinds.normalize(orphan == null ? null : orphan.kind);
            String name = UpdateService.sanitizeFileName(raw);
            if (raw == null || raw.isBlank() || name.equals("mod.jar")
                    || name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")) {
                result.status = "failed";
                result.error = "invalid file name";
                report.results.add(result);
                report.failed++;
                continue;
            }
            // Шаблоны игнорирования (.gitignore, настройки категорий): служебные
            // файлы (например, .index от Sinytra Connector) не хэшируем и не
            // отправляем запросы в Modrinth/CurseForge — не тратим на них силы
            if (IgnoreMatcher.forKind(config, kind).matchesName(name, false)) {
                result.status = "skipped";
                result.error = "ignored by settings";
                report.results.add(result);
                report.skipped++;
                continue;
            }
            if (!seen.add(kind + ":" + name)) {
                result.status = "skipped";
                result.error = "duplicate";
                report.results.add(result);
                report.skipped++;
                continue;
            }
            Path dir = modpacks.kindDir(profile, modpack, kind);
            Path file = dir.resolve(name);
            if (!Files.isRegularFile(file)) {
                result.status = "failed";
                result.error = "file not found";
                report.results.add(result);
                report.failed++;
                continue;
            }
            // Уже известен модпаку — просто пропустить
            boolean known = modpack.mods.stream()
                    .anyMatch(m -> name.equals(m.installedFileName) && kind.equals(m.kindOrMod()));
            if (known) {
                result.status = "skipped";
                result.error = "already in modpack";
                report.results.add(result);
                report.skipped++;
                continue;
            }
            Job job = new Job();
            job.name = name;
            job.kind = kind;
            job.file = file;
            job.result = result;
            try {
                job.sha1 = sha1Hex(file);
                job.fingerprint = CurseForgeFingerprint.of(file);
            } catch (Exception e) {
                result.status = "failed";
                result.error = "cannot read file: " + e.getMessage();
                report.results.add(result);
                report.failed++;
                continue;
            }
            jobs.add(job);
        }
        if (jobs.isEmpty()) return report;

        // 2. Пакетные поиски по хэшам: CurseForge (отпечатки murmur2, одним-двумя POST)
        //    и Modrinth (SHA-1, POST /v2/version_files). Порядок выбора источника —
        //    по приоритету из настроек (mods.updateSourcePriority).
        Map<Long, CurseForgeApiProvider.FingerprintMatch> cfMatches = new ConcurrentHashMap<>();
        ModsApiProvider cfProvider = updates.provider("curseforge");
        if (cfProvider instanceof CurseForgeApiProvider curseforge && curseforge.available()) {
            for (int from = 0; from < jobs.size(); from += FINGERPRINT_CHUNK) {
                List<Long> chunk = new ArrayList<>();
                for (int i = from; i < Math.min(from + FINGERPRINT_CHUNK, jobs.size()); i++) {
                    if (jobs.get(i).fingerprint > 0) chunk.add(jobs.get(i).fingerprint);
                }
                if (chunk.isEmpty()) continue;
                try {
                    cfMatches.putAll(curseforge.fingerprintMatches(chunk));
                } catch (Exception e) {
                    String message = "CurseForge: сопоставление по отпечаткам не удалось — "
                            + String.valueOf(e.getMessage());
                    logger.warn("CurseForge fingerprints lookup failed: {}", String.valueOf(e.getMessage()));
                    if (!report.warnings.contains(message)) report.warnings.add(message);
                }
            }
        }
        Map<String, RemoteVersionInfo> modrinthMatches = new ConcurrentHashMap<>();
        ModsApiProvider mrProvider = updates.provider("modrinth");
        if (mrProvider instanceof ModrinthApiProvider modrinth && modrinth.available()) {
            List<String> sha1s = new ArrayList<>();
            for (Job job : jobs) {
                if (job.sha1 != null) sha1s.add(job.sha1);
            }
            try {
                modrinthMatches.putAll(modrinth.versionsBySha1(sha1s));
            } catch (Exception e) {
                String message = "Modrinth: сопоставление по SHA-1 не удалось — "
                        + String.valueOf(e.getMessage());
                logger.warn("Modrinth hash lookup failed: {}", String.valueOf(e.getMessage()));
                if (!report.warnings.contains(message)) report.warnings.add(message);
            }
        }

        // 3. Параллельное сопоставление и создание записей.
        //    Опциональность — из updateOptional профиля: по привязке файла ИЛИ
        //    по имени записи (профили, собранные конвертерами/руками, читаются
        //    ещё и напрямую из profile.json — см. ProfileOptionalSync.view)
        ProfileOptionalSync.OptionalsView optionals = modpacks.optionalSync().view(profile);
        Map<String, ModsApiProvider.ProjectInfo> projectCache = new ConcurrentHashMap<>();
        List<Future<ModEntry>> futures = new ArrayList<>();
        for (Job job : jobs) {
            futures.add(executor.submit(() -> buildEntry(profile, modpack, job, cfMatches,
                    modrinthMatches, optionals, projectCache, report.warnings)));
        }
        for (int i = 0; i < futures.size(); i++) {
            Job job = jobs.get(i);
            FileResult result = job.result;
            try {
                ModEntry entry = futures.get(i).get(15, TimeUnit.MINUTES);
                if (entry == null) {
                    result.status = "failed";
                    result.error = "no match";
                    report.failed++;
                } else if (modpack.mods.size() >= config.mods.maxModsPerProfile) {
                    result.status = "limit";
                    result.error = "max mods per profile reached (" + config.mods.maxModsPerProfile + ")";
                    report.limit++;
                } else {
                    if (modpack.find(entry.id) != null) {
                        entry.id = uniqueId(modpack, entry.id);
                    }
                    modpack.mods.add(entry);
                    result.status = "local".equals(entry.source) ? "local" : "imported";
                    result.source = entry.source;
                    result.id = entry.id;
                    result.name = entry.name;
                    result.version = entry.installedVersionNumber;
                    if ("local".equals(entry.source)) report.local++;
                    else report.imported++;
                }
            } catch (Exception e) {
                result.status = "failed";
                result.error = String.valueOf(e.getMessage());
                report.failed++;
            }
            report.results.add(result);
        }

        // 4. Согласование updateOptional (ручные записи админа подхватываются),
        //    сохранение модпака. rehash делает вызывающий код.
        if (report.imported > 0 || report.local > 0) {
            modpacks.optionalSync().reconcile(profile, modpack);
            modpacks.save(profile, modpack);
        }
        return report;
    }

    // ======================= Внутреннее =======================

    private static final class Job {
        String name;
        String kind = Kinds.MOD;
        Path file;
        String sha1;
        long fingerprint;
        FileResult result;
    }

    /**
     * Сопоставить один файл с источниками по хэшам — в порядке приоритета
     * обновлений (mods.updateSourcePriority). Если хэш не совпал ни на одном
     * источнике, делается запасная попытка ПОИСКОМ по modId из jar (файлы
     * версий сверяются по SHA-1/имени — на случай пересобранных автором jar).
     * Несопоставленные файлы становятся «локальными» модами.
     */
    private ModEntry buildEntry(ClientProfile profile, Modpack modpack, Job job,
                                Map<Long, CurseForgeApiProvider.FingerprintMatch> cfMatches,
                                Map<String, RemoteVersionInfo> modrinthMatches,
                                ProfileOptionalSync.OptionalsView optionals,
                                Map<String, ModsApiProvider.ProjectInfo> projectCache,
                                List<String> warnings) throws Exception {
        ModEntry entry = new ModEntry();
        entry.kind = job.kind;
        entry.installedFileName = job.name;
        entry.installedSha1 = job.sha1;
        entry.installedAt = System.currentTimeMillis();
        ModJarInfo.ModMeta meta = ModJarInfo.read(job.file);
        entry.installedModId = meta == null ? null : meta.id;
        entry.installedModName = meta == null ? null : meta.name;
        entry.channel = config.mods.defaultChannel;

        // Источники в порядке приоритета: на первом, где файл найден (по хэшу,
        // а если не совпал — поиском с проверкой файла версии), мод и учитывается
        String gameVersion = modpacks.gameVersion(profile, modpack);
        String loader = modpacks.loader(profile, modpack);
        for (ModsApiProvider provider : updates.priorityProviders()) {
            if (provider instanceof CurseForgeApiProvider curseforge) {
                CurseForgeApiProvider.FingerprintMatch match = cfMatches.get(job.fingerprint);
                if (match != null && match.modId() > 0) {
                    ModsApiProvider.ProjectInfo info = projectInfo("curseforge",
                            String.valueOf(match.modId()), projectCache);
                    if (info == null) continue;
                    fillFromProject(entry, "curseforge", info, job);
                    entry.installedVersionId = String.valueOf(match.fileId());
                    entry.installedVersionNumber = match.displayName();
                    markOptional(entry, job, optionals);
                    return entry;
                }
            }
            if (provider instanceof ModrinthApiProvider) {
                RemoteVersionInfo version = modrinthMatches.get(job.sha1);
                if (version != null && version.projectId != null) {
                    ModsApiProvider.ProjectInfo info = projectInfo("modrinth", version.projectId, projectCache);
                    entry.source = "modrinth";
                    entry.projectId = version.projectId;
                    entry.id = info != null && info.slug != null && !info.slug.isBlank()
                            ? info.slug : "modrinth-" + version.projectId;
                    entry.name = info != null && info.title != null ? info.title
                            : (version.versionNumber != null ? version.versionNumber : job.name);
                    entry.description = info == null ? null : info.description;
                    entry.iconUrl = info == null ? null : info.iconUrl;
                    entry.installedVersionId = version.versionId;
                    entry.installedVersionNumber = version.versionNumber;
                    markOptional(entry, job, optionals);
                    return entry;
                }
            }
            // Хэш не совпал на этом источнике — запасная попытка поиском
            UpdateService.FileHashes hashes = new UpdateService.FileHashes();
            hashes.sha1 = job.sha1;
            hashes.fingerprint = job.fingerprint;
            hashes.fileName = job.name;
            UpdateService.HashMatch found = updates.searchMatch(provider, entry, gameVersion, loader, hashes);
            if (found != null) {
                ModsApiProvider.ProjectInfo info;
                try {
                    info = provider.project(found.projectId);
                } catch (Exception e) {
                    info = null;
                }
                fillFromProject(entry, provider.id(), info, job);
                entry.projectId = found.projectId;
                if (info == null || info.slug == null || info.slug.isBlank()) {
                    entry.id = provider.id() + "-" + found.projectId;
                }
                if (entry.name == null) {
                    entry.name = entry.installedModName != null ? entry.installedModName : job.name;
                }
                entry.installedVersionId = found.versionId;
                entry.installedVersionNumber = found.versionNumber;
                markOptional(entry, job, optionals);
                if (warnings != null && !warnings.contains(SEARCH_MATCH_NOTE)) {
                    warnings.add(SEARCH_MATCH_NOTE);
                }
                return entry;
            }
        }
        // Локальная запись (без проверки обновлений)
        String jarId = ModJarInfo.sanitizeId(meta == null ? null : meta.id);
        String jarName = meta == null ? null : meta.name;
        String stem = job.name;
        int dot = stem.lastIndexOf('.');
        if (dot > 0) stem = stem.substring(0, dot);
        entry.source = "local";
        entry.projectId = null;
        entry.id = jarId != null ? jarId : ModJarInfo.sanitizeId(stem);
        if (entry.id == null) entry.id = "local";
        entry.name = jarName != null ? jarName : stem;
        markOptional(entry, job, optionals);
        return entry;
    }

    /** Примечание в отчёте импорта, когда мод опознан поиском (а не по хэшу). */
    private static final String SEARCH_MATCH_NOTE =
            "Часть модов опознана поиском (хэш файла не совпал — возможно, автор пересобрал jar)";

    /** Заполнить источник/id/имя/описание/иконку из информации проекта. */
    private static void fillFromProject(ModEntry entry, String source,
                                        ModsApiProvider.ProjectInfo info, Job job) {
        entry.source = source;
        if (info == null) return;
        entry.projectId = info.projectId;
        entry.id = info.slug != null && !info.slug.isBlank() ? info.slug : source + "-" + info.projectId;
        entry.name = info.title != null ? info.title : job.name;
        entry.description = info.description;
        entry.iconUrl = info.iconUrl;
    }

    /** Опциональность из updateOptional профиля: по привязке файла (без учёта
     *  регистра) ИЛИ по совпадению имени записи с modId/id мода. */
    private void markOptional(ModEntry entry, Job job, ProfileOptionalSync.OptionalsView optionals) {
        boolean byFile = optionals != null && job.name != null && containsLower(optionals.boundFileNames, job.name);
        boolean byName = optionals != null
                && ProfileOptionalSync.matchesEntryName(entry, lowerSet(optionals.entryNames));
        entry.optional = byFile || byName;
        // скачан — значит нужен: выключения модов больше нет
        entry.enabled = true;
    }

    private static boolean containsLower(Set<String> values, String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        for (String value : values) {
            if (value != null && value.toLowerCase(java.util.Locale.ROOT).equals(lower)) return true;
        }
        return false;
    }

    private static Set<String> lowerSet(Set<String> values) {
        Set<String> result = new java.util.LinkedHashSet<>(values.size());
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                result.add(value.trim().toLowerCase(java.util.Locale.ROOT));
            }
        }
        return result;
    }

    /** Инфо о проекте с кэшем на время импорта (батч-эндпоинты требуют список id). */
    private ModsApiProvider.ProjectInfo projectInfo(String source, String projectId,
                                                    Map<String, ModsApiProvider.ProjectInfo> cache) throws Exception {
        String key = source + ":" + projectId;
        ModsApiProvider.ProjectInfo cached = cache.get(key);
        if (cached != null) {
            return cached == NULL_INFO ? null : cached;
        }
        ModsApiProvider provider = updates.provider(source);
        if (provider == null || !provider.available()) return null;
        try {
            ModsApiProvider.ProjectInfo info = provider.project(projectId);
            cache.put(key, info);
            return info;
        } catch (Exception e) {
            logger.warn("Cannot fetch project {}/{}: {}", source, projectId, String.valueOf(e.getMessage()));
            cache.put(key, NULL_INFO);
            return null;
        }
    }

    private static final ModsApiProvider.ProjectInfo NULL_INFO = new ModsApiProvider.ProjectInfo();

    private static String uniqueId(Modpack modpack, String base) {
        if (modpack.find(base) == null) return base;
        for (int i = 2; i < 100_000; i++) {
            String candidate = base + "-" + i;
            if (modpack.find(candidate) == null) return candidate;
        }
        return base + "-" + System.currentTimeMillis();
    }

    /** SHA-1 файла в hex (используется и проверкой обновлений). */
    static String sha1Hex(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
