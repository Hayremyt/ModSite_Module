package pro.gravit.launchermodules.modssite;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Конфигурация модуля ModsSite (config/ModsSite/Config.json).
 * Всё, что можно, отключаемо/настраиваемо.
 */
public class ModsSiteConfig {

    /** Мастер-выключатель модуля. */
    public boolean enabled = true;

    // ==================== ВЕБ ====================

    /** Путь монтирования сайта и API. */
    public WebConfig web = new WebConfig();

    // ==================== АВТОРИЗАЦИЯ ====================

    /**
     * Режим авторизации:
     * "hybrid"   — локальные аккаунты из accounts + аккаунты лаунчера (по умолчанию);
     * "local"    — только локальные аккаунты из конфига;
     * "launcher" — только аккаунты лаунчера (проверка через auth-провайдера).
     */
    public AuthConfig auth = new AuthConfig();

    /**
     * Настройки проверки аккаунтов лаунчера (для режимов hybrid/launcher):
     * признак админа берётся из прав аккаунта, либо из вашего SQL-запроса
     * (например, столбца isAdmin в БД авторизации).
     */
    public LauncherAuthConfig launcherAuth = new LauncherAuthConfig();

    /** Локальные аккаунты сайта. Пароль можно задать полем password (plain) —
     *  при первом старте он будет автоматически заменён на PBKDF2-хэш. */
    public List<AccountConfig> accounts = new ArrayList<>();

    // ==================== БЕЗОПАСНОСТЬ ====================

    /** Защита от подбора пароля: баны IP с нарастающим сроком, whitelist/blacklist. */
    public SecurityConfig security = new SecurityConfig();

    /** Ограничение частоты запросов (rate limit) по IP. */
    public RateLimitConfig rateLimit = new RateLimitConfig();

    /** Журнал аудита (JSONL). */
    public AuditConfig audit = new AuditConfig();

    /** Уровень вывода логов модуля в консоль сервера (off/error/warn/all). */
    public LoggingConfig logging = new LoggingConfig();

    /** Темы оформления сайта. */
    public ThemesConfig themes = new ThemesConfig();

    /** Сколько «старых дефолтов» защиты смягчено последним normalize()
     *  (для одного лога при старте; в конфиг не пишется). */
    public transient int relaxedDefaults = 0;

    // ==================== МОДЫ ====================

    /** Настройки источников модов и работы с модпаками. */
    public ModsConfig mods = new ModsConfig();

    /** IP, которые никогда не блокируются (дублируется/дополняется командой mods whitelist). */
    public List<String> whitelist = new ArrayList<>();

    /** IP, которые всегда отклоняются (команда mods blacklist). */
    public List<String> blacklist = new ArrayList<>();

    /** Заполнить отсутствующие секции значениями по умолчанию (после загрузки конфига). */
    public void normalize() {
        if (web == null) web = new WebConfig();
        web.siteDomains = sanitizeSiteDomains(web.siteDomains);
        if (auth == null) auth = new AuthConfig();
        if (launcherAuth == null) launcherAuth = new LauncherAuthConfig();
        if (launcherAuth.adminCheck == null) launcherAuth.adminCheck = new LauncherAdminCheck();
        if (launcherAuth.adminCheck.sql == null) launcherAuth.adminCheck.sql = new SqlAdminCheck();
        if (accounts == null) accounts = new ArrayList<>();
        if (security == null) security = new SecurityConfig();
        if (security.accountLock == null) security.accountLock = new AccountLockConfig();
        if (rateLimit == null) rateLimit = new RateLimitConfig();
        if (audit == null) audit = new AuditConfig();
        if (logging == null) logging = new LoggingConfig();
        logging.consoleLevel = ModsSiteLogLevels.normalize(logging.consoleLevel);
        normalizeThemes();
        if (mods == null) mods = new ModsConfig();
        if (mods.modrinth == null) mods.modrinth = new ModrinthConfig();
        if (mods.curseforge == null) mods.curseforge = new CurseForgeConfig();
        if (mods.modsIgnore == null) mods.modsIgnore = new ArrayList<>(List.of(".index"));
        if (mods.resourcepacksIgnore == null) mods.resourcepacksIgnore = new ArrayList<>();
        if (mods.shadersIgnore == null) mods.shadersIgnore = new ArrayList<>();
        if (mods.suggestions == null) mods.suggestions = new SuggestionsConfig();
        if (mods.suggestions.bannedUsers == null) mods.suggestions.bannedUsers = new ArrayList<>();
        // тип поиска по умолчанию — только осмысленные значения (защита от ручной правки)
        mods.defaultProjectType = pro.gravit.launchermodules.modssite.mods.Kinds.normalize(mods.defaultProjectType);
        mods.suggestions.maxPerUser = Math.max(0, Math.min(100, mods.suggestions.maxPerUser));
        if (mods.updateSourcePriority == null) mods.updateSourcePriority = new ArrayList<>();
        // санитизация приоритета: только modrinth/curseforge, без дублей,
        // пропущенные источники дописываются в конец (защита от ручной правки)
        {
            List<String> sanitized = new ArrayList<>();
            for (String raw : mods.updateSourcePriority) {
                String id = raw == null ? "" : raw.trim().toLowerCase();
                if (("modrinth".equals(id) || "curseforge".equals(id)) && !sanitized.contains(id)) {
                    sanitized.add(id);
                }
            }
            for (String id : List.of("modrinth", "curseforge")) {
                if (!sanitized.contains(id)) sanitized.add(id);
            }
            mods.updateSourcePriority = sanitized;
        }
        // «грязный» ключ (пробелы/кавычки при вставке) CurseForge молча отвечает 403 —
        // чистим заранее
        if (mods.curseforge.apiKey != null) {
            mods.curseforge.apiKey = stripKeyNoise(mods.curseforge.apiKey);
        }
        if (whitelist == null) whitelist = new ArrayList<>();
        if (blacklist == null) blacklist = new ArrayList<>();
        if (security.banScheduleMinutes == null || security.banScheduleMinutes.isEmpty()) {
            security.banScheduleMinutes = new ArrayList<>(Arrays.asList(5, 15, 60, 360, 1440));
        }
        if (security.accountLock.lockScheduleMinutes == null || security.accountLock.lockScheduleMinutes.isEmpty()) {
            security.accountLock.lockScheduleMinutes = new ArrayList<>(Arrays.asList(10, 30, 240));
        }
        if (launcherAuth.adminCheck.sql.params == null || launcherAuth.adminCheck.sql.params.isEmpty()) {
            launcherAuth.adminCheck.sql.params = new ArrayList<>(List.of("{login}"));
        }
        if (launcherAuth.adminCheck.sql.trueValues == null || launcherAuth.adminCheck.sql.trueValues.isEmpty()) {
            launcherAuth.adminCheck.sql.trueValues = new ArrayList<>(Arrays.asList("1", "true", "t", "yes", "y", "admin"));
        }
        if (launcherAuth.extraTotp == null) launcherAuth.extraTotp = new HashMap<>();
        relaxSecurityDefaults();
    }

    /** Убрать у ключа/токена случайные пробелы и обёртку из кавычек (после ручного редактирования конфига). */
    private static String stripKeyNoise(String value) {
        String s = value.strip();
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\""))
                || (s.startsWith("'") && s.endsWith("'")))) {
            s = s.substring(1, s.length() - 1).strip();
        }
        return s;
    }

    // =====================================================================
    //  ДОМЕНЫ САЙТА (root-режим: ip/mods/ и mods.example.com одновременно)
    // =====================================================================

    /** Максимум доменов в siteDomains (защита от раздувания конфига). */
    public static final int MAX_SITE_DOMAINS = 16;

    /**
     * Санитизация списка доменов сайта: trim, нижний регистр, без порта,
     * без завершающей точки, без пустых и дублей. null — пустой список.
     */
    public static List<String> sanitizeSiteDomains(List<String> raw) {
        if (raw == null) return new ArrayList<>();
        List<String> result = new ArrayList<>();
        for (String item : raw) {
            String domain = normalizeDomain(item);
            if (!domain.isEmpty() && !result.contains(domain)) {
                result.add(domain);
                if (result.size() >= MAX_SITE_DOMAINS) break;
            }
        }
        return result;
    }

    /** Нормализовать один домен: trim, нижний регистр, без портa и завершающей точки. */
    public static String normalizeDomain(String raw) {
        if (raw == null) return "";
        String s = stripKeyNoise(raw).toLowerCase();
        if (s.isEmpty()) return "";
        int colon = s.lastIndexOf(':');
        if (colon > 0 && !s.contains("]")) {
            s = s.substring(0, colon); // "mods.example.com:8080" → "mods.example.com"
        }
        while (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.length() <= 253 ? s : "";
    }

    /**
     * Совпадает ли Host-заголовок запроса с одним из доменов сайта.
     * Порт в заголовке не учитывается ("mods.example.com:9274" → "mods.example.com"),
     * IPv6-литералы в скобках поддерживаются. Пустой список — совпадений нет.
     */
    public static boolean siteDomainMatches(List<String> domains, String hostHeader) {
        if (domains == null || domains.isEmpty() || hostHeader == null || hostHeader.isBlank()) {
            return false;
        }
        String host = hostHeader.trim().toLowerCase();
        if (host.startsWith("[") && host.contains("]")) {
            host = host.substring(1, host.indexOf(']')); // "[::1]:9274" → "::1"
        } else {
            int colon = host.lastIndexOf(':');
            if (colon > 0) host = host.substring(0, colon);
        }
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        for (String domain : domains) {
            if (domain != null && !domain.isBlank() && domain.equals(host)) return true;
        }
        return false;
    }

    // =====================================================================
    //  ТЕМЫ ОФОРМЛЕНИЯ САЙТА
    // =====================================================================

    /** Встроенные темы (порядок = порядок в меню). */
    public static final List<String> BUILTIN_THEME_IDS =
            List.of("glass", "comic", "simple", "neon", "pixel", "terminal");

    /** Максимум кастомных тем (защита от раздувания конфига). */
    public static final int MAX_CUSTOM_THEMES = 16;

    /** Базовые ключи цветов кастомной темы (для каждого варианта light/dark). */
    public static final List<String> CUSTOM_THEME_TOKENS =
            List.of("bg", "card", "text", "textMuted", "border", "accent", "accent2");

    /** Максимум длины имени кастомной темы. */
    public static final int MAX_THEME_NAME = 40;

    /** Валидный hex-цвет (#rgb или #rrggbb)? */
    public static boolean isHexColor(String value) {
        if (value == null) return false;
        return value.matches("#[0-9a-fA-F]{6}") || value.matches("#[0-9a-fA-F]{3}");
    }

    /** Slug для id кастомной темы: латиница/цифры/дефис, до 24 символов. */
    public static String themeSlug(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
               .replaceAll("(^-+|-+$)", "");
        if (s.length() > 24) s = s.substring(0, 24);
        return s.replaceAll("(^-+|-+$)", "");
    }

    /** Допустимый шрифт кастомной темы. */
    public static String normalizeThemeFont(String font) {
        return "mono".equals(font) ? "mono" : "sans";
    }

    /** Допустимая скруглённость углов кастомной темы. */
    public static String normalizeThemeRadius(String radius) {
        if ("sharp".equals(radius)) return "sharp";
        if ("round".equals(radius)) return "round";
        return "soft";
    }

    /** Цвета одного варианта темы: только известные ключи, только hex-значения.
     *  Недостающие ключи берутся из fallback. Возвращает null, если после
     *  очистки нет ни одного валидного цвета. */
    public static Map<String, String> sanitizeThemeColors(Map<String, String> raw, Map<String, String> fallback) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : CUSTOM_THEME_TOKENS) {
            String value = raw == null ? null : raw.get(key);
            if (value != null && isHexColor(value)) {
                result.put(key, value.toLowerCase(java.util.Locale.ROOT));
            } else if (fallback != null && fallback.get(key) != null) {
                result.put(key, fallback.get(key));
            }
        }
        return result.size() == CUSTOM_THEME_TOKENS.size() ? result : null;
    }

    /** Санитизация списка кастомных тем: валидация полей, уникальные id
     *  (не пересекающиеся со встроенными), лимит количества. Невалидные
     *  записи отбрасываются. */
    public static List<CustomTheme> sanitizeCustomThemes(List<CustomTheme> raw) {
        List<CustomTheme> result = new ArrayList<>();
        if (raw == null) return result;
        for (CustomTheme theme : raw) {
            if (theme == null || theme.name == null) continue;
            String name = theme.name.trim();
            if (name.isEmpty() || name.length() > MAX_THEME_NAME) continue;
            Map<String, String> light = sanitizeThemeColors(theme.light, null);
            Map<String, String> dark = sanitizeThemeColors(theme.dark, null);
            if (light == null || dark == null) continue;
            String slug = themeSlug(theme.id != null && !theme.id.isBlank() ? theme.id : name);
            if (slug.isEmpty()) slug = "theme";
            String id = slug;
            for (int i = 2; containsThemeId(result, id); i++) {
                id = slug + "-" + i;
            }
            CustomTheme clean = new CustomTheme();
            clean.id = id;
            clean.name = name;
            clean.font = normalizeThemeFont(theme.font);
            clean.radius = normalizeThemeRadius(theme.radius);
            clean.light = light;
            clean.dark = dark;
            result.add(clean);
            if (result.size() >= MAX_CUSTOM_THEMES) break;
        }
        return result;
    }

    /** Занят ли id (существующей кастомной темой или встроенной)? */
    private static boolean containsThemeId(List<CustomTheme> themes, String id) {
        if (BUILTIN_THEME_IDS.contains(id)) return true;
        for (CustomTheme theme : themes) {
            if (theme.id != null && theme.id.equals(id)) return true;
        }
        return false;
    }

    /** Доступна ли тема с таким id (встроенная не выключена ИЛИ кастомная)? */
    public static boolean themeAvailable(String id, ThemesConfig themes) {
        if (id == null || themes == null) return false;
        if (BUILTIN_THEME_IDS.contains(id)) {
            return themes.disabled == null || !themes.disabled.contains(id);
        }
        return themes.custom != null && themes.custom.stream().anyMatch(t -> id.equals(t.id));
    }

    /** Нормализация секции тем: вариант по умолчанию, список выключенных,
     *  кастомные темы и тема по умолчанию (должна существовать и быть
     *  доступной — иначе осмысленный запасной вариант). */
    private void normalizeThemes() {
        if (themes == null) themes = new ThemesConfig();
        themes.defaultMode = "light".equals(themes.defaultMode) ? "light" : "dark";
        // выключенные встроенные: только известные id, без дублей
        List<String> disabled = new ArrayList<>();
        if (themes.disabled != null) {
            for (String id : themes.disabled) {
                if (BUILTIN_THEME_IDS.contains(id) && !disabled.contains(id)) {
                    disabled.add(id);
                }
            }
        }
        themes.disabled = disabled;
        themes.custom = sanitizeCustomThemes(themes.custom);
        // тема по умолчанию обязана быть доступной: иначе первая включённая
        // встроенная, иначе первая кастомная, иначе принудительно glass
        if (themeAvailable(themes.defaultTheme, themes)) return;
        for (String id : BUILTIN_THEME_IDS) {
            if (!disabled.contains(id)) {
                themes.defaultTheme = id;
                return;
            }
        }
        if (!themes.custom.isEmpty()) {
            themes.defaultTheme = themes.custom.get(0).id;
            return;
        }
        // всё выключено и своих тем нет — включаем glass обратно,
        // иначе пользователям не из чего выбирать
        themes.disabled = new ArrayList<>();
        themes.defaultTheme = "glass";
    }

    /**
     * 1.0.5: прежние дефолты защиты (3 попытки до бана, эскалация до недели)
     * признаны слишком жёсткими для живого администратора. НОВЫЕ дефолты:
     * 10 попыток, баны 5/15/60/360/1440 мин, сброс уровня через 24 ч чистого
     * времени. Мигрируются ТОЛЬКО значения, равные старым дефолтам —
     * значения, заданные администратором осознанно, не трогаем.
     */
    private void relaxSecurityDefaults() {
        relaxedDefaults = 0;
        if (security.maxAttempts == 3) { security.maxAttempts = 10; relaxedDefaults++; }
        if (security.banScheduleMinutes.equals(List.of(10, 30, 120, 720, 1440, 10080))) {
            security.banScheduleMinutes = new ArrayList<>(List.of(5, 15, 60, 360, 1440));
            relaxedDefaults++;
        }
        if (security.levelDecayHours == 168) { security.levelDecayHours = 24; relaxedDefaults++; }
        if (security.accountLock.maxFails == 5) { security.accountLock.maxFails = 10; relaxedDefaults++; }
        if (security.accountLock.lockScheduleMinutes.equals(List.of(15, 60, 1440))) {
            security.accountLock.lockScheduleMinutes = new ArrayList<>(List.of(10, 30, 240));
            relaxedDefaults++;
        }
        if (security.accountLock.levelDecayHours == 168) { security.accountLock.levelDecayHours = 24; relaxedDefaults++; }
        if (rateLimit.loginPerMinute == 10) { rateLimit.loginPerMinute = 30; relaxedDefaults++; }
    }

    // =====================================================================
    //  ВЛОЖЕННЫЕ КЛАССЫ
    // =====================================================================

    public static class WebConfig {
        /** Отдавать ли веб-интерфейс (сайта). API продолжает работать. */
        public boolean uiEnabled = true;
        /** Включить REST API. Если false — модуль работает только через консоль. */
        public boolean apiEnabled = true;
        /** Заголовок сайта. */
        public String title = "Mods Manager";
        /** Язык интерфейса по умолчанию: "ru" или "en" (пользователь может переключить). */
        public String defaultLang = "ru";
        /**
         * Флаг Secure у cookie сессии:
         * "auto"   — ставится, если соединение пришло по HTTPS (SslHandler или доверенный X-Forwarded-Proto);
         * "always" — ставить всегда (сайт за HTTPS-прокси);
         * "never"  — не ставить.
         */
        public String cookieSecure = "auto";
        /** SameSite для cookie сессии: Lax / Strict / None (None требует Secure). */
        public String cookieSameSite = "Lax";
        /**
         * Доверять заголовкам X-Forwarded-For / X-Real-IP / X-Forwarded-Proto от обратного прокси:
         * "auto" — доверять, если в LaunchServer.json включён netty.ipForwarding;
         * true / false — принудительно.
         */
        public String trustProxyHeaders = "auto";
        /**
         * Необязательный URL для редиректа с HTTP на HTTPS, например "https://mods.example.com".
         * Работает только при доверии к заголовкам прокси (X-Forwarded-Proto=http).
         * Пустая строка — не редиректить.
         */
        public String httpsRedirectUrl = "";
        /** Дополнительная директива для Content-Security-Policy (например "img-src https:"). */
        public String cspExtra = "";
        /** Отправлять security-заголовки (CSP, X-Frame-Options, X-Content-Type-Options, Referrer-Policy). */
        public boolean securityHeaders = true;
        /**
         * Домены, на которые сайт отвечает В КОРНЕ ("/"), а не только в /mods:
         * укажите "mods.example.com" — и сайт откроется и как http://ip:порт/mods/,
         * так и по http://mods.example.com/ одновременно. Хост запроса сверяется
         * с заголовком Host (порт не учитывается). Пустой список — сайт доступен
         * только по пути /mods (настройка не влияет на file-сервер лаунчера:
         * запросы других хостов в корне передаются дальше без изменений).
         * Меняется в «Настройки» сайта или через mods reload — перезапуск не нужен.
         */
        public List<String> siteDomains = new ArrayList<>();
    }

    public static class AuthConfig {
        /** "hybrid" | "local" | "launcher". */
        public String mode = "hybrid";
        /** Имя cookie сессии. */
        public String cookieName = "mods_session";
        /** Время жизни сессии, минут. */
        public int sessionTtlMinutes = 720;
        /** Разрешать вход только администраторам (роль admin). Остальным — отказ. */
        public boolean onlyAdminsCanLogin = false;
        /**
         * Обязателен ли TOTP-код для аккаунтов, у которых задан totpSecret.
         * false — секрет хранится, но не спрашивается (не рекомендуется).
         */
        public boolean totpEnabled = true;
        /** Разрешать одному IP держать столько одновременных сессий (0 = без лимита). */
        public int maxSessionsPerIp = 0;
        /** Максимум длины логина/пароля (защита от огромных запросов). */
        public int maxFieldLength = 256;
    }

    public static class LauncherAuthConfig {
        /** ID AuthProviderPair из LaunchServer.json (пусто = пара по умолчанию). */
        public String authId = "";
        /** Как определять админа у аккаунта лаунчера. */
        public LauncherAdminCheck adminCheck = new LauncherAdminCheck();
        /** Чем становится не-админ: "viewer" (только просмотр) или "deny" (отказ во входе). */
        public String treatNonAdminAs = "viewer";
        /**
         * Дополнительные TOTP-секреты для аккаунтов лаунчера: login -> base32-секрет.
         * Позволяет требовать 2FA и для аккаунтов лаунчера.
         */
        public Map<String, String> extraTotp = new HashMap<>();
    }

    public static class LauncherAdminCheck {
        /**
         * "permissions" — по правам аккаунта лаунчера (perms/roles, см. permission/role ниже);
         * "sql"         — своим SQL-запросом к БД авторизации (см. sql);
         * "none"        — все аккаунты лаунчера считаются НЕ админами.
         */
        public String type = "permissions";
        /** Право (perm), дающее админа. Проверка hasPerm(permission). */
        public String permission = "mods.admin";
        /** Имя роли, дающей админа. Проверка hasRole(role). */
        public String role = "modsadmin";
        /** Настройки SQL-проверки. */
        public SqlAdminCheck sql = new SqlAdminCheck();
    }

    public static class SqlAdminCheck {
        /**
         * JDBC URL. Если пуст — модуль попытается взять настройки соединения
         * из первого SQL-совместимого auth-провайдера (mysql/postgresql/sql).
         */
        public String url = "";
        public String user = "";
        public String password = "";
        /**
         * Запрос. ? — параметры в порядке params. Доступные подстановки в params:
         * {login}, {uuid}. Пример:
         *   query  = "SELECT isAdmin FROM users WHERE username = ?"
         *   params = ["{login}"]
         * Первая колонка первой строки сравнивается с trueValues.
         */
        public String query = "SELECT isAdmin FROM users WHERE username = ?";
        public List<String> params = new ArrayList<>(List.of("{login}"));
        /** Значения, трактуемые как «админ». */
        public List<String> trueValues = new ArrayList<>(Arrays.asList("1", "true", "t", "yes", "y", "admin"));
    }

    public static class AccountConfig {
        public String login;
        /** Открытый пароль (используется один раз, затем заменяется на passwordHash). */
        public String password;
        /** PBKDF2-хэш: pbkdf2_sha256$итерации$соль$хэш. */
        public String passwordHash;
        /** "admin" | "viewer". */
        public String role = "viewer";
        /** Разрешён ли вход. */
        public boolean enabled = true;
        /** Base32 TOTP-секрет (генерируется командой mods user totp <login>). */
        public String totpSecret;

        public AccountConfig() {
        }

        public AccountConfig(String login, String password, String role) {
            this.login = login;
            this.password = password;
            this.role = role;
        }
    }

    public static class SecurityConfig {
        /** Включить защиту от подбора (баны IP). */
        public boolean enabled = true;
        /** Неудачных попыток входа до бана (в пределах windowMinutes). */
        public int maxAttempts = 10;
        /** Окно подсчёта неудачных попыток, минут. */
        public int windowMinutes = 10;
        /**
         * Нарастающие сроки бана, минут, по уровням повторного нарушения:
         * 1-е нарушение -> 5 мин, 2-е -> 15 мин, 3-е -> 1 ч и т.д.
         * Последний элемент используется для всех последующих нарушений.
         */
        public List<Integer> banScheduleMinutes = new ArrayList<>(Arrays.asList(5, 15, 60, 360, 1440));
        /**
         * Через сколько часов «чистого» времени уровень нарушений сбрасывается
         * (0 — никогда не сбрасывать).
         */
        public int levelDecayHours = 24;
        /** Бан по логину (независимо от IP). */
        public AccountLockConfig accountLock = new AccountLockConfig();
    }

    public static class AccountLockConfig {
        public boolean enabled = true;
        /** Неудачных попыток по логину до блокировки. */
        public int maxFails = 10;
        /** Окно подсчёта, минут. */
        public int windowMinutes = 10;
        /** Сроки блокировки логина по уровням, минут. */
        public List<Integer> lockScheduleMinutes = new ArrayList<>(Arrays.asList(10, 30, 240));
        public int levelDecayHours = 24;
    }

    public static class RateLimitConfig {
        public boolean enabled = true;
        /** Запросов/мин на IP ко всем API. */
        public int apiPerMinute = 240;
        /** Попыток входа/мин на IP. */
        public int loginPerMinute = 30;
    }

    public static class AuditConfig {
        public boolean enabled = true;
        /** Имя файла журнала (в папке конфига модуля). */
        public String file = "audit.log.jsonl";
        /** Размер файла для ротации, МБ. */
        public int maxFileMb = 5;
        /** Сколько старых файлов хранить. */
        public int keepFiles = 3;
    }

    public static class LoggingConfig {
        /**
         * Сколько писать в консоль сервера:
         * "off"   — ничего;
         * "error" — только ошибки;
         * "warn"  — ошибки и предупреждения (по умолчанию);
         * "all"   — весь вывод, включая info/debug.
         * Меняется в «Настройки -> Обновления» или в конфиге; применяется сразу.
         * Аудит-файл (audit.log.jsonl) от этого не зависит.
         */
        public String consoleLevel = "warn";
    }

    /** Настройки тем оформления сайта. */
    public static class ThemesConfig {
        /** Тема по умолчанию для всех, у кого нет своего выбора: id встроенной
         *  темы (glass/comic/simple/neon/pixel/terminal) или кастомной. */
        public String defaultTheme = "glass";
        /** Вариант по умолчанию: "light" или "dark". У каждой темы есть оба. */
        public String defaultMode = "dark";
        /** Выключенные встроенные темы (id) — скрыты у ВСЕХ: у игроков,
         *  наблюдателей и админов. Управляет админ в «Настройки -> Оформление». */
        public List<String> disabled = new ArrayList<>();
        /** Кастомные темы: добавляет и удаляет админ на сайте; выбирать из них
         *  могут все. У каждой — светлый и тёмный вариант. */
        public List<CustomTheme> custom = new ArrayList<>();
    }

    /** Кастомная тема: имя + базовые цвета светлого и тёмного варианта
     *  (остальные тоны интерфейса сайт выводит из них автоматически). */
    public static class CustomTheme {
        /** Уникальный id (латиница/цифры/дефис; не пересекается со встроенными). */
        public String id;
        /** Отображаемое имя (до 40 символов). */
        public String name;
        /** "sans" | "mono". */
        public String font = "sans";
        /** "sharp" (прямые) | "soft" (умеренные) | "round" (круглые). */
        public String radius = "soft";
        /** Цвета светлого варианта: bg, card, text, textMuted, border, accent, accent2 (#rrggbb). */
        public Map<String, String> light = new LinkedHashMap<>();
        /** Цвета тёмного варианта (те же ключи). */
        public Map<String, String> dark = new LinkedHashMap<>();
    }

    public static class ModsConfig {
        public ModrinthConfig modrinth = new ModrinthConfig();
        public CurseForgeConfig curseforge = new CurseForgeConfig();
        /**
         * Порядок источников при проверке обновлений: сначала первый, и если
         * мода на нём нет (нет проекта/версий/источник недоступен/запись
         * «локальная») — модуль ищет установленный файл по хэшам на следующем.
         * Совпадение файла на приоритетном источнике перепривязывает мод к нему.
         * Порядок меняется в «Настройки → Обновления» на сайте.
         */
        public List<String> updateSourcePriority = new ArrayList<>(List.of("modrinth", "curseforge"));
        /** Канал обновлений по умолчанию: "release" | "beta" | "alpha". */
        public String defaultChannel = "release";
        /** Поддиректория профиля с модами (updates/<профиль>/<modsSubdir>). */
        public String modsSubdir = "mods";
        /** Поддиректория профиля с ресурспаками (updates/<профиль>/<resourcepacksSubdir>). */
        public String resourcepacksSubdir = "resourcepacks";
        /** Поддиректория профиля с шейдерами (updates/<профиль>/<shadersSubdir>). */
        public String shadersSubdir = "shaderpacks";
        /** Шаблоны игнорирования (синтаксис .gitignore) для папки МОДОВ: файлы и
         *  папки, которые модулю трогать не надо — не показывать «лишними», не
         *  импортировать, не читать метаданные. По умолчанию — служебная скрытая
         *  папка .index, которую создаёт Sinytra Connector (Fabric-моды на Forge). */
        public List<String> modsIgnore = new ArrayList<>(List.of(".index"));
        /** Шаблоны игнорирования (.gitignore) для папки ресурспаков. */
        public List<String> resourcepacksIgnore = new ArrayList<>();
        /** Шаблоны игнорирования (.gitignore) для папки шейдеров. */
        public List<String> shadersIgnore = new ArrayList<>();
        /** Тип контента, выбранный в поиске по умолчанию: mod | resourcepack | shader
         *  (переназначается в «Настройки → Обновления»; пользователь может выбрать
         *  другой тип в самом поиске на любой запрос). */
        public String defaultProjectType = "mod";
        /** Проверять SHA-хэш файла после скачивания. */
        public boolean verifyHash = true;
        /** Разрешить загрузку, если провайдер не дал хэш. */
        public boolean allowWithoutHash = true;
        /** Пересчитывать хэши профиля после изменений автоматически. */
        public boolean autoRehash = true;
        /** TTL кэша ответов API Modrinth/CurseForge, минут. */
        public int cacheTtlMinutes = 10;
        /** Таймаут HTTP-запросов, секунд. */
        public int httpTimeoutSeconds = 30;
        /** Параллельные загрузки файлов. */
        public int maxConcurrentDownloads = 4;
        /** Автоматически подтягивать зависимости и конфликты при добавлении мода. */
        public boolean autoResolveDeps = true;
        /** Опциональные моды по умолчанию включены в сборку. */
        public boolean defaultOptionalEnabled = true;
        /** Синхронизировать опциональные моды с updateOptional профиля: игроки
         *  увидят их в «Настройках» лаунчера; привязка к имени файла обновляется
         *  автоматически при обновлении мода (иначе она протухает — в 5.7.x пути
         *  в OptionalActionFile.files точные, без глобов). */
        public boolean syncProfileOptional = true;
        /** Максимум модов в одном модпаке. */
        public int maxModsPerProfile = 500;
        /** Система предложений (наблюдатели предлагают моды/ресурспаки/шейдеры из поиска). */
        public SuggestionsConfig suggestions = new SuggestionsConfig();
    }

    /** Настройки системы предложений. */
    public static class SuggestionsConfig {
        /** Разрешить предлагать (выключатель всей системы). */
        public boolean enabled = true;
        /** Максимум ОЖИДАЮЩИХ предложений на пользователя одновременно (0 — без лимита). */
        public int maxPerUser = 5;
        /** Логины, лишённые права предлагать (сравнение без учёта регистра). */
        public List<String> bannedUsers = new ArrayList<>();
    }

    public static class ModrinthConfig {
        public boolean enabled = true;
        public String baseUrl = "https://api.modrinth.com/v2";
        /** Modrinth требует осмысленный User-Agent: укажите свой контакт. */
        public String userAgent = "ModsSite_module/1.0 (LaunchServer; admin@example.com)";
    }

    public static class CurseForgeConfig {
        /** Активен, только если задан apiKey (заявка на ключ: overwolf.com). */
        public boolean enabled = true;
        public String apiKey = "";
        public String baseUrl = "https://api.curseforge.com";
    }
}
