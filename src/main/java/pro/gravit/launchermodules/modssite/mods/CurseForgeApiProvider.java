package pro.gravit.launchermodules.modssite.mods;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import pro.gravit.launchermodules.modssite.ModsSiteConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Клиент CurseForge Core API v1 (https://docs.curseforge.com).
 * Требуется персональный API-ключ (заявка на overwolf.com).
 */
public class CurseForgeApiProvider extends ModsApiProvider {
    private static final int GAME_ID_MINECRAFT = 432;
    private static final int CLASS_ID_MODS = 6;
    /** Resource Packs (texture packs) у CurseForge. */
    private static final int CLASS_ID_RESOURCE_PACKS = 12;
    /** Shader Packs у CurseForge. */
    private static final int CLASS_ID_SHADER_PACKS = 6552;
    /** Конфиг не final: mods reload подменяет его живым значением (apiKey/baseUrl/enabled). */
    private volatile ModsSiteConfig.CurseForgeConfig config;

    /** classId раздела CurseForge для типа контента (mods/resourcepack/shader). */
    private static int classIdOf(String projectType) {
        return switch (Kinds.normalize(projectType)) {
            case Kinds.RESOURCEPACK -> CLASS_ID_RESOURCE_PACKS;
            case Kinds.SHADER -> CLASS_ID_SHADER_PACKS;
            default -> CLASS_ID_MODS;
        };
    }

    public CurseForgeApiProvider(ModsSiteConfig.CurseForgeConfig config, long cacheTtlMs) {
        super(cacheTtlMs);
        this.config = config;
    }

    /** mods reload: подменить конфиг (apiKey/baseUrl/enabled) без пересоздания провайдера. */
    public void updateConfig(ModsSiteConfig.CurseForgeConfig newConfig) {
        if (newConfig == null) return;
        this.config = newConfig;
    }

    @Override
    public String id() {
        return "curseforge";
    }

    @Override
    public boolean available() {
        return config.enabled && config.apiKey != null && !config.apiKey.isBlank();
    }

    private String baseUrl() {
        String url = config.baseUrl;
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private Map<String, String> headers() {
        // User-Agent обязателен не по спеке, но CurseForge стоит за Cloudflare,
        // который может резать дефолтный UA Java-клиента; идентифицируемся честно.
        return Map.of(
                "x-api-key", config.apiKey,
                "User-Agent", "ModsSite_module/1.0 (GravitLauncher; CurseForge Core API)");
    }

    /** 401/403 у CurseForge — всегда отклонённый ключ (тело пустое и без ключа, и с неверным). */
    @Override
    protected String errorHint(int status, String body) {
        if (status == 401 || status == 403) {
            return " — CurseForge отклонил API-ключ. Проверьте mods.curseforge.apiKey в config/ModsSite/Config.json"
                    + " (ключ выдаётся/перевыпускается на console.curseforge.com), затем: mods reload и mods curseforge test";
        }
        if (status == 429) {
            return " — превышен лимит запросов CurseForge, повторите позже";
        }
        return super.errorHint(status, body);
    }

    /** modLoaderType для search: 0 Any, 1 Forge, 4 Fabric, 5 Quilt, 6 NeoForge. */
    private static Integer modLoaderType(String loader) {
        if (loader == null) return null;
        return switch (loader.toLowerCase()) {
            case "forge" -> 1;
            case "fabric" -> 4;
            case "quilt" -> 5;
            case "neoforge" -> 6;
            default -> null;
        };
    }

    /**
     * Ответы-списки CurseForge завёрнуты в объект: {"data": [...]}
     * (GetCategoriesResponse/GetModsResponse в официальной OpenAPI-спеке).
     * Раньше код ждал голый массив и всегда получал пустой список — из-за
     * этого категории CurseForge в фильтрах не показывались, а фильтр
     * поиска по категориям молча игнорировался. Здесь принимаем оба формата.
     */
    private static JsonArray dataArray(JsonElement element) {
        if (element == null || element.isJsonNull()) return null;
        if (element.isJsonArray()) return element.getAsJsonArray();
        if (element.isJsonObject()) {
            JsonElement data = element.getAsJsonObject().get("data");
            if (data != null && data.isJsonArray()) return data.getAsJsonArray();
        }
        return null;
    }

    /** Категория CurseForge: id/slug/name + служебные поля для иерархии и сортировки. */
    private record CfCategory(int id, String slug, String name, boolean isClass,
                              Integer parentCategoryId, Integer displayIndex) {
    }

    /**
     * Все категории раздела (classId по типу контента) с кэшем. В ответе могут
     * быть и классы-разделы (isClass=true, например Technology/Magic), и
     * подкатегории (isClass=false) — разбираем всё, выбор уровня для UI делает
     * {@link #categories(String)}.
     */
    private List<CfCategory> fetchCategories(int classId) throws Exception {
        JsonElement element = httpGetJson(baseUrl() + "/v1/categories?gameId=" + GAME_ID_MINECRAFT
                + "&classId=" + classId, headers());
        JsonArray array = dataArray(element);
        List<CfCategory> result = new ArrayList<>();
        if (array == null) return result;
        for (JsonElement e : array) {
            if (!e.isJsonObject()) continue;
            JsonObject object = e.getAsJsonObject();
            String slug = optString(object, "slug");
            String name = optString(object, "name");
            long id = optLong(object, "id", 0);
            if (slug == null || slug.isBlank() || name == null || id == 0) continue;
            boolean isClass = object.has("isClass") && !object.get("isClass").isJsonNull()
                    && object.get("isClass").getAsBoolean();
            Integer parent = object.has("parentCategoryId") && !object.get("parentCategoryId").isJsonNull()
                    ? (int) object.get("parentCategoryId").getAsLong() : null;
            Integer displayIndex = object.has("displayIndex") && !object.get("displayIndex").isJsonNull()
                    ? (int) object.get("displayIndex").getAsLong() : null;
            result.add(new CfCategory((int) id, slug, name, isClass, parent, displayIndex));
        }
        return result;
    }

    /** Порядок как на сайте CurseForge: displayIndex, пустые — в конец, по алфавиту. */
    private static final Comparator<CfCategory> CATEGORY_ORDER = Comparator
            .comparing((CfCategory c) -> c.displayIndex == null ? Integer.MAX_VALUE : c.displayIndex)
            .thenComparing(c -> c.name.toLowerCase());

    /** Имя загрузчика в списке gameVersions файла CF. */
    private static String cfLoaderName(String loader) {
        if (loader == null) return null;
        return switch (loader.toLowerCase()) {
            case "forge" -> "Forge";
            case "fabric" -> "Fabric";
            case "quilt" -> "Quilt";
            case "neoforge" -> "NeoForge";
            default -> null;
        };
    }

    /**
     * Алгоритм хэша файла CurseForge — SHA-1. По официальной спеке поле algo
     * — ЧИСЛО (1 = sha1, 2 = sha512), но отдельные ответы/зеркала отдают
     * строку «sha1»; принимаем оба варианта.
     */
    private static boolean isSha1Algo(String algo) {
        return "1".equals(algo) || "sha1".equalsIgnoreCase(algo) || "sha-1".equalsIgnoreCase(algo);
    }

    /** sortField CF: 1 Featured, 2 Popularity, 3 LastUpdated, 4 Name, 6 TotalDownloads, 11 CreationDate. */
    private static int sortField(String sort) {
        return switch (sort == null ? "relevance" : sort) {
            case "downloads" -> 6;   // TotalDownloads
            case "newest" -> 11;     // CreationDate
            case "updated" -> 3;     // LastUpdated
            // follows в CF нет — ближайшее по смыслу
            case "follows", "relevance" -> 2; // Popularity
            default -> 2;
        };
    }

    // ======================= Отпечатки файлов (импорт) =======================

    /** Совпадение отпечатка файла: какой мод и какой файл CurseForge знает под этим murmur2. */
    public record FingerprintMatch(long fingerprint, long fileId, long modId,
                                   String displayName, String fileName) {
    }

    /**
     * Пакетное сопоставление отпечатков: POST /v1/fingerprints/{gameId}
     * (murmur2, seed 1, без пробельных символов — см. CurseForgeFingerprint).
     * Нераспознанные отпечатки в ответе просто отсутствуют.
     *
     * <p>Формат ответа (официальная OpenAPI-спека, GetFingerprintMatchesResponse):
     * {@code {"data": {"isCacheBuilt": true, "exactMatches": [
     * {"id": &lt;fingerprint&gt;, "file": {...}, "latestFiles": [...]}, ...],
     * "exactFingerprints": [...], "unmatchedFingerprints": [...]}}} —
     * поле data здесь ОБЪЕКТ со списком exactMatches внутри, а не массив
     * (как у /v1/mods и /v1/categories). Раньше код ждал массив — из-за этого
     * сопоставление с CurseForge всегда возвращало пусто, и файлы, которых
     * нет на Modrinth, оставались «локальными». Для надёжности принимаем
     * и другие формы: голый массив exactMatches на верхнем уровне,
     * {@code {"data":[...]}} и {@code {"exactMatches":[...]}}.
     */
    public Map<Long, FingerprintMatch> fingerprintMatches(List<Long> fingerprints) throws Exception {
        Map<Long, FingerprintMatch> result = new LinkedHashMap<>();
        if (!available() || fingerprints == null || fingerprints.isEmpty()) return result;
        StringBuilder body = new StringBuilder("{\"fingerprints\":[");
        for (int i = 0; i < fingerprints.size(); i++) {
            if (i > 0) body.append(',');
            body.append(fingerprints.get(i));
        }
        body.append("]}");
        JsonElement element = httpPostJson(baseUrl() + "/v1/fingerprints/" + GAME_ID_MINECRAFT,
                headers(), body.toString());
        JsonArray array = fingerprintMatchesArray(element);
        if (array == null) {
            logger.warn("CurseForge fingerprints: unexpected response format (no exactMatches) — {}",
                    element == null ? "null" : element.toString().substring(0,
                            Math.min(160, element.toString().length())));
            return result;
        }
        for (JsonElement e : array) {
            if (!e.isJsonObject()) continue;
            JsonObject match = e.getAsJsonObject();
            long fingerprint = optLong(match, "id", 0);
            JsonObject file = optObject(match, "file");
            if (fingerprint == 0 || file == null) continue;
            result.put(fingerprint, new FingerprintMatch(fingerprint,
                    optLong(file, "id", 0), optLong(file, "modId", 0),
                    optString(file, "displayName"), optString(file, "fileName")));
        }
        return result;
    }

    /**
     * Вытащить список совпадений из любого из форматов ответа:
     * data.exactMatches (спека) / data как массив / exactMatches сверху / голый массив.
     */
    private static JsonArray fingerprintMatchesArray(JsonElement element) {
        if (element == null || element.isJsonNull()) return null;
        if (element.isJsonArray()) return element.getAsJsonArray();
        if (!element.isJsonObject()) return null;
        JsonObject object = element.getAsJsonObject();
        // 1. Спека: {"data": {"exactMatches": [...]}}
        JsonElement data = object.get("data");
        if (data != null && data.isJsonObject()) {
            JsonElement exact = data.getAsJsonObject().get("exactMatches");
            if (exact != null && exact.isJsonArray()) return exact.getAsJsonArray();
        }
        // 2. {"data": [...]} — на случай смены формата в духе остальных эндпоинтов
        if (data != null && data.isJsonArray()) return data.getAsJsonArray();
        // 3. {"exactMatches": [...]} сверху
        JsonElement exact = object.get("exactMatches");
        if (exact != null && exact.isJsonArray()) return exact.getAsJsonArray();
        return null;
    }

    /**
     * Выбранные слаги → id для categoryIds поискового запроса.
     * Класс-раздел (isClass=true) расширяется в [id класса + id его подкатегорий]:
     * у CurseForge моды помечены подкатегориями, а фильтрация по классу надёжнее
     * работает вместе с ними. Лимит CF — 10 id на запрос (официальная спека).
     */
    private List<Integer> resolveCategoryIds(List<String> slugs, int classId) throws Exception {
        List<CfCategory> all = fetchCategories(classId);
        if (all.isEmpty()) return List.of();
        Map<String, CfCategory> bySlug = new LinkedHashMap<>();
        Map<Integer, List<Integer>> children = new LinkedHashMap<>();
        for (CfCategory category : all) {
            bySlug.putIfAbsent(category.slug().toLowerCase(), category);
            if (category.isClass()) {
                children.computeIfAbsent(category.id(), k -> new ArrayList<>());
            }
        }
        for (CfCategory category : all) {
            if (!category.isClass() && category.parentCategoryId() != null) {
                children.computeIfAbsent(category.parentCategoryId(), k -> new ArrayList<>())
                        .add(category.id());
            }
        }
        Set<Integer> ids = new LinkedHashSet<>();
        for (String slug : slugs) {
            CfCategory category = bySlug.get(slug.toLowerCase());
            if (category == null) continue; // незнакомый слаг — пропускаем
            if (category.isClass()) {
                ids.add(category.id());
                List<Integer> kids = children.get(category.id());
                if (kids != null) ids.addAll(kids);
            } else {
                ids.add(category.id());
            }
        }
        List<Integer> result = new ArrayList<>(ids);
        if (result.size() > 10) {
            logger.warn("CurseForge: больше 10 categoryIds после расширения {} -> оставляю первые 10",
                    slugs);
            result = result.subList(0, 10);
        }
        return result;
    }

    @Override
    public SearchResult search(SearchRequest request) throws Exception {
        SearchResult result = new SearchResult();
        if (!available()) return result;
        String type = Kinds.normalize(request.projectType);
        boolean hasVersion = request.gameVersion != null && !request.gameVersion.isBlank();
        /*
         * По спецификации CurseForge modLoaderType «must be coupled with
         * gameVersion»: без версии игра возвращает РОВНО 0 результатов при
         * любом загрузчике (выглядит как «Найдено: 0»). Поэтому без версии
         * фильтр загрузчика не отправляем вовсе и сообщаем об этом в UI.
         * Для ресурспаков/шейдеров фильтр загрузчика неприменим вовсе —
         * у этих разделов загрузчиков нет.
         */
        Integer loaderType = Kinds.MOD.equals(type) ? modLoaderType(request.loader) : null;
        boolean loaderSent = false;
        if (loaderType != null && hasVersion) {
            loaderSent = true;
        } else if (loaderType != null) {
            result.warning = "loaderRequiresVersion";
        }
        result = searchPage(request, loaderSent ? loaderType : null, result.warning);
        /*
         * Цепочка фоллбэков «Найдено: 0» — как поиск на curseforge.com, который
         * НЕ навязывает версию игры жёстким фильтром (версия профиля подставляется
         * у нас автоматически, поэтому типичная картина «на сайте CurseForge есть,
         * у нас 0» и есть этот случай). Шаги:
         *   1) загрузчик + версия -> 0 : повтор только с версией
         *      (индекс загрузчика у CurseForge строится с запозданием);
         *   2) всё ещё 0 -> повтор без версии игры (жёсткий фильтр версии у
         *      Core API строже фасетного поиска сайта);
         * Каждый ослабленный шаг честно помечается предупреждением в UI.
         * Категории не ослабляем — это осознанный выбор пользователя.
         */
        if (result.total == 0 && result.hits.isEmpty()) {
            if (loaderSent) {
                SearchResult noLoader = searchPage(request, null, null);
                if (noLoader.total > 0 || !noLoader.hits.isEmpty()) {
                    noLoader.warning = "loaderFilterEmpty";
                    return noLoader;
                }
                // с загрузчиком пусто и без него пусто — пробуем ещё и без версии
                SearchResult noBoth = searchPage(request, null, null, null);
                if (noBoth.total > 0 || !noBoth.hits.isEmpty()) {
                    noBoth.warning = "filtersRelaxed";
                    return noBoth;
                }
            } else if (hasVersion) {
                SearchResult noVersion = searchPage(request, null, null, null);
                if (noVersion.total > 0 || !noVersion.hits.isEmpty()) {
                    noVersion.warning = "versionFilterEmpty";
                    return noVersion;
                }
            }
        }
        return result;
    }

    /** Одна страница поиска; loaderType == null — без фильтра загрузчика. */
    private SearchResult searchPage(SearchRequest request, Integer loaderType, String warning) throws Exception {
        return searchPage(request, loaderType, warning, request.gameVersion);
    }

    /** Одна страница поиска; gameVersion == null — без фильтра версии игры. */
    private SearchResult searchPage(SearchRequest request, Integer loaderType, String warning,
                                    String gameVersion) throws Exception {
        SearchResult result = new SearchResult();
        result.warning = warning;
        StringBuilder url = new StringBuilder(baseUrl()).append("/v1/mods/search?gameId=").append(GAME_ID_MINECRAFT)
                .append("&classId=").append(classIdOf(request.projectType))
                // %20, а не «+»: URLEncoder кодирует пробел как «+» (форма),
                // CurseForge ищет по нему буквально и многословный запрос даёт 0
                .append("&searchFilter=").append(queryEncode(request.query == null ? "" : request.query))
                .append("&pageSize=").append(Math.max(1, Math.min(request.limit, 30)))
                .append("&index=").append(Math.max(0, request.offset))
                .append("&sortField=").append(sortField(request.sort))
                .append("&sortOrder=desc");
        if (gameVersion != null && !gameVersion.isBlank()) {
            url.append("&gameVersion=").append(queryEncode(gameVersion));
        }
        if (loaderType != null) {
            url.append("&modLoaderType=").append(loaderType);
        }
        if (request.categories != null && !request.categories.isEmpty()) {
            List<Integer> ids = resolveCategoryIds(request.categories, classIdOf(request.projectType));
            // нераспознанные слаги пропускаем: фильтр по тому, что удалось сопоставить
            if (!ids.isEmpty()) {
                String joined = ids.stream().map(String::valueOf)
                        .collect(java.util.stream.Collectors.joining(","));
                url.append("&categoryIds=").append(queryEncode(joined));
            }
        }
        JsonObject response = httpGetJson(url.toString(), headers()).getAsJsonObject();
        JsonObject pagination = optObject(response, "pagination");
        // Спека CurseForge: поле называется totalCount (не total!) — раньше
        // читали «total», его в ответе НЕТ, и счётчик «Найдено» всегда был 0,
        // а пагинация в интерфейсе не работала. На случай нестандартных зеркал
        // принимаем и total, и resultCount как запасные.
        result.total = pagination == null ? 0
                : optLong(pagination, "totalCount", optLong(pagination, "total", 0));
        JsonArray dataArray = response.getAsJsonArray("data");
        // Диагностика «Найдено: 0»: если на странице есть результаты, а счётчик
        // нулевой — формат ответа изменился; печатаем фактическую pagination
        if (result.total == 0 && dataArray != null && !dataArray.isEmpty()) {
            logger.warn("CurseForge search: счётчик 0 при {} результатах на странице — pagination: {} "
                    + "(похоже, изменился формат ответа — пришлите этот лог разработчику)",
                    dataArray.size(), pagination);
        }
        // диагностика «Найдено: 0»: полный URL запроса и ответ — в debug-лог
        logger.debug("CurseForge search: {} -> total={}, data={}", url,
                result.total, dataArray == null ? "(нет)" : dataArray.size());
        if (dataArray == null) return result;
        for (JsonElement element : dataArray) {
            JsonObject object = element.getAsJsonObject();
            SearchHit item = new SearchHit();
            item.source = id();
            item.projectId = String.valueOf(optLong(object, "id", 0));
            item.slug = optString(object, "slug");
            item.title = optString(object, "name");
            item.description = optString(object, "summary");
            JsonObject logo = optObject(object, "logo");
            if (logo != null) {
                item.iconUrl = optString(logo, "thumbnailUrl");
                if (item.iconUrl == null) item.iconUrl = optString(logo, "url");
            }
            item.downloads = optLong(object, "downloadCount", 0);
            String date = optString(object, "dateModified");
            if (date != null) {
                try {
                    item.dateModified = java.time.Instant.parse(date).toEpochMilli();
                } catch (Exception ignored) {
                }
            }
            JsonElement catsElement = object.get("categories");
            if (catsElement != null && catsElement.isJsonArray()) {
                List<String> cats = new ArrayList<>();
                for (JsonElement cat : catsElement.getAsJsonArray()) {
                    if (!cat.isJsonObject()) continue;
                    String name = optString(cat.getAsJsonObject(), "name");
                    if (name != null) cats.add(name);
                    if (cats.size() >= 3) break;
                }
                item.categories = cats;
            }
            result.hits.add(item);
        }
        return result;
    }

    @Override
    public List<CategoryInfo> categories(String projectType) throws Exception {
        if (!available()) return List.of();
        List<CfCategory> all = fetchCategories(classIdOf(projectType));
        if (all.isEmpty()) return List.of();
        /*
         * Выбор уровня для фильтров UI — как на сайте CurseForge:
         * если в ответе есть классы-разделы (Technology, Magic, …), показываем их;
         * подкатегории при поиске подтянутся расширением (см. resolveCategoryIds).
         * Плоский ответ без классов (только категории) показываем целиком.
         */
        boolean hasClasses = all.stream().anyMatch(CfCategory::isClass);
        List<CfCategory> chosen = hasClasses
                ? all.stream().filter(CfCategory::isClass).sorted(CATEGORY_ORDER).toList()
                : all.stream().sorted(CATEGORY_ORDER).toList();
        List<CategoryInfo> list = new ArrayList<>();
        for (CfCategory category : chosen) {
            list.add(new CategoryInfo(category.slug().toLowerCase(), category.name()));
        }
        return list;
    }

    @Override
    public ProjectInfo project(String projectId) throws Exception {
        if (!available()) throw new ApiException("CurseForge disabled (no apiKey)");
        JsonObject object = httpGetJson(baseUrl() + "/v1/mods/" + urlEncode(projectId), headers())
                .getAsJsonObject().getAsJsonObject("data");
        ProjectInfo info = new ProjectInfo();
        info.source = id();
        info.projectId = String.valueOf(optLong(object, "id", 0));
        info.slug = optString(object, "slug");
        info.title = optString(object, "name");
        info.description = optString(object, "summary");
        JsonObject logo = optObject(object, "logo");
        if (logo != null) {
            info.iconUrl = optString(logo, "thumbnailUrl");
            if (info.iconUrl == null) info.iconUrl = optString(logo, "url");
        }
        return info;
    }

    @Override
    public Map<String, ProjectInfo> projects(List<String> projectIds) throws Exception {
        if (!available() || projectIds.isEmpty()) return Map.of();
        // GET /v1/mods?modIds=[...] -> {"data": [...]}
        JsonArray ids = new JsonArray();
        for (String s : projectIds) {
            try {
                ids.add(Long.parseLong(s.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        Map<String, ProjectInfo> result = new LinkedHashMap<>();
        JsonArray array = dataArray(httpGetJson(baseUrl() + "/v1/mods?modIds="
                + urlEncode(ids.toString()), headers()));
        if (array == null) return result;
        for (JsonElement e : array) {
            if (!e.isJsonObject()) continue;
            JsonObject object = e.getAsJsonObject();
            ProjectInfo info = new ProjectInfo();
            info.source = id();
            info.projectId = String.valueOf(optLong(object, "id", 0));
            info.slug = optString(object, "slug");
            info.title = optString(object, "name");
            info.description = optString(object, "summary");
            JsonObject logo = optObject(object, "logo");
            if (logo != null) {
                info.iconUrl = optString(logo, "thumbnailUrl");
                if (info.iconUrl == null) info.iconUrl = optString(logo, "url");
            }
            result.put(info.projectId, info);
        }
        return result;
    }

    @Override
    public List<RemoteVersionInfo> versions(String projectId, String gameVersion, String loader) throws Exception {
        if (!available()) return List.of();
        StringBuilder url = new StringBuilder(baseUrl()).append("/v1/mods/")
                .append(urlEncode(projectId)).append("/files?pageSize=40");
        if (gameVersion != null && !gameVersion.isBlank()) {
            url.append("&gameVersion=").append(queryEncode(gameVersion));
        }
        JsonObject response = httpGetJson(url.toString(), headers()).getAsJsonObject();
        String loaderName = cfLoaderName(loader);
        List<RemoteVersionInfo> result = new ArrayList<>();
        JsonArray dataArray = response.getAsJsonArray("data");
        if (dataArray == null) return result;
        for (JsonElement element : dataArray) {
            RemoteVersionInfo version = parseFile(element.getAsJsonObject());
            if (version == null) continue;
            // Фильтр по загрузчику: имя загрузчика должно быть в gameVersions файла
            if (loaderName != null && !version.gameVersions.contains(loaderName)) {
                continue;
            }
            boolean serverPack = isServerPack(element.getAsJsonObject());
            if (serverPack || version.url == null) {
                continue;
            }
            result.add(version);
        }
        // Свежие сверху
        result.sort((a, b) -> Long.compare(b.datePublished, a.datePublished));
        return result;
    }

    /** GET /v1/mods/files/{fileId} — точный файл по ID (восстановление бекапов). */
    @Override
    public RemoteVersionInfo versionById(String versionId) throws Exception {
        if (!available()) return null;
        if (versionId == null || versionId.isBlank()) return null;
        JsonObject file = httpGetJson(baseUrl() + "/v1/mods/files/" + urlEncode(versionId), headers()).getAsJsonObject();
        if (file == null || !file.has("id")) return null;
        return parseFile(file);
    }

    private static boolean isServerPack(JsonObject file) {
        return file.has("isServerPack") && !file.get("isServerPack").isJsonNull()
                && file.get("isServerPack").getAsBoolean();
    }

    /** Разбор объекта файла CurseForge (общий для /mods/{id}/files и /mods/files/{id}). */
    private RemoteVersionInfo parseFile(JsonObject file) {
        if (file == null) return null;
        RemoteVersionInfo version = new RemoteVersionInfo();
        version.source = id();
        version.projectId = String.valueOf(optLong(file, "modId", 0));
        version.versionId = String.valueOf(optLong(file, "id", 0));
        version.versionNumber = optString(file, "displayName");
        version.fileName = optString(file, "fileName");
        version.url = optString(file, "downloadUrl");
        version.datePublished = 0;
        String date = optString(file, "fileDate");
        if (date != null) {
            try {
                version.datePublished = java.time.Instant.parse(date).toEpochMilli();
            } catch (Exception ignored) {
            }
        }
        int releaseType = (int) optLong(file, "releaseType", 1);
        version.releaseType = switch (releaseType) {
            case 2 -> "beta";
            case 3 -> "alpha";
            default -> "release";
        };
        version.gameVersions = stringList(file.get("gameVersions"));
        version.loaders = version.gameVersions.stream()
                .filter(v -> "Forge".equals(v) || "Fabric".equals(v) || "Quilt".equals(v) || "NeoForge".equals(v))
                .toList();
        JsonArray depArray = file.getAsJsonArray("dependencies");
        if (depArray != null) {
            for (JsonElement dep : depArray) {
                JsonObject depObject = dep.getAsJsonObject();
                long modId = optLong(depObject, "modId", 0);
                int relationType = (int) optLong(depObject, "relationType", 1);
                // Спека CurseForge: 1=EmbeddedLibrary, 2=OptionalDependency,
                // 3=RequiredDependency, 4=Tool, 5=Incompatible
                // (раньше 1 и 3 были перепутаны: required-зависимости
                // считались встроенными и не подтягивались)
                String type = switch (relationType) {
                    case 3 -> "required";
                    case 2 -> "optional";
                    case 5 -> "incompatible";
                    default -> "embedded"; // 1=embedded, 4=tool
                };
                if (modId != 0) {
                    version.dependencies.add(new RemoteVersionInfo.RemoteDependency(id(), String.valueOf(modId), type));
                }
            }
        }
        JsonArray hashes = file.getAsJsonArray("hashes");
        if (hashes != null) {
            for (JsonElement h : hashes) {
                JsonObject hash = h.getAsJsonObject();
                if (isSha1Algo(optString(hash, "algo"))) {
                    version.sha1 = optString(hash, "value");
                }
            }
        }
        return version;
    }
}
