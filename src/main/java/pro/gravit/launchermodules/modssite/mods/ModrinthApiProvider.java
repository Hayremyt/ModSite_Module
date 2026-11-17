package pro.gravit.launchermodules.modssite.mods;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import pro.gravit.launchermodules.modssite.ModsSiteConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Клиент Modrinth API v2 (https://docs.modrinth.com).
 * API открытый, но требует осмысленный User-Agent.
 */
public class ModrinthApiProvider extends ModsApiProvider {
    /** Конфиг не final: mods reload подменяет его живым значением (baseUrl/userAgent/enabled). */
    private volatile ModsSiteConfig.ModrinthConfig config;

    public ModrinthApiProvider(ModsSiteConfig.ModrinthConfig config, long cacheTtlMs) {
        super(cacheTtlMs);
        this.config = config;
    }

    /** mods reload: подменить конфиг без пересоздания провайдера. */
    public void updateConfig(ModsSiteConfig.ModrinthConfig newConfig) {
        if (newConfig == null) return;
        this.config = newConfig;
    }

    @Override
    public String id() {
        return "modrinth";
    }

    @Override
    public boolean available() {
        return config.enabled;
    }

    private String baseUrl() {
        String url = config.baseUrl;
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private Map<String, String> headers() {
        return Map.of("User-Agent", config.userAgent == null || config.userAgent.isBlank()
                ? "ModsSite_module/1.0" : config.userAgent);
    }

    /** У Modrinth ключа нет: 403 — почти всегда блокировка IP дата-центра на стороне Cloudflare. */
    @Override
    protected String errorHint(int status, String body) {
        if (status == 401 || status == 403) {
            return " — Modrinth ограничил доступ (обычно это блокировка IP хостинга/дата-центра, а не проблема модуля)";
        }
        if (status == 429) {
            return " — превышен лимит запросов Modrinth, повторите позже";
        }
        return super.errorHint(status, body);
    }

    /** Запасной список категорий — если /tag/category недоступен. */
    private static final List<String> FALLBACK_CATEGORIES = List.of(
            "adventure", "technology", "magic", "utility", "optimization", "worldgen",
            "storage", "equipment", "food", "game-mechanics", "library", "mobs",
            "transportation", "decoration", "economy", "social", "quests", "minigame");

    /** Запасные категории ресурспаков (по типам разрешения/стилю Modrinth). */
    private static final List<String> FALLBACK_CATEGORIES_RESOURCEPACK = List.of(
            "realistic", "semi-realistic", "vanilla-like", "cartoon", "pixel", "fantasy",
            "medieval", "modern", "sci-fi", "steampunk", "animated", "dark", "light", "miscellaneous");

    /** Запасные категории шейдеров. */
    private static final List<String> FALLBACK_CATEGORIES_SHADER = List.of(
            "cartoon", "fantasy", "realistic", "semi-realistic", "vanilla-like",
            "potato", "colored", "path-tracing", "stylized");

    /** index= для сортировки Modrinth. */
    private static String index(String sort) {
        return switch (sort == null ? "relevance" : sort) {
            case "downloads" -> "downloads";
            case "follows" -> "follows";
            case "newest" -> "newest";
            case "updated" -> "updated";
            default -> "relevance";
        };
    }

    @Override
    public SearchResult search(SearchRequest request) throws Exception {
        StringBuilder url = new StringBuilder(baseUrl()).append("/search?query=")
                .append(queryEncode(request.query == null ? "" : request.query))
                .append("&limit=").append(Math.max(1, Math.min(request.limit, 30)))
                .append("&offset=").append(Math.max(0, request.offset))
                .append("&index=").append(index(request.sort));
        JsonArray facets = new JsonArray();
        JsonArray projectType = new JsonArray();
        projectType.add("project_type:" + Kinds.normalize(request.projectType));
        facets.add(projectType);
        if (request.loader != null && !request.loader.isBlank() && !"any".equalsIgnoreCase(request.loader)) {
            JsonArray categories = new JsonArray();
            categories.add("categories:" + request.loader);
            facets.add(categories);
        }
        if (request.gameVersion != null && !request.gameVersion.isBlank()) {
            JsonArray versions = new JsonArray();
            versions.add("versions:" + request.gameVersion);
            facets.add(versions);
        }
        if (request.categories != null && !request.categories.isEmpty()) {
            // внутри одной группы — ИЛИ (мод подходит хотя бы под одну категорию)
            JsonArray categories = new JsonArray();
            request.categories.forEach(c -> categories.add("categories:" + c));
            facets.add(categories);
        }
        url.append("&facets=").append(queryEncode(facets.toString()));
        JsonObject response = httpGetJson(url.toString(), headers()).getAsJsonObject();
        SearchResult result = new SearchResult();
        result.total = optLong(response, "total_hits", 0);
        JsonArray hits = response.getAsJsonArray("hits");
        if (hits == null) return result;
        for (JsonElement element : hits) {
            JsonObject hit = element.getAsJsonObject();
            SearchHit item = new SearchHit();
            item.source = id();
            item.projectId = optString(hit, "project_id");
            item.slug = optString(hit, "slug");
            item.title = optString(hit, "title");
            item.description = optString(hit, "description");
            item.iconUrl = optString(hit, "icon_url");
            item.downloads = optLong(hit, "downloads", 0);
            item.follows = optLong(hit, "follows", 0);
            String date = optString(hit, "date_modified");
            if (date != null) {
                try {
                    item.dateModified = java.time.Instant.parse(date).toEpochMilli();
                } catch (Exception ignored) {
                }
            }
            List<String> cats = stringList(hit.get("display_categories"));
            if (cats.isEmpty()) cats = stringList(hit.get("categories"));
            item.categories = cats.stream().limit(3).toList();
            result.hits.add(item);
        }
        return result;
    }

    @Override
    public List<CategoryInfo> categories(String projectType) throws Exception {
        String type = Kinds.normalize(projectType);
        try {
            JsonElement element = httpGetJson(baseUrl() + "/tag/category", headers());
            List<CategoryInfo> list = new ArrayList<>();
            for (JsonElement e : element.getAsJsonArray()) {
                JsonObject object = e.getAsJsonObject();
                if (!type.equals(optString(object, "project_type"))) continue;
                String name = optString(object, "name");
                if (name != null && !name.isBlank()) list.add(new CategoryInfo(name, prettify(name)));
            }
            if (!list.isEmpty()) {
                list.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name));
                return list;
            }
        } catch (Exception ignored) {
            // сеть/недоступность — вернём запасной список ниже
        }
        List<String> fallback = switch (type) {
            case Kinds.RESOURCEPACK -> FALLBACK_CATEGORIES_RESOURCEPACK;
            case Kinds.SHADER -> FALLBACK_CATEGORIES_SHADER;
            default -> FALLBACK_CATEGORIES;
        };
        return fallback.stream().map(c -> new CategoryInfo(c, prettify(c))).toList();
    }

    @Override
    public ProjectInfo project(String projectId) throws Exception {
        JsonObject object = httpGetJson(baseUrl() + "/project/" + urlEncode(projectId), headers()).getAsJsonObject();
        ProjectInfo info = new ProjectInfo();
        info.source = id();
        info.projectId = optString(object, "id");
        info.slug = optString(object, "slug");
        info.title = optString(object, "title");
        info.description = optString(object, "description");
        info.iconUrl = optString(object, "icon_url");
        return info;
    }

    @Override
    public Map<String, ProjectInfo> projects(List<String> projectIds) throws Exception {
        // Modrinth: GET /projects?ids=["..."]
        JsonArray ids = new JsonArray();
        projectIds.forEach(ids::add);
        Map<String, ProjectInfo> result = new LinkedHashMap<>();
        JsonElement element = httpGetJson(baseUrl() + "/projects?ids=" + urlEncode(ids.toString()), headers());
        for (JsonElement e : element.getAsJsonArray()) {
            JsonObject object = e.getAsJsonObject();
            ProjectInfo info = new ProjectInfo();
            info.source = id();
            info.projectId = optString(object, "id");
            info.slug = optString(object, "slug");
            info.title = optString(object, "title");
            info.description = optString(object, "description");
            info.iconUrl = optString(object, "icon_url");
            result.put(info.projectId, info);
        }
        return result;
    }

    /**
     * Найти версию Modrinth по SHA-1 файла (GET /v2/version_file/{sha1}?algorithm=sha1).
     * Используется импортом существующих jar в модпак. 404 — файл неизвестен.
     */
    public RemoteVersionInfo versionBySha1(String sha1) throws Exception {
        if (sha1 == null || sha1.isBlank()) return null;
        JsonElement element;
        try {
            element = httpGetJson(baseUrl() + "/version_file/" + urlEncode(sha1)
                    + "?algorithm=sha1", headers());
        } catch (ApiException e) {
            if (e.getMessage() != null && e.getMessage().contains("404")) return null;
            throw e;
        }
        if (element == null || !element.isJsonObject()) return null;
        return parseHashVersion(element.getAsJsonObject(), sha1);
    }

    /**
     * Пакетный поиск версий по SHA-1 файлов (POST /v2/version_files) —
     * один запрос на весь модпак. Возвращает sha1 → версия; неизвестные
     * хэши в ответе отсутствуют.
     */
    public Map<String, RemoteVersionInfo> versionsBySha1(List<String> hashes) throws Exception {
        Map<String, RemoteVersionInfo> result = new LinkedHashMap<>();
        if (!available() || hashes == null || hashes.isEmpty()) return result;
        for (int from = 0; from < hashes.size(); from += 100) {
            List<String> chunk = hashes.subList(from, Math.min(from + 100, hashes.size()));
            JsonArray array = new JsonArray();
            chunk.forEach(array::add);
            String body = "{\"algorithm\":\"sha1\",\"hashes\":" + array + "}";
            JsonElement element = httpPostJson(baseUrl() + "/version_files", headers(), body);
            if (element == null || !element.isJsonObject()) continue;
            for (Map.Entry<String, com.google.gson.JsonElement> entry : element.getAsJsonObject().entrySet()) {
                if (entry.getValue() != null && entry.getValue().isJsonObject()) {
                    RemoteVersionInfo version = parseHashVersion(entry.getValue().getAsJsonObject(), entry.getKey());
                    if (version != null) result.put(entry.getKey(), version);
                }
            }
        }
        return result;
    }

    /** Разбор ответа /version_file|/version_files: версия + первичный файл. */
    private RemoteVersionInfo parseHashVersion(JsonObject object, String sha1) {
        RemoteVersionInfo version = new RemoteVersionInfo();
        version.source = id();
        version.projectId = optString(object, "project_id");
        version.versionId = optString(object, "id");
        version.versionNumber = optString(object, "version_number");
        JsonArray files = object.getAsJsonArray("files");
        if (files != null) {
            JsonObject file = null;
            for (JsonElement e : files) {
                if (!e.isJsonObject()) continue;
                file = e.getAsJsonObject();
                if (Boolean.parseBoolean(String.valueOf(optString(file, "primary")))) break;
            }
            if (file == null && !files.isEmpty() && files.get(0).isJsonObject()) {
                file = files.get(0).getAsJsonObject();
            }
            if (file != null) {
                version.fileName = optString(file, "filename");
                version.url = optString(file, "url");
            }
        }
        if (version.versionId == null || version.projectId == null) return null;
        return version;
    }

    @Override
    public List<RemoteVersionInfo> versions(String projectId, String gameVersion, String loader) throws Exception {
        StringBuilder url = new StringBuilder(baseUrl()).append("/project/")
                .append(urlEncode(projectId)).append("/version");
        boolean hasQuery = false;
        if (gameVersion != null && !gameVersion.isBlank()) {
            url.append("?game_versions=").append(queryEncode(stringArray(List.of(gameVersion)).toString()));
            hasQuery = true;
        }
        if (loader != null && !loader.isBlank() && !"any".equalsIgnoreCase(loader)) {
            url.append(hasQuery ? "&" : "?").append("loaders=")
                    .append(queryEncode(stringArray(List.of(loader)).toString()));
        }
        List<RemoteVersionInfo> result = new ArrayList<>();
        for (JsonElement element : httpGetJson(url.toString(), headers()).getAsJsonArray()) {
            JsonObject object = element.getAsJsonObject();
            RemoteVersionInfo version = new RemoteVersionInfo();
            version.source = id();
            version.projectId = projectId;
            version.versionId = optString(object, "id");
            version.versionNumber = optString(object, "version_number");
            version.releaseType = "release".equals(optString(object, "version_type"))
                    ? "release" : optString(object, "version_type");
            version.datePublished = 0;
            String date = optString(object, "date_published");
            if (date != null) {
                try {
                    version.datePublished = java.time.Instant.parse(date).toEpochMilli();
                } catch (Exception ignored) {
                }
            }
            version.gameVersions = stringList(object.get("game_versions"));
            version.loaders = stringList(object.get("loaders"));
            JsonArray depArray = object.getAsJsonArray("dependencies");
            if (depArray != null) {
                for (JsonElement dep : depArray) {
                    JsonObject depObject = dep.getAsJsonObject();
                    String type = optString(depObject, "dependency_type");
                    String pid = optString(depObject, "project_id");
                    if (pid != null && type != null) {
                        version.dependencies.add(new RemoteVersionInfo.RemoteDependency(id(), pid, type));
                    }
                }
            }
            JsonArray files = object.getAsJsonArray("files");
            JsonObject primary = null;
            if (files != null) {
                for (JsonElement f : files) {
                    JsonObject file = f.getAsJsonObject();
                    if (file.has("primary") && !file.get("primary").isJsonNull()
                            && file.get("primary").getAsBoolean()) {
                        primary = file;
                        break;
                    }
                }
            }
            if (primary == null && files != null && files.size() > 0) {
                primary = files.get(0).getAsJsonObject();
            }
            if (primary != null) {
                version.fileName = optString(primary, "filename");
                version.url = optString(primary, "url");
                version.size = optLong(primary, "size", 0);
                JsonObject hashes = optObject(primary, "hashes");
                if (hashes != null) {
                    version.sha512 = optString(hashes, "sha512");
                    version.sha1 = optString(hashes, "sha1");
                }
            }
            if (version.url != null) {
                result.add(version);
            }
        }
        return result;
    }
}
