package pro.gravit.launchermodules.modssite.commands;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pro.gravit.launchermodules.modssite.ModsSiteConfig;
import pro.gravit.launchermodules.modssite.ModsSiteModule;
import pro.gravit.launchserver.LaunchServer;
import pro.gravit.launchserver.command.Command;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * «mods curseforge test» — живая проверка ключа CurseForge API.
 * <p>
 * CurseForge отвечает на отсутствие ключа и на неверный ключ одинаково:
 * голый 403 с пустым телом, поэтому в интерфейсе видно только
 * «curseforge API error 403», без причины. Команда отправляет реальный
 * запрос {@code GET /v1/games?gameId=432} с настроенным ключом и
 * расшифровывает результат с конкретными шагами исправления.
 * <p>
 * Примеры:
 * <pre>
 *   mods curseforge test
 *   mods curseforge categories
 * </pre>
 */
public class CurseForgeCommand extends Command {
    private final ModsSiteModule module;

    public CurseForgeCommand(LaunchServer server, ModsSiteModule module) {
        super(server);
        this.module = module;
        childCommands.put("test", new Test(server, module));
        childCommands.put("categories", new Categories(server, module));
        childCommands.put("search", new Search(server, module));
        childCommands.put("fingerprint", new Fingerprint(server, module));
    }

    @Override
    public String getArgsDescription() {
        return "[test [версия]] [categories] [search <запрос> [версия] [загрузчик]] [fingerprint <файл>...]";
    }

    @Override
    public String getUsageDescription() {
        return "CurseForge API diagnostics (key check, categories dump, live search probe)";
    }

    @Override
    public void invoke(String... args) throws Exception {
        invokeSubcommands(args);
    }

    /** Сниппет тела ответа для лога (ошибка/диагностика). */
    static String snippet(String body) {
        String s = body == null ? "" : body.strip();
        if (s.isEmpty()) return " (пустое тело ответа)";
        if (s.length() > 200) s = s.substring(0, 200) + "…";
        return ": " + s.replaceAll("\\s+", " ");
    }

    /** Подкоманда «test»: запрос к CurseForge с текущим ключом из конфига.
     *  Необязательный аргумент — версия игры для пробы поиска (по умолчанию 1.21.1). */
    public static class Test extends Command {
        private static final Logger logger = LogManager.getLogger(Test.class);
        private final ModsSiteModule module;

        public Test(LaunchServer server, ModsSiteModule module) {
            super(server);
            this.module = module;
        }

        @Override
        public String getArgsDescription() {
            return "[gameVersion]";
        }

        @Override
        public String getUsageDescription() {
            return "Test CurseForge API key from config (mods.curseforge.apiKey)";
        }

        @Override
        public void invoke(String... args) {
            ModsSiteConfig.CurseForgeConfig cf = module.config == null ? null : module.config.mods.curseforge;
            if (cf == null) {
                logger.info("Конфиг модуля не загружен");
                return;
            }
            String gameVersion = args.length > 0 && !args[0].isBlank() ? args[0].trim() : "1.21.1";
            String baseUrl = cf.baseUrl == null || cf.baseUrl.isBlank()
                    ? "https://api.curseforge.com" : cf.baseUrl.endsWith("/")
                    ? cf.baseUrl.substring(0, cf.baseUrl.length() - 1) : cf.baseUrl;
            if (!cf.enabled) {
                logger.info("CurseForge выключен в конфиге (mods.curseforge.enabled=false) — включите его, если нужен CurseForge");
            }
            if (cf.apiKey == null || cf.apiKey.isBlank()) {
                logger.info("apiKey НЕ задан: укажите mods.curseforge.apiKey в config/ModsSite/Config.json");
                logger.info("Ключ выдаётся на https://console.curseforge.com (подробнее — README, раздел «CurseForge: API error 403»)");
                return;
            }
            logger.info("Проверяю ключ {} против {} ...", mask(cf.apiKey), baseUrl);
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/games?gameId=432"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json")
                    .header("x-api-key", cf.apiKey)
                    .GET().build();
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status / 100 == 2) {
                    logger.info("OK (HTTP {}): CurseForge принял ключ — поиск и категории будут работать", status);
                    searchProbe(logger, client, baseUrl, cf.apiKey, gameVersion);
                } else if (status == 401 || status == 403) {
                    logger.info("ОТКАЗ (HTTP {}): CurseForge отклонил ключ. Что проверить:", status);
                    logger.info("  1) В config/ModsSite/Config.json -> mods.curseforge.apiKey скопирован ВЕСЬ ключ, без пробелов и кавычек (с 1.0.8 случайные пробелы/кавычки обрезаются автоматически).");
                    logger.info("  2) Статус API-клиента на https://console.curseforge.com: если ключ свежий и не работает — перевыпустите его (известны случаи, когда ключ начинал работать только после перевыпуска).");
                    logger.info("  3) После правки конфига выполните: mods reload (или перезапустите LaunchServer), затем снова: mods curseforge test");
                } else {
                    logger.info("HTTP {}: неожиданный ответ{}", status, snippet(response.body()));
                }
            } catch (HttpTimeoutException e) {
                logger.info("Таймаут: {} не ответил за 30 с — проверьте доступность хоста с машины LaunchServer", baseUrl);
            } catch (IOException e) {
                logger.info("Сетевая ошибка: {} — проверьте доступность https://api.curseforge.com с машины LaunchServer", e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /** Маска ключа для лога: первые/последние 4 символа + длина. */
        private static String mask(String key) {
            String s = key.strip();
            if (s.length() <= 8) return "***(" + s.length() + " симв.)";
            return s.substring(0, 4) + "***" + s.substring(s.length() - 4) + " (" + s.length() + " симв.)";
        }

        /**
         * Проба поиска с фильтром загрузчика: по спецификации CurseForge
         * modLoaderType работает только вместе с gameVersion — без версии
         * любой загрузчик даёт ровно 0 результатов (выглядит как «Найдено: 0»
         * в веб-интерфейсе). Проба показывает это на живом API, включая
         * версию игры и загрузчик вашего профиля (аргумент команды).
         */
        private static void searchProbe(org.apache.logging.log4j.Logger logger, HttpClient client,
                                        String baseUrl, String apiKey, String gameVersion) {
            String base = baseUrl + "/v1/mods/search?gameId=432&classId=6&pageSize=1&searchFilter=";
            String[][] cases = {
                    {"Forge, БЕЗ версии игры (так было до 1.0.10)", "&modLoaderType=1"},
                    {"Forge + версия " + gameVersion, "&modLoaderType=1&gameVersion=" + gameVersion},
                    {"Fabric + версия " + gameVersion, "&modLoaderType=4&gameVersion=" + gameVersion},
                    {"NeoForge + версия " + gameVersion, "&modLoaderType=6&gameVersion=" + gameVersion},
                    {"Без загрузчика + версия " + gameVersion, "&gameVersion=" + gameVersion},
            };
            logger.info("Проба поиска (влияние фильтра загрузчика; своя версия — mods curseforge test <версия>):");
            for (int i = 0; i < cases.length; i++) {
                HttpRequest probe = HttpRequest.newBuilder(URI.create(base + cases[i][1]))
                        .timeout(Duration.ofSeconds(30))
                        .header("Accept", "application/json")
                        .header("x-api-key", apiKey)
                        .GET().build();
                try {
                    HttpResponse<String> response = client.send(probe, HttpResponse.BodyHandlers.ofString());
                    long total = -1;
                    if (response.statusCode() / 100 == 2) {
                        try {
                            com.google.gson.JsonObject parsed = com.google.gson.JsonParser
                                    .parseString(response.body()).getAsJsonObject();
                            if (parsed.has("pagination")) {
                                com.google.gson.JsonObject pagination = parsed.getAsJsonObject("pagination");
                                // Спека CurseForge: totalCount (total в ответе нет)
                                if (pagination.has("totalCount") && !pagination.get("totalCount").isJsonNull()) {
                                    total = pagination.get("totalCount").getAsLong();
                                } else if (pagination.has("total") && !pagination.get("total").isJsonNull()) {
                                    total = pagination.get("total").getAsLong();
                                }
                            }
                        } catch (RuntimeException ignored) {
                        }
                        logger.info("  [{}] {} -> HTTP {}, всего: {}", i + 1, cases[i][0], response.statusCode(),
                                total < 0 ? "?" : total);
                    } else {
                        logger.info("  [{}] {} -> HTTP {}{}", i + 1, cases[i][0], response.statusCode(),
                                snippet(response.body()));
                    }
                } catch (IOException e) {
                    logger.info("  [{}] {} -> сетевая ошибка: {}", i + 1, cases[i][0], e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            logger.info("Если в первой строке «всего: 0», а во второй больше нуля — это подтверждение: фильтр загрузчика CurseForge работает только с версией игры. Модуль учитывает это автоматически (без версии фильтр не отправляется; если с фильтром 0 — повторяет без него и предупреждает в интерфейсе).");
        }
    }

    /**
     * Подкоманда «fingerprint»: вычислить murmur2-отпечатки указанных jar
     * (тот же алгоритм, что использует импорт) и спросить CurseForge,
     * знает ли он такие файлы. Диагностика сценария «файл точно есть на
     * CurseForge, но импорт пометил его локальным»: если API не находит
     * отпечаток — автор выложил на CurseForge ДРУГОЙ билд (пересобранный
     * jar), и сопоставить файл автоматически можно только поиском
     * (1.0.13+ делает это при «Проверить обновления»/импорте).
     */
    public static class Fingerprint extends Command {
        private static final Logger logger = LogManager.getLogger(Fingerprint.class);
        private final ModsSiteModule module;

        public Fingerprint(LaunchServer server, ModsSiteModule module) {
            super(server);
            this.module = module;
        }

        @Override
        public String getArgsDescription() {
            return "<файл или папка>...";
        }

        @Override
        public String getUsageDescription() {
            return "Compute murmur2 fingerprints of mod jars and check them against CurseForge";
        }

        @Override
        public void invoke(String... args) throws Exception {
            pro.gravit.launchermodules.modssite.mods.CurseForgeApiProvider provider =
                    module.curseforgeProvider;
            if (provider == null || !provider.available()) {
                logger.info("CurseForge недоступен: укажите mods.curseforge.apiKey (mods curseforge test — проверка ключа)");
                return;
            }
            if (args.length == 0) {
                logger.info("Формат: mods curseforge fingerprint <путь-к-jar>...");
                logger.info("Пути — от папки LaunchServer или абсолютные. Папка — все .jar из неё.");
                return;
            }
            List<java.nio.file.Path> files = new ArrayList<>();
            for (String arg : args) {
                java.nio.file.Path path = java.nio.file.Path.of(arg);
                if (!path.isAbsolute() && server != null) {
                    path = server.dir.resolve(arg);
                }
                if (java.nio.file.Files.isDirectory(path)) {
                    try (var stream = java.nio.file.Files.list(path)) {
                        stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jar"))
                                .sorted().forEach(files::add);
                    }
                } else {
                    files.add(path);
                }
            }
            if (files.isEmpty()) {
                logger.info("Файлы не найдены");
                return;
            }
            List<Long> fingerprints = new ArrayList<>();
            for (java.nio.file.Path file : files) {
                long fingerprint = pro.gravit.launchermodules.modssite.mods.CurseForgeFingerprint.of(file);
                fingerprints.add(fingerprint);
                logger.info("{}: отпечаток {}", file.getFileName(), fingerprint);
            }
            logger.info("Спрашиваю CurseForge (/v1/fingerprints/432), файлов: {} ...", files.size());
            java.util.Map<Long, pro.gravit.launchermodules.modssite.mods.CurseForgeApiProvider.FingerprintMatch> matches;
            try {
                matches = provider.fingerprintMatches(fingerprints);
            } catch (Exception e) {
                logger.warn("Запрос не удался: {}", String.valueOf(e.getMessage()));
                return;
            }
            int matched = 0;
            for (int i = 0; i < files.size(); i++) {
                pro.gravit.launchermodules.modssite.mods.CurseForgeApiProvider.FingerprintMatch match =
                        matches.get(fingerprints.get(i));
                if (match == null || match.modId() <= 0) {
                    logger.info("  {} — CurseForge НЕ ЗНАЕТ этот файл (нет точного совпадения отпечатка)",
                            files.get(i).getFileName());
                } else {
                    matched++;
                    logger.info("  {} — modId={}, файл {} ({}), версия '{}'",
                            files.get(i).getFileName(), match.modId(), match.fileId(),
                            match.fileName() == null ? "?" : match.fileName(),
                            match.displayName() == null ? "?" : match.displayName());
                }
            }
            logger.info("Итого: совпадений {}/{}", matched, files.size());
            if (matched < files.size()) {
                logger.info("Отпечаток не найден — не значит, что мода нет на CurseForge: автор мог");
                logger.info("выложить туда другой билд того же мода (пересобранный jar, хэш другой).");
                logger.info("С 1.0.13 такие моды опознаются запасным поиском (по modId из jar с");
                logger.info("проверкой файлов версий) — при импорте и «Проверить обновления».");
            }
        }
    }

    /**
     * Подкоманда «search»: живой поиск CurseForge ТЕМ ЖЕ кодом, что и сайт
     * (provider.search с фильтрами) — видно реальный итог с вашим ключом
     * и параметрами: сколько найдено, warning'и (связка загрузчик+версия,
     * фоллбек фильтра) и первые результаты.
     */
    public static class Search extends Command {
        private static final Logger logger = LogManager.getLogger(Search.class);
        private final ModsSiteModule module;

        public Search(LaunchServer server, ModsSiteModule module) {
            super(server);
            this.module = module;
        }

        @Override
        public String getArgsDescription() {
            return "<запрос> [версия] [загрузчик]";
        }

        @Override
        public String getUsageDescription() {
            return "Live CurseForge search via module code path (same as web UI)";
        }

        @Override
        public void invoke(String... args) {
            ModsSiteConfig.CurseForgeConfig cf = module.config == null ? null : module.config.mods.curseforge;
            if (cf == null || cf.apiKey == null || cf.apiKey.isBlank()) {
                logger.info("apiKey НЕ задан (mods.curseforge.apiKey) — поиск CurseForge недоступен");
                return;
            }
            if (args.length == 0 || args[0].isBlank()) {
                logger.info("Формат: mods curseforge search <запрос> [версия] [загрузчик]");
                logger.info("Примеры: mods curseforge search jei 1.21.1 neoforge");
                logger.info("          mods curseforge search create");
                return;
            }
            String query = args[0];
            String gameVersion = args.length > 1 ? args[1] : null;
            String loader = args.length > 2 ? args[2].toLowerCase() : null;
            pro.gravit.launchermodules.modssite.mods.CurseForgeApiProvider provider =
                    module.curseforgeProvider;
            if (provider == null || !provider.available()) {
                logger.info("CurseForge недоступен (нет apiKey или выключен)");
                return;
            }
            pro.gravit.launchermodules.modssite.mods.ModsApiProvider.SearchRequest request =
                    new pro.gravit.launchermodules.modssite.mods.ModsApiProvider.SearchRequest();
            request.query = query;
            request.gameVersion = gameVersion;
            request.loader = loader;
            request.sort = "relevance";
            request.limit = 10;
            logger.info("Поиск CurseForge: запрос='{}', версия={}, загрузчик={}",
                    query, gameVersion == null ? "—" : gameVersion, loader == null ? "—" : loader);
            try {
                pro.gravit.launchermodules.modssite.mods.ModsApiProvider.SearchResult result =
                        provider.search(request);
                logger.info("Найдено всего: {}, на странице: {}", result.total, result.hits.size());
                if (result.warning != null) {
                    logger.info("Предупреждение: {}", result.warning);
                    if (result.warning.contains("loaderRequiresVersion")) {
                        logger.info("  (загрузчик без версии игры не учитывается — спецификация CurseForge)");
                    } else if (result.warning.contains("loaderFilterEmpty")) {
                        logger.info("  (с фильтром загрузчика 0 результатов — показаны результаты без фильтра)");
                    }
                }
                int i = 1;
                for (pro.gravit.launchermodules.modssite.mods.ModsApiProvider.SearchHit hit : result.hits) {
                    logger.info("  {}. {} [{}] (id={}, загрузок={})", i++, hit.title, hit.slug,
                            hit.projectId, hit.downloads);
                }
                if (result.hits.isEmpty() && result.total == 0) {
                    logger.info("Ноль результатов. Что попробовать: mods curseforge test (проверка ключа и проба фильтров),");
                    logger.info("другую версию игры/загрузчик, односоставной запрос — или поиск на curseforge.com для сравнения.");
                }
            } catch (Exception e) {
                logger.info("Ошибка поиска: {}", String.valueOf(e.getMessage()));
            }
        }
    }

    /**
     * Подкоманда «categories»: живой дамп /v1/categories?gameId=432&classId=6 —
     * id, slug, имя, признак класса-раздела, родитель и displayIndex.
     * Нужна для проверки, какие категории CurseForge реально отдаёт: именно
     * из этого списка собираются фильтры поиска в веб-интерфейсе.
     */
    public static class Categories extends Command {
        private static final Logger logger = LogManager.getLogger(Categories.class);
        private final ModsSiteModule module;

        public Categories(LaunchServer server, ModsSiteModule module) {
            super(server);
            this.module = module;
        }

        @Override
        public String getArgsDescription() {
            return "[]";
        }

        @Override
        public String getUsageDescription() {
            return "Dump CurseForge mod categories (raw /v1/categories)";
        }

        @Override
        public void invoke(String... args) {
            ModsSiteConfig.CurseForgeConfig cf = module.config == null ? null : module.config.mods.curseforge;
            if (cf == null) {
                logger.info("Конфиг модуля не загружен");
                return;
            }
            if (cf.apiKey == null || cf.apiKey.isBlank()) {
                logger.info("apiKey НЕ задан — категории CurseForge недоступны (mods.curseforge.apiKey)");
                return;
            }
            String baseUrl = cf.baseUrl == null || cf.baseUrl.isBlank()
                    ? "https://api.curseforge.com" : cf.baseUrl.endsWith("/")
                    ? cf.baseUrl.substring(0, cf.baseUrl.length() - 1) : cf.baseUrl;
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/categories?gameId=432&classId=6"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json")
                    .header("x-api-key", cf.apiKey)
                    .GET().build();
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 != 2) {
                    logger.info("HTTP {}: категории получить не удалось{}", response.statusCode(), snippet(response.body()));
                    return;
                }
                com.google.gson.JsonElement parsed = com.google.gson.JsonParser.parseString(response.body());
                com.google.gson.JsonArray array;
                if (parsed.isJsonObject() && parsed.getAsJsonObject().has("data")) {
                    array = parsed.getAsJsonObject().getAsJsonArray("data");
                } else if (parsed.isJsonArray()) {
                    array = parsed.getAsJsonArray();
                } else {
                    logger.info("Неожиданный формат ответа: {}", snippet(response.body()));
                    return;
                }
                logger.info("Категории модов CurseForge ({} шт.):", array.size());
                logger.info("{}", String.format("  %7s  %-28s %-26s %-6s %7s  %s",
                        "id", "slug", "name", "class", "parent", "index"));
                int classes = 0;
                for (com.google.gson.JsonElement e : array) {
                    if (!e.isJsonObject()) continue;
                    com.google.gson.JsonObject o = e.getAsJsonObject();
                    long id = o.has("id") && !o.get("id").isJsonNull() ? o.get("id").getAsLong() : 0;
                    String slug = o.has("slug") && !o.get("slug").isJsonNull() ? o.get("slug").getAsString() : "?";
                    String name = o.has("name") && !o.get("name").isJsonNull() ? o.get("name").getAsString() : "?";
                    boolean isClass = o.has("isClass") && !o.get("isClass").isJsonNull() && o.get("isClass").getAsBoolean();
                    long parent = o.has("parentCategoryId") && !o.get("parentCategoryId").isJsonNull()
                            ? o.get("parentCategoryId").getAsLong() : 0;
                    long index = o.has("displayIndex") && !o.get("displayIndex").isJsonNull()
                            ? o.get("displayIndex").getAsLong() : -1;
                    if (isClass) classes++;
                    logger.info("{}", String.format("  %7d  %-28s %-26s %-6s %7s  %d",
                            id, slug, name, isClass ? "CLASS" : "cat", parent == 0 ? "-" : String.valueOf(parent), index));
                }
                logger.info("Итого: {} шт., из них классов-разделов (isClass=true): {}", array.size(), classes);
                logger.info("В фильтрах сайта показываются: {}", classes > 0
                        ? "классы-разделы (фильтр по ним включает подкатегории)"
                        : "категории напрямую (плоский список)");
            } catch (HttpTimeoutException e) {
                logger.info("Таймаут: {} не ответил за 30 с", baseUrl);
            } catch (IOException e) {
                logger.info("Сетевая ошибка: {}", e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
