package pro.gravit.launchermodules.modssite.mods;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pro.gravit.launcher.base.profiles.ClientProfile;
import pro.gravit.launchermodules.modssite.ModsSiteConfig;
import pro.gravit.utils.helper.IOHelper;
import pro.gravit.utils.helper.SecurityHelper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Проверка обновлений и скачивание файлов модов.
 * Ключевая особенность: при обновлении имя файла меняется, поэтому старый файл
 * удаляется по сохранённому installedFileName, а не по имени нового файла.
 */
public class UpdateService {
    private static final Logger logger = LogManager.getLogger(UpdateService.class);

    /** Конфиг не final: mods reload подменяет его живым значением. */
    private volatile ModsSiteConfig config;
    private final ModrinthApiProvider modrinth;
    private final CurseForgeApiProvider curseforge;
    private final HttpClient downloadClient;
    private final ExecutorService executor;

    public UpdateService(ModsSiteConfig config, ModrinthApiProvider modrinth, CurseForgeApiProvider curseforge) {
        this.config = config;
        this.modrinth = modrinth;
        this.curseforge = curseforge;
        this.downloadClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.executor = Executors.newFixedThreadPool(Math.max(1, config.mods.maxConcurrentDownloads),
                r -> {
                    Thread thread = new Thread(r, "ModsSite-Download");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    public ModsApiProvider provider(String id) {
        if ("modrinth".equals(id)) return modrinth;
        if ("curseforge".equals(id)) return curseforge;
        return null;
    }

    public List<ModsApiProvider> availableProviders() {
        List<ModsApiProvider> result = new ArrayList<>();
        if (modrinth.available()) result.add(modrinth);
        if (curseforge.available()) result.add(curseforge);
        return result;
    }

    /** Источники в порядке приоритета проверки обновлений (только доступные).
     *  Порядок — из настроек «Настройки → Обновления» (mods.updateSourcePriority). */
    public List<ModsApiProvider> priorityProviders() {
        List<ModsApiProvider> result = new ArrayList<>();
        for (String id : config.mods.updateSourcePriority) {
            ModsApiProvider provider = provider(id);
            if (provider != null && provider.available() && !result.contains(provider)) {
                result.add(provider);
            }
        }
        for (ModsApiProvider provider : availableProviders()) {
            if (!result.contains(provider)) result.add(provider);
        }
        return result;
    }

    /** Приоритет источника (меньше — раньше проверяется); -1 — не в списке. */
    private int priorityIndex(String sourceId) {
        if (sourceId == null) return Integer.MAX_VALUE;
        return config.mods.updateSourcePriority.indexOf(sourceId);
    }

    /** mods reload: подменить конфиг (TTL, приоритет источников и т.п.). */
    public void updateConfig(ModsSiteConfig newConfig) {
        if (newConfig == null) return;
        this.config = newConfig;
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    // ======================= Выбор версии =======================

    /** Разрешённые releaseType по каналу. */
    private static boolean channelAllows(String channel, String releaseType) {
        if (channel == null || channel.isBlank() || "release".equalsIgnoreCase(channel)) {
            return "release".equals(releaseType);
        }
        if ("beta".equalsIgnoreCase(channel)) {
            return "release".equals(releaseType) || "beta".equals(releaseType);
        }
        return true; // alpha
    }

    /** Свежая совместимая версия мода (или null). */
    public RemoteVersionInfo pickVersion(ModsApiProvider provider, String projectId,
                                         String gameVersion, String loader, String channel) throws Exception {
        List<RemoteVersionInfo> versions = provider.versions(projectId, gameVersion, loader);
        for (RemoteVersionInfo version : versions) {
            if (channelAllows(channel, version.releaseType)) {
                return version;
            }
        }
        return null;
    }

    // ======================= Проверка обновлений =======================

    public static class UpdateCheck {
        public boolean hasUpdate;
        public String error;
        /** Источник, у которого в итоге нашли мод (может отличаться после перепривязки). */
        public String source;
        /** Мод перепривязан к другому источнику (совпадение файла по хэшу/поиску). */
        public boolean relinked;
    }

    /** Итог проверки обновлений модпака: состояние каждого мода + предупреждения
     *  о сбоях источников (сеть, 403, троттлинг) — показываются в интерфейсе. */
    public static class CheckSummary {
        public final List<UpdateCheck> checks = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
    }

    /** Совпадение установленного файла с источником (по хэшам). */
    public static class HashMatch {
        public final ModsApiProvider provider;
        public final String projectId;
        public final String versionId;
        public final String versionNumber;
        public final String fileName;

        public HashMatch(ModsApiProvider provider, String projectId, String versionId,
                  String versionNumber, String fileName) {
            this.provider = provider;
            this.projectId = projectId;
            this.versionId = versionId;
            this.versionNumber = versionNumber;
            this.fileName = fileName;
        }
    }

    /**
     * Индекс сопоставления установленных файлов с источниками по хэшам:
     * Modrinth — пакетно по SHA-1 (POST /v2/version_files), CurseForge —
     * пакетно по murmur2-отпечаткам (POST /v1/fingerprints). Для каждого
     * мода хранятся совпадения по всем источникам — при проверке берётся
     * самый приоритетный (см. mods.updateSourcePriority).
     */
    private static class HashIndex {
        /** modId → источник → совпадение. */
        final Map<String, Map<String, HashMatch>> byMod = new HashMap<>();

        void put(String modId, HashMatch match) {
            byMod.computeIfAbsent(modId, k -> new LinkedHashMap<>())
                    .put(match.provider.id(), match);
        }

        /** Совпадения мода по всем источникам (может быть пусто). */
        Map<String, HashMatch> matches(String modId) {
            return byMod.getOrDefault(modId, Map.of());
        }
    }

    /** Хэш-профиль одного файла на диске. */
    public static class FileHashes {
        public String sha1;
        public long fingerprint;
        /** Имя установленного файла (для поиска по имени как запасного критерия). */
        public String fileName;
    }

    /**
     * Проверить обновления всех модов модпака с учётом приоритета источников:
     * сначала мод проверяется на приоритетном источнике; если его там нет
     * (проект удалён/источник недоступен/нет совместимых версий/запись «локальная»),
     * установленный файл ищется по хэшам на следующем источнике — и при
     * совпадении мод перепривязывается туда (это же генерирует метаданные
     * для модов без них). Локальные моды, которых не нашлось ни по одному
     * хэшу, дополнительно ищутся ПОИСКОМ (modId из jar) с проверкой файлов
     * версий по SHA-1/имени. Обновляет поля latest* у записей и сохраняет модпак.
     */
    public CheckSummary checkUpdates(ClientProfile profile, Modpack modpack,
                                      ModpackService modpackService, boolean force) {
        CheckSummary summary = new CheckSummary();
        List<UpdateCheck> results = summary.checks;
        boolean changed = false;
        String gameVersion = modpackService.gameVersion(profile, modpack);
        String loader = modpackService.loader(profile, modpack);
        // 1. Кто подлежит проверке (кэш записей с TTL)
        List<ModEntry> toCheck = new ArrayList<>();
        for (ModEntry entry : modpack.mods) {
            if (!force && entry.checkedAt > 0
                    && System.currentTimeMillis() - entry.checkedAt < config.mods.cacheTtlMinutes * 60_000L) {
                UpdateCheck cached = new UpdateCheck();
                cached.hasUpdate = entry.isUpdateAvailable();
                cached.source = entry.source;
                results.add(cached);
            } else {
                toCheck.add(entry);
                results.add(null); // placeholder, заполним ниже
            }
        }
        if (!toCheck.isEmpty()) {
            // 2. Пакетное сопоставление файлов по хэшам на всех доступных источниках
            HashIndex index = buildHashIndex(profile, modpack, modpackService, toCheck, summary.warnings);
            // 3. Разрешение по приоритету + перепривязка + проверка версий
            int i = 0;
            for (ModEntry entry : modpack.mods) {
                if (i >= results.size()) break;
                if (results.get(i) != null) { i++; continue; }
                UpdateCheck check;
                try {
                    check = resolveUpdate(profile, modpack, modpackService,
                            entry, gameVersion, loader, index, null, summary.warnings);
                } catch (Throwable t) {
                    // Сбой на ОДНОМ моде не должен убивать всю проверку
                    // (раньше UnsupportedOperationException на одном моде
                    // ронял весь /api/mods/check)
                    logger.error("Update check crashed for mod '{}'", entry.id, t);
                    check = new UpdateCheck();
                    check.error = "check failed: " + String.valueOf(t.getMessage());
                    entry.checkedAt = System.currentTimeMillis();
                    String message = "Проверка '" + entry.id + "' не удалась — "
                            + String.valueOf(t.getMessage());
                    if (!summary.warnings.contains(message)) summary.warnings.add(message);
                }
                if (check.relinked) changed = true;
                changed |= check.source != null;
                results.set(i, check);
                i++;
            }
        }
        // Автоподтяжка зависимостей/конфликтов при проверке обновлений УБРАНА:
        // для уже установленных модов она не нужна — связи подтягиваются при
        // СКАЧИВАНИИ/добавлении (ModpackService.add, настройка autoResolveDeps)
        // или явно кнопкой в настройках записи (/api/mods/resolve-deps).
        if (changed) {
            modpackService.save(profile, modpack);
        }
        return summary;
    }

    /** Хэши файла мода (SHA-1 для Modrinth, murmur2 для CurseForge). */
    private FileHashes fileHashes(ClientProfile profile, Modpack modpack,
                                  ModpackService modpackService, ModEntry entry) {
        if (entry.installedFileName == null || entry.installedFileName.isBlank()) return null;
        Path file = modpackService.kindDir(profile, modpack, entry.kindOrMod())
                .resolve(sanitizeFileName(entry.installedFileName));
        if (!Files.isRegularFile(file)) return null;
        FileHashes hashes = new FileHashes();
        hashes.fileName = entry.installedFileName;
        try {
            hashes.sha1 = entry.installedSha1 != null && !entry.installedSha1.isBlank()
                    ? entry.installedSha1 : ImportService.sha1Hex(file);
            hashes.fingerprint = CurseForgeFingerprint.of(file);
        } catch (Exception e) {
            logger.warn("Cannot hash {}: {}", entry.installedFileName, String.valueOf(e.getMessage()));
            return null;
        }
        return hashes;
    }

    /** Пакетно сопоставить файлы проверяемых модов со всеми доступными источниками. */
    private HashIndex buildHashIndex(ClientProfile profile, Modpack modpack,
                                     ModpackService modpackService, List<ModEntry> entries,
                                     List<String> warnings) {
        HashIndex index = new HashIndex();
        Map<String, FileHashes> hashesByMod = new LinkedHashMap<>();
        List<String> sha1s = new ArrayList<>();
        List<Long> fingerprints = new ArrayList<>();
        for (ModEntry entry : entries) {
            FileHashes hashes = fileHashes(profile, modpack, modpackService, entry);
            if (hashes == null) continue;
            hashesByMod.put(entry.id, hashes);
            if (hashes.sha1 != null) sha1s.add(hashes.sha1);
            if (hashes.fingerprint > 0) fingerprints.add(hashes.fingerprint);
        }
        Map<String, RemoteVersionInfo> modrinthBySha1 = Map.of();
        if (!sha1s.isEmpty() && modrinth.available()) {
            try {
                modrinthBySha1 = modrinth.versionsBySha1(sha1s);
            } catch (Exception e) {
                String message = "Modrinth: сопоставление по SHA-1 не удалось — " + String.valueOf(e.getMessage());
                logger.warn("Modrinth hash lookup failed: {}", String.valueOf(e.getMessage()));
                if (warnings != null) warnings.add(message);
            }
        }
        Map<Long, CurseForgeApiProvider.FingerprintMatch> cfByFingerprint = Map.of();
        if (!fingerprints.isEmpty() && curseforge.available()) {
            for (int from = 0; from < fingerprints.size(); from += 100) {
                List<Long> chunk = fingerprints.subList(from, Math.min(from + 100, fingerprints.size()));
                try {
                    cfByFingerprint = new LinkedHashMap<>(cfByFingerprint);
                    cfByFingerprint.putAll(curseforge.fingerprintMatches(chunk));
                } catch (Exception e) {
                    String message = "CurseForge: сопоставление по отпечаткам не удалось — " + String.valueOf(e.getMessage());
                    logger.warn("CurseForge fingerprint lookup failed: {}", String.valueOf(e.getMessage()));
                    if (warnings != null && !warnings.contains(message)) warnings.add(message);
                }
            }
        }
        for (Map.Entry<String, FileHashes> e : hashesByMod.entrySet()) {
            String modId = e.getKey();
            FileHashes hashes = e.getValue();
            RemoteVersionInfo mr = modrinthBySha1.get(hashes.sha1);
            if (mr != null && mr.projectId != null) {
                index.put(modId, new HashMatch(modrinth, mr.projectId, mr.versionId,
                        mr.versionNumber, mr.fileName));
            }
            CurseForgeApiProvider.FingerprintMatch cf = cfByFingerprint.get(hashes.fingerprint);
            if (cf != null && cf.modId() > 0) {
                index.put(modId, new HashMatch(curseforge, String.valueOf(cf.modId()),
                        String.valueOf(cf.fileId()), cf.displayName(), cf.fileName()));
            }
        }
        return index;
    }

    /**
     * Разрешить обновление одного мода по приоритету источников.
     * Порядок попыток: источник-владелец записи и совпадения по хэшам
     * выстраиваются по mods.updateSourcePriority; при совпадении файла на
     * более приоритетном источнике запись перепривязывается к нему.
     * Мод без источника («локальный») или без хэш-совпадений дополнительно
     * ищется ПОИСКОМ по modId (см. {@link #searchMatch}).
     *
     * @param singleHashes хэши для одиночной проверки (updateMods), иначе null —
     *                     берутся из index
     * @param warnings накопитель предупреждений для интерфейса (может быть null)
     */
    private UpdateCheck resolveUpdate(ClientProfile profile, Modpack modpack,
                                      ModpackService modpackService, ModEntry entry,
                                      String gameVersion, String loader,
                                      HashIndex index, FileHashes singleHashes,
                                      List<String> warnings) {
        UpdateCheck check = new UpdateCheck();
        // Фильтр загрузчика (fabric/forge/…) применим только к модам:
        // у ресурспаков/шейдеров он отсекал бы все версии
        String effectiveLoader = Kinds.loaderApplicable(entry.kindOrMod()) ? loader : null;
        // Совпадения по хэшам (для одиночной проверки — посчитать на месте).
        // ВАЖНО: снимок в ИЗМЕНЯЕМОЙ карте — matches() может вернуть Map.of()
        // (нет хэш-совпадений в пакетном индексе), а ниже поиск-фолбэк кладёт
        // в карту найденное совпадение; put в immutable Map.of() давал
        // UnsupportedOperationException и убивал всю проверку.
        Map<String, HashMatch> matches;
        if (index != null) {
            matches = new LinkedHashMap<>(index.matches(entry.id));
        } else {
            matches = new LinkedHashMap<>();
            if (singleHashes != null) {
                if (modrinth.available() && singleHashes.sha1 != null) {
                    try {
                        RemoteVersionInfo mr = modrinth.versionBySha1(singleHashes.sha1);
                        if (mr != null && mr.projectId != null) {
                            matches.put("modrinth", new HashMatch(modrinth, mr.projectId,
                                    mr.versionId, mr.versionNumber, mr.fileName));
                        }
                    } catch (Exception e) {
                        addWarning(warnings, "Modrinth: поиск по SHA-1 не удалось — " + String.valueOf(e.getMessage()));
                    }
                }
                if (curseforge.available() && singleHashes.fingerprint > 0) {
                    try {
                        Map<Long, CurseForgeApiProvider.FingerprintMatch> found =
                                curseforge.fingerprintMatches(List.of(singleHashes.fingerprint));
                        CurseForgeApiProvider.FingerprintMatch cf = found.get(singleHashes.fingerprint);
                        if (cf != null && cf.modId() > 0) {
                            matches.put("curseforge", new HashMatch(curseforge,
                                    String.valueOf(cf.modId()), String.valueOf(cf.fileId()),
                                    cf.displayName(), cf.fileName()));
                        }
                    } catch (Exception e) {
                        addWarning(warnings, "CurseForge: поиск по отпечатку не удалось — " + String.valueOf(e.getMessage()));
                    }
                }
            }
        }
        // Кандидаты в порядке приоритета: свой источник и хэш-совпадения
        List<ModsApiProvider> order = priorityProviders();
        String ownSource = entry.projectId != null && !entry.projectId.isBlank()
                && provider(entry.source) != null && provider(entry.source).available()
                ? entry.source : null;
        // Локальный мод (или мод недоступного источника) без хэш-совпадений:
        // ищем проект ПОИСКОМ по modId и сверяем файлы версий по SHA-1/имени —
        // так определяются моды, чьи jar пересобраны (хэш изменился) или
        // отсутствующие на приоритетном источнике
        if (matches.isEmpty() && ownSource == null) {
            FileHashes hashes = singleHashes != null ? singleHashes
                    : fileHashes(profile, modpack, modpackService, entry);
            if (hashes != null) {
                for (ModsApiProvider provider : order) {
                    HashMatch found = searchMatch(provider, entry, gameVersion, effectiveLoader, hashes);
                    if (found != null) {
                        matches.put(provider.id(), found);
                        break; // порядок = приоритет: нашли на более приоритетном — хватит
                    }
                }
            }
        }
        List<String> candidates = new ArrayList<>(); // id источников в порядке попытки
        for (ModsApiProvider provider : order) {
            if (provider.id().equals(ownSource) || matches.containsKey(provider.id())) {
                candidates.add(provider.id());
            }
        }
        String lastError = null;
        for (String sourceId : candidates) {
            ModsApiProvider provider = provider(sourceId);
            HashMatch match = matches.get(sourceId);
            boolean isOwn = sourceId.equals(ownSource);
            // Свой источник пробуется напрямую; чужой — только если файл совпал
            // по хэшу. Порядок — по приоритету: так мод с CurseForge перепривязывается
            // к Modrinth, если установленный файл есть и там.
            if (!isOwn && match == null) continue;
            // Перепривязка (для своего источника не нужна)
            if (!isOwn && match != null) {
                reLink(profile, modpack, modpackService, entry, provider, match);
                check.relinked = true;
            }
            try {
                RemoteVersionInfo latest = pickVersion(provider, entry.projectId, gameVersion, effectiveLoader, entry.channel);
                if (latest != null) {
                    applyLatest(entry, latest);
                } else {
                    entry.latestVersionId = null;
                }
                check.source = provider.id();
                entry.checkedAt = System.currentTimeMillis();
                check.hasUpdate = entry.isUpdateAvailable();
                if (latest == null) {
                    check.error = "no compatible version";
                }
                return check;
            } catch (Exception e) {
                lastError = String.valueOf(e.getMessage());
                logger.warn("Update check on {} failed for {}: {}", sourceId, entry.id, lastError);
                // источник не дал ответа — попробуем следующий кандидат
            }
        }
        // Ничего не сработало
        if (lastError == null) {
            lastError = ownSource == null && matches.isEmpty()
                    ? (entry.source == null || entry.projectId == null
                        ? "no metadata" : "source unavailable")
                    : "no compatible version";
        }
        check.error = lastError;
        entry.latestVersionId = null;
        entry.checkedAt = System.currentTimeMillis();
        check.hasUpdate = entry.isUpdateAvailable();
        return check;
    }

    private static void addWarning(List<String> warnings, String message) {
        if (warnings == null) return;
        if (!warnings.contains(message)) {
            warnings.add(message);
        }
    }

    /**
     * Запасное сопоставление локального мода с источником ПОИСКОМ, когда хэшей
     * недостаточно: у CurseForge/Modrinth файл мог быть пересобран автором
     * (murmur2/SHA-1 не совпадают с лежащим на диске jar) — тогда находим
     * проект по modId из метаданных jar и сверяем файлы его версий по
     * SHA-1/имени файла. SHA-1 совпадение — точное; имя файла — только если
     * источник не отдал SHA-1. Возврат null — проект не найден.
     *
     * <p>Стоимость: 1 запрос поиска + 1-3 запроса версий на мод (только для
     * ещё не сопоставленных модов). Поиск кэшируется общим кэшем источника.
     */
    public HashMatch searchMatch(ModsApiProvider provider, ModEntry entry,
                                 String gameVersion, String loader, FileHashes hashes) {
        if (provider == null || !provider.available()) return null;
        String key = entry.installedModId != null && !entry.installedModId.isBlank()
                ? entry.installedModId : entry.id;
        if (key == null || key.isBlank()) return null;
        String query = key.trim();
        try {
            ModsApiProvider.SearchHit slugHit = null;
            List<ModsApiProvider.SearchHit> candidates = new ArrayList<>();
            ModsApiProvider.SearchRequest request = new ModsApiProvider.SearchRequest();
            request.query = query;
            request.projectType = entry.kindOrMod();
            request.gameVersion = gameVersion;
            request.loader = loader;
            request.limit = 10;
            ModsApiProvider.SearchResult found = provider.search(request);
            for (ModsApiProvider.SearchHit hit : found.hits) {
                if (hit == null || hit.projectId == null) continue;
                if (slugHit == null && hit.slug != null
                        && hit.slug.equalsIgnoreCase(query)) {
                    slugHit = hit;
                }
                if (candidates.size() < 3 && hit != slugHit) {
                    candidates.add(hit);
                }
            }
            if (slugHit != null) {
                candidates.add(0, slugHit);
            }
            for (ModsApiProvider.SearchHit hit : candidates) {
                // сначала совместимые версии (узкий список), затем — все свежие
                List<RemoteVersionInfo> versions = provider.versions(hit.projectId, gameVersion, loader);
                HashMatch match = matchByVersionFile(provider, hit.projectId, versions, hashes);
                if (match == null && hit == slugHit) {
                    versions = provider.versions(hit.projectId, null, null);
                    match = matchByVersionFile(provider, hit.projectId, versions, hashes);
                }
                if (match != null) {
                    logger.info("[ModsSite] Mod '{}' matched {} project '{}' by search ({}); file verified by {}",
                            entry.id, provider.id(), hit.projectId,
                            hit.slug == null ? hit.title : hit.slug,
                            hashes.sha1 != null ? "SHA-1" : "file name");
                    return match;
                }
            }
        } catch (Exception e) {
            logger.debug("Search fallback for '{}' on {} failed: {}", entry.id, provider.id(),
                    String.valueOf(e.getMessage()));
        }
        return null;
    }

    /** Совпадение файла локального мода с одной из версий проекта. */
    private static HashMatch matchByVersionFile(ModsApiProvider provider, String projectId,
                                                List<RemoteVersionInfo> versions, FileHashes hashes) {
        if (versions == null) return null;
        for (RemoteVersionInfo version : versions) {
            if (version == null) continue;
            if (hashes.sha1 != null && version.sha1 != null
                    && version.sha1.equalsIgnoreCase(hashes.sha1)) {
                return new HashMatch(provider, projectId, version.versionId,
                        version.versionNumber, version.fileName);
            }
        }
        // Запасной критерий — точное имя файла (если SHA-1 источник не отдал)
        if (hashes.fileName != null) {
            for (RemoteVersionInfo version : versions) {
                if (version == null || version.sha1 != null) continue;
                if (version.fileName != null && version.fileName.equalsIgnoreCase(hashes.fileName)) {
                    return new HashMatch(provider, projectId, version.versionId,
                            version.versionNumber, version.fileName);
                }
            }
        }
        return null;
    }

    /** Перепривязать запись мода к источнику по совпадению файла (метаданные берутся пакетно). */
    private void reLink(ClientProfile profile, Modpack modpack, ModpackService modpackService,
                        ModEntry entry, ModsApiProvider provider, HashMatch match) {
        entry.source = provider.id();
        entry.projectId = match.projectId;
        // версия УСТАНОВЛЕННОГО файла на новом источнике — от неё считается обновление
        if (match.versionId != null) {
            entry.installedVersionId = match.versionId;
            entry.installedVersionNumber = match.versionNumber;
        }
        // имя файла на диске не меняем; richer-метаданные (имя/иконка/описание)
        try {
            ModsApiProvider.ProjectInfo info = provider.project(match.projectId);
            if (info != null) {
                if (info.title != null && !info.title.isBlank()) entry.name = info.title;
                if (info.description != null) entry.description = info.description;
                if (info.iconUrl != null) entry.iconUrl = info.iconUrl;
            }
        } catch (Exception e) {
            logger.debug("Project info fetch failed for re-link {}: {}", entry.id, String.valueOf(e.getMessage()));
        }
        logger.info("[ModsSite] Mod '{}' re-linked to {} (projectId={})", entry.id, provider.id(), match.projectId);
    }

    private static void applyLatest(ModEntry entry, RemoteVersionInfo latest) {
        entry.latestVersionId = latest.versionId;
        entry.latestVersionNumber = latest.versionNumber;
        entry.latestFileName = latest.fileName;
        entry.latestSha1 = latest.sha1;
        entry.latestUrl = latest.url;
        entry.latestDatePublished = latest.datePublished;
    }

    // ======================= Установка/обновление =======================

    public static class InstallResult {
        public String modId;
        public boolean success;
        public String version;
        public String fileName;
        public String error;
        /** Обновление пропущено: версия отложена кнопкой «Отложить». */
        public boolean postponed;

        InstallResult(String modId) {
            this.modId = modId;
        }
    }

    /**
     * Скачать и установить конкретную версию мода (заменяя старый файл — в т.ч.
     * при смене имени файла). Выполняется в вызывающем потоке.
     */
    public InstallResult install(ClientProfile profile, Modpack modpack,
                                 ModpackService modpackService, ModEntry entry, RemoteVersionInfo version) {
        InstallResult result = new InstallResult(entry.id);
        try {
            Path dir = modpackService.kindDir(profile, modpack, entry.kindOrMod());
            IOHelper.createParentDirs(dir.resolve("x"));
            String fileName = sanitizeFileName(version.fileName);
            Path target = dir.resolve(fileName);
            Path temp = dir.resolve(fileName + ".part");
            downloadToFile(version.url, temp, version);
            verify(temp, version);
            // Удаляем СТАРЫЙ файл — имя могло измениться!
            if (entry.installedFileName != null && !entry.installedFileName.isBlank()
                    && !entry.installedFileName.equals(fileName)) {
                Path old = dir.resolve(sanitizeFileName(entry.installedFileName));
                Files.deleteIfExists(old);
                // Запомнить прежнее имя: по нему находится привязка в updateOptional
                // (в т.ч. ручная запись админа), которую надо перепривязать
                entry.pushPreviousFileName(entry.installedFileName);
            }
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            entry.installedFileName = fileName;
            entry.installedVersionId = version.versionId;
            entry.installedVersionNumber = version.versionNumber;
            entry.installedSha1 = version.sha1;
            entry.installedAt = System.currentTimeMillis();
            // Метаданные мода из jar: modId используется как запасное имя записи
            // updateOptional (когда сервис не дал slug), имя — в описании записи
            ModJarInfo.ModMeta meta = ModJarInfo.read(target);
            entry.installedModId = meta != null ? meta.id : null;
            entry.installedModName = meta != null ? meta.name : null;
            result.success = true;
            result.version = version.versionNumber;
            result.fileName = fileName;
        } catch (Exception e) {
            logger.error("Install failed for {}", entry.id, e);
            result.error = String.valueOf(e.getMessage());
        }
        return result;
    }

    /**
     * Обновить моды (все с обновлениями или список ids) до свежих версий.
     * Отложенные кнопкой «Отложить» версии не ставятся; отсутствующий файл
     * скачивается даже без «обновления». Выключенные опциональные моды
     * обновляются только при updateDisabled=true (файл выключенного мода
     * отсутствует на диске — тогда просто скачиваем).
     */
    public List<InstallResult> updateMods(ClientProfile profile, Modpack modpack,
                                          ModpackService modpackService, Set<String> ids,
                                          boolean updateDisabled) throws Exception {
        String gameVersion = modpackService.gameVersion(profile, modpack);
        String loader = modpackService.loader(profile, modpack);
        List<Future<InstallResult>> futures = new ArrayList<>();
        List<ModEntry> toProcess = new ArrayList<>();
        for (ModEntry entry : modpack.mods) {
            boolean targeted = ids == null || ids.contains(entry.id);
            if (!targeted) continue;
            boolean isDisabledOptional = entry.optional && !entry.enabled;
            if (isDisabledOptional && !updateDisabled) continue;
            toProcess.add(entry);
        }
        for (ModEntry entry : toProcess) {
            futures.add(executor.submit(() -> installOrUpdate(profile, modpack, modpackService,
                    entry, gameVersion, loader)));
        }
        List<InstallResult> results = new ArrayList<>();
        for (Future<InstallResult> future : futures) {
            results.add(future.get(10, TimeUnit.MINUTES));
        }
        // Привязка updateOptional могла протухнуть: имена файлов изменились.
        // Синхронизируем ДО rehash и сохранения модпака.
        modpackService.optionalSync().reconcile(profile, modpack);
        modpackService.save(profile, modpack);
        return results;
    }

    /** Установить/обновить один мод: приоритет источников + учёт «Отложить». */
    private InstallResult installOrUpdate(ClientProfile profile, Modpack modpack,
                                          ModpackService modpackService, ModEntry entry,
                                          String gameVersion, String loader) {
        InstallResult result = new InstallResult(entry.id);
        try {
            Path dir = modpackService.kindDir(profile, modpack, entry.kindOrMod());
            boolean fileExists = entry.installedFileName != null && !entry.installedFileName.isBlank()
                    && Files.isRegularFile(dir.resolve(sanitizeFileName(entry.installedFileName)));
            // Локальный мод без привязки к сервису: файл уже на диске — нечего делать
            if ((entry.source == null || entry.projectId == null || provider(entry.source) == null)
                    && fileExists) {
                result.success = true;
                result.fileName = entry.installedFileName;
                result.version = entry.installedVersionNumber;
                return result;
            }
            // Актуализируем latest* той же приоритетной логикой, что и «Проверить
            // обновления» (в т.ч. перепривязка к приоритетному источнику)
            boolean stale = entry.checkedAt <= 0
                    || System.currentTimeMillis() - entry.checkedAt >= config.mods.cacheTtlMinutes * 60_000L
                    || entry.latestVersionId == null;
            if (stale) {
                FileHashes hashes = fileHashes(profile, modpack, modpackService, entry);
                resolveUpdate(profile, modpack, modpackService, entry, gameVersion, loader, null, hashes, null);
            }
            if (entry.latestVersionId == null) {
                if (fileExists) {
                    // совместимой версии нет, файл на месте — оставляем как есть
                    result.success = true;
                    result.fileName = entry.installedFileName;
                    result.version = entry.installedVersionNumber;
                    return result;
                }
                result.error = "no compatible version";
                return result;
            }
            // «Отложить»: последняя версия отложена и файл на месте — пропускаем
            if (entry.latestVersionId.equals(entry.skippedVersionId) && fileExists) {
                result.success = true;
                result.postponed = true;
                result.version = entry.latestVersionNumber;
                result.fileName = entry.installedFileName;
                return result;
            }
            RemoteVersionInfo version = latestAsVersion(entry);
            return install(profile, modpack, modpackService, entry, version);
        } catch (Exception e) {
            logger.error("Update failed for {}", entry.id, e);
            result.error = String.valueOf(e.getMessage());
            return result;
        }
    }

    /** Собрать RemoteVersionInfo из кэша последней проверки записи. */
    private static RemoteVersionInfo latestAsVersion(ModEntry entry) {
        RemoteVersionInfo version = new RemoteVersionInfo();
        version.source = entry.source;
        version.projectId = entry.projectId;
        version.versionId = entry.latestVersionId;
        version.versionNumber = entry.latestVersionNumber;
        version.fileName = entry.latestFileName;
        version.sha1 = entry.latestSha1;
        version.url = entry.latestUrl;
        version.datePublished = entry.latestDatePublished;
        version.releaseType = "release";
        return version;
    }

    /**
     * Применить состав модпака. Включение/выключение модов убрано
     * («скачан — значит нужен»): состав = весь модпак — доставить
     * недостающие файлы и согласовать updateOptional с профилем.
     * Возвращает результаты установки.
     */
    public List<InstallResult> applyComposition(ClientProfile profile, Modpack modpack,
                                                 ModpackService modpackService) throws Exception {
        // Все записи входят в состав: доставить недостающие файлы
        List<ModEntry> toInstall = new ArrayList<>();
        for (ModEntry entry : modpack.mods) {
            entry.enabled = true;
            Path file = null;
            if (entry.installedFileName != null && !entry.installedFileName.isBlank()) {
                Path dir = modpackService.kindDir(profile, modpack, entry.kindOrMod());
                file = dir.resolve(UpdateService.sanitizeFileName(entry.installedFileName));
            }
            boolean needInstall = entry.installedVersionId == null || file == null || !Files.exists(file);
            if (needInstall) {
                toInstall.add(entry);
            }
        }
        Set<String> ids = new HashSet<>();
        for (ModEntry entry : toInstall) ids.add(entry.id);
        List<InstallResult> results = ids.isEmpty() ? new ArrayList<>()
                : updateMods(profile, modpack, modpackService, ids, true);
        // Согласовать updateOptional с составом (в т.ч. записи бывших
        // «выключенных» из старых версий — теперь они тоже в составе) до rehash.
        modpackService.optionalSync().reconcile(profile, modpack);
        modpackService.save(profile, modpack);
        return results;
    }

    // ======================= Скачивание =======================

    /**
     * Предел размера скачиваемого файла: jar мода — единицы–десятки МБ;
     * лимит (с запасом) останавливает вырожденный/скомпрометированный
     * источник, отдающий гигабайты, — иначе диск исчерпывается раньше,
     * чем сработала бы проверка хэша.
     */
    private static final long MAX_DOWNLOAD_BYTES = 512L * 1024 * 1024;

    private void downloadToFile(String url, Path target, RemoteVersionInfo version) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(new URI(url))
                .timeout(Duration.ofSeconds(Math.max(10, config.mods.httpTimeoutSeconds)))
                .GET();
        if ("modrinth".equals(version.source) && config.mods.modrinth != null
                && config.mods.modrinth.userAgent != null && !config.mods.modrinth.userAgent.isBlank()) {
            builder.header("User-Agent", config.mods.modrinth.userAgent);
        }
        HttpResponse<InputStream> response = downloadClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Download failed: HTTP " + response.statusCode());
        }
        try (InputStream input = response.body()) {
            IOHelper.createParentDirs(target);
            Files.deleteIfExists(target);
            long total = 0;
            try (java.io.OutputStream output = Files.newOutputStream(target)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    total += read;
                    if (total > MAX_DOWNLOAD_BYTES) {
                        throw new IOException("Download aborted: file exceeds limit "
                                + (MAX_DOWNLOAD_BYTES / (1024 * 1024)) + " MB (" + target.getFileName() + ")");
                    }
                    output.write(buffer, 0, read);
                }
            } catch (Exception e) {
                // Не оставлять недокачанный файл: на следующей попытке начнём заново
                try {
                    Files.deleteIfExists(target);
                } catch (IOException ignored) {
                }
                throw e;
            }
        }
    }

    private void verify(Path file, RemoteVersionInfo version) throws IOException {
        if (!config.mods.verifyHash) return;
        if (version.sha512 != null && !version.sha512.isBlank()) {
            String actual = hashFile(file, "SHA-512");
            if (!actual.equalsIgnoreCase(version.sha512)) {
                throw new IOException("SHA-512 mismatch for " + file.getFileName());
            }
        } else if (version.sha1 != null && !version.sha1.isBlank()) {
            String actual = hashFile(file, "SHA-1");
            if (!actual.equalsIgnoreCase(version.sha1)) {
                throw new IOException("SHA-1 mismatch for " + file.getFileName());
            }
        } else if (!config.mods.allowWithoutHash) {
            throw new IOException("No hash provided by source for " + file.getFileName());
        }
    }

    private static String hashFile(Path file, String algorithm) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /** Безопасное имя файла (без путей). */
    public static String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) return "mod.jar";
        name = name.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.replace("\u0000", "");
        if (name.equals(".") || name.equals("..")) name = "mod.jar";
        return name;
    }

    public static byte[] sha256(Path file) throws IOException {
        return SecurityHelper.digest(SecurityHelper.DigestAlgorithm.SHA256, file);
    }
}
