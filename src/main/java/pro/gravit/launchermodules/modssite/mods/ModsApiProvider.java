package pro.gravit.launchermodules.modssite.mods;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pro.gravit.launcher.base.Launcher;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Абстракция над API хранилищ модов (Modrinth / CurseForge):
 * HTTP, кэш ответов с TTL, единый формат результатов.
 */
public abstract class ModsApiProvider {
    protected static final Logger logger = LogManager.getLogger(ModsApiProvider.class);
    protected static final Gson GSON = Launcher.gsonManager.gson;

    protected final HttpClient httpClient;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    protected final long cacheTtlMs;

    protected record CacheEntry(JsonElement data, long storedAt) {
    }

    protected ModsApiProvider(long cacheTtlMs) {
        this.cacheTtlMs = cacheTtlMs;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public abstract String id();

    public abstract boolean available();

    /** Поиск модов (упрощённый вариант: без категорий/сортировки/пагинации). */
    public final SearchResult search(String query, String gameVersion, String loader, int limit) throws Exception {
        SearchRequest request = new SearchRequest();
        request.query = query == null ? "" : query;
        request.gameVersion = gameVersion;
        request.loader = loader;
        request.limit = limit;
        return search(request);
    }

    /** Поиск модов с фильтрами (категории, загрузчик, версия), сортировкой и пагинацией. */
    public abstract SearchResult search(SearchRequest request) throws Exception;

    /**
     * Категории проектов для фильтров поиска (Modrinth — из /tag/category,
     * CurseForge — из /v1/categories). Пустой список — источник недоступен.
     */
    public List<CategoryInfo> categories() throws Exception {
        return categories(Kinds.MOD);
    }

    /** Категории проектов КОНКРЕТНОГО типа (mod/resourcepack/shader). */
    public List<CategoryInfo> categories(String projectType) throws Exception {
        return List.of();
    }

    /** Информация о проекте. */
    public abstract ProjectInfo project(String projectId) throws Exception;

    /** Версии проекта (свежие сверху), отфильтрованные по игре/загрузчику. */
    public abstract List<RemoteVersionInfo> versions(String projectId, String gameVersion, String loader) throws Exception;

    /** Пакетное получение информации о проектах. */
    public abstract Map<String, ProjectInfo> projects(List<String> projectIds) throws Exception;

    /** Максимальный размер кэша ответов (защита от роста при произвольных поисковых запросах). */
    private static final int CACHE_MAX_ENTRIES = 256;

    /** Общий HTTP GET c кэшем. */
    protected JsonElement httpGetJson(String url, Map<String, String> headers) throws Exception {
        CacheEntry cached = cache.get(url);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.storedAt() < cacheTtlMs) {
            return cached.data();
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(new URI(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json");
        if (headers != null) {
            headers.forEach(builder::header);
        }
        HttpRequest request = builder.GET().build();
        JsonElement element = parseResponse(request, url);
        // успешные ответы кэшируются на cacheTtl (раньше кэш читался, но никогда
        // не заполнялся — каждый поиск бил в API, лишний расход лимита запросов)
        if (cache.size() >= CACHE_MAX_ENTRIES) {
            cache.clear();
        }
        cache.put(url, new CacheEntry(element, System.currentTimeMillis()));
        return element;
    }

    /**
     * HTTP POST c JSON-телом (эндпоинты поиска по хэшам: /version_files,
     * /v1/fingerprints). Ответы НЕ кэшируются: результат «файл не найден»
     * не должен залипать на cacheTtl минут — иначе временный сбой источника
     * выглядит как «мод навсегда локальный».
     */
    protected JsonElement httpPostJson(String url, Map<String, String> headers, String jsonBody) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(new URI(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "" : jsonBody,
                        java.nio.charset.StandardCharsets.UTF_8));
        if (headers != null) {
            headers.forEach(builder::header);
        }
        return parseResponse(builder.build(), url);
    }

    /** Мс паузы между повторами (после 1-й и 2-й неудачной попытки). */
    private static final long[] RETRY_BACKOFF_MS = {700, 1800};

    private JsonElement parseResponse(HttpRequest request, String url) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt <= RETRY_BACKOFF_MS.length; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(RETRY_BACKOFF_MS[attempt - 1]);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            HttpResponse<String> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                // Сетевые сбои: TLS-handshake («Remote host terminated the handshake»),
                // обрывы, таймауты — у хостингов встречаются пачками, повтор лечит
                last = e;
                logger.warn("{}: сетевая ошибка, попытка {}/{}: {}",
                        id(), attempt + 1, RETRY_BACKOFF_MS.length + 1, String.valueOf(e.getMessage()));
                continue;
            }
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return com.google.gson.JsonParser.parseString(response.body());
            }
            String message = id() + " API error " + status + " for " + maskUrl(url)
                    + errorHint(status, response.body());
            if (isRetryableStatus(status) && attempt < RETRY_BACKOFF_MS.length) {
                last = new ApiException(message);
                logger.warn("{}: HTTP {}, попытка {}/{} — повторяю",
                        id(), status, attempt + 1, RETRY_BACKOFF_MS.length + 1);
                continue;
            }
            throw new ApiException(message);
        }
        throw new ApiException(id() + " network error for " + maskUrl(url)
                + (last == null ? "" : " — " + String.valueOf(last.getMessage())), last);
    }

    /** Повторяемые статусы: троттлинг (429) и сбои шлюзов/Cloudflare (5xx). */
    private static boolean isRetryableStatus(int status) {
        return status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
    }

    protected static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Кодирование значения query-параметра: пробел — %20, а не «+»
     * (URLEncoder кодирует для application/x-www-form-urlencoded; часть API,
     * включая CurseForge searchFilter, ищет по «+» буквально — многословные
     * запросы дают «Найдено: 0»).
     */
    protected static String queryEncode(String s) {
        return urlEncode(s).replace("+", "%20");
    }

    private static String maskUrl(String url) {
        int query = url.indexOf('?');
        return query > 0 ? url.substring(0, Math.min(query, 60)) : url.substring(0, Math.min(url.length(), 60));
    }

    /**
     * Подсказка к неуспешному ответу источника: сниппет тела, а для 401/403 без тела —
     * понятное объяснение (у CurseForge, например, и отсутствие ключа, и неверный ключ
     * выглядят одинаково — голый 403 с пустым телом, без подсказки не разобраться).
     * Переопределяется провайдерами под их специфику.
     */
    protected String errorHint(int status, String body) {
        String snippet = body == null ? "" : body.strip();
        if (snippet.isEmpty()) return "";
        if (snippet.length() > 160) snippet = snippet.substring(0, 160) + "…";
        return " — " + snippet.replaceAll("\\s+", " ");
    }

    public void clearCache() {
        cache.clear();
    }

    public static class ApiException extends Exception {
        public ApiException(String message) {
            super(message);
        }

        public ApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Результат поиска. */
    public static class SearchHit {
        public String source;
        public String projectId;
        public String slug;
        public String title;
        public String description;
        public String iconUrl;
        public long downloads;
        /** Подписчики (Modrinth follows; CurseForge не отдаёт — 0). */
        public long follows;
        /** Последнее обновление проекта (epoch ms, 0 — неизвестно). */
        public long dateModified;
        /** До 3 категорий проекта (для карточек результатов). */
        public List<String> categories = new ArrayList<>();

        public SearchHit() {
        }
    }

    /** Страница результатов поиска: хиты + общее число (для пагинации). */
    public static class SearchResult {
        public List<SearchHit> hits = new ArrayList<>();
        public long total;
        /** Код предупреждения для интерфейса (например, «loaderRequiresVersion»), null — нет. */
        public String warning;

        public SearchResult() {
        }
    }

    /**
     * Параметры поиска как в Modrinth/Prism Launcher: текст, загрузчик,
     * версия игры, категории, сортировка, пагинация.
     */
    public static class SearchRequest {
        public String query = "";
        /** Тип контента: mod | resourcepack | shader (по умолчанию mod). */
        public String projectType = Kinds.MOD;
        /** Версия Minecraft (null — без фильтра). */
        public String gameVersion;
        /** fabric | forge | neoforge | quilt (null/any — без фильтра). */
        public String loader;
        /** Слаги категорий (ИЛИ внутри набора). */
        public List<String> categories = List.of();
        /** relevance | downloads | follows | newest | updated. */
        public String sort = "relevance";
        /** Смещение для постраничного просмотра. */
        public int offset;
        public int limit = 20;

        public SearchRequest() {
        }
    }

    /** Категория проекта (для фильтров). */
    public static class CategoryInfo {
        public String id;
        public String name;

        public CategoryInfo() {
        }

        public CategoryInfo(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /** «Технология» → красиво для UI. */
    protected static String prettify(String slug) {
        if (slug == null || slug.isBlank()) return slug;
        String s = slug.replace('-', ' ').replace('_', ' ').trim();
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Информация о проекте. */
    public static class ProjectInfo {
        public String source;
        public String projectId;
        public String slug;
        public String title;
        public String description;
        public String iconUrl;

        public ProjectInfo() {
        }
    }

    protected static List<String> stringList(JsonElement element) {
        List<String> result = new ArrayList<>();
        if (element == null || !element.isJsonArray()) return result;
        for (JsonElement e : element.getAsJsonArray()) {
            if (!e.isJsonNull()) result.add(e.getAsString());
        }
        return result;
    }

    protected static JsonArray stringArray(List<String> values) {
        JsonArray array = new JsonArray();
        if (values != null) values.forEach(array::add);
        return array;
    }

    protected static String optString(JsonObject object, String member) {
        if (object == null || !object.has(member) || object.get(member).isJsonNull()) return null;
        return object.get(member).getAsString();
    }

    protected static long optLong(JsonObject object, String member, long def) {
        if (object == null || !object.has(member) || object.get(member).isJsonNull()) return def;
        try {
            return object.get(member).getAsLong();
        } catch (Exception e) {
            return def;
        }
    }

    protected static JsonObject optObject(JsonObject object, String member) {
        if (object == null || !object.has(member) || object.get(member).isJsonNull()) return null;
        JsonElement element = object.get(member);
        return element.isJsonObject() ? element.getAsJsonObject() : null;
    }
}
