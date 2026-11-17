package pro.gravit.launchermodules.modssite.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import pro.gravit.launcher.base.Launcher;
import pro.gravit.launcher.base.profiles.ClientProfile;
import pro.gravit.launchermodules.modssite.ModsSiteConfig;
import pro.gravit.launchermodules.modssite.ModsSiteLogLevels;
import pro.gravit.launchermodules.modssite.ModsSiteModule;
import pro.gravit.launchermodules.modssite.Utils;
import pro.gravit.launchermodules.modssite.auth.AuthService;
import pro.gravit.launchermodules.modssite.auth.SessionManager;
import pro.gravit.launchermodules.modssite.mods.ModEntry;
import pro.gravit.launchermodules.modssite.mods.ImportService;
import pro.gravit.launchermodules.modssite.mods.Kinds;
import pro.gravit.launchermodules.modssite.mods.Modpack;
import pro.gravit.launchermodules.modssite.mods.ModpackService;
import pro.gravit.launchermodules.modssite.mods.ModsApiProvider;
import pro.gravit.launchermodules.modssite.mods.RemoteVersionInfo;
import pro.gravit.launchermodules.modssite.mods.SuggestionService;
import pro.gravit.launchermodules.modssite.mods.UpdateService;
import pro.gravit.launchermodules.modssite.security.IpBanManager;
import pro.gravit.launchermodules.modssite.security.RateLimiter;
import pro.gravit.launchserver.socket.NettyConnectContext;
import pro.gravit.launchserver.socket.handlers.NettyWebAPIHandler;
import pro.gravit.utils.helper.IOHelper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * HTTP-фронтенд модуля: статика веб-интерфейса + JSON API.
 * Монтируется через NettyWebAPIHandler.addUnsafeSeverlet, работает и по HTTP, и по HTTPS
 * (флаг Secure у cookie выставляется автоматически).
 */
public class ModsWebSeverlet implements NettyWebAPIHandler.SimpleSeverletHandler {
    private final ModsSiteModule module;
    /** true — экземпляр, зарегистрированный в корне "/": обрабатывает
     *  только запросы с Host из web.siteDomains; остальные передаёт дальше
     *  по конвейеру (msg.retain() + fireChannelRead — ровно как
     *  NettyWebAPIHandler делает для запросов без подходящего сервлета). */
    private final boolean rootGate;

    public ModsWebSeverlet(ModsSiteModule module) {
        this(module, false);
    }

    public ModsWebSeverlet(ModsSiteModule module, boolean rootGate) {
        this.module = module;
        this.rootGate = rootGate;
    }

    private ModsSiteConfig config() {
        return module.config;
    }

    /** Путь (без запроса) относится к сайту в корне? Только "/",
     *  "/index.html" и одиночные имена из белого списка статики
     *  (favicon.svg, style.css, app.js, i18n.js, свои файлы админа).
     *  Всё остальное — не наше: file-сервер, websocket лаунчера и т.п. */
    public static boolean isSiteRootPath(String uri) {
        if (uri == null || uri.isEmpty()) return false;
        int q = uri.indexOf('?');
        String path = q >= 0 ? uri.substring(0, q) : uri;
        if (!path.startsWith("/")) return false;
        if (path.equals("/") || path.equals("/index.html")) return true;
        String name = path.substring(1);
        return name.indexOf('/') < 0 && StaticResources.isAllowedName(name);
    }

    private boolean trustProxy() {
        String mode = config().web.trustProxyHeaders;
        if ("true".equalsIgnoreCase(mode)) return true;
        if ("false".equalsIgnoreCase(mode)) return false;
        // auto: следуем за netty.ipForwarding лаунчера
        return module.server != null && module.server.config.netty != null
                && module.server.config.netty.ipForwarding;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, FullHttpRequest msg, NettyConnectContext context) {
        // Корневая точка монтирования: сайт в корне отдаём ТОЛЬКО
        // 1) хостам из web.siteDomains (заголовок Host, порт не учитывается) и
        // 2) только путям самого сайта ("/", "/index.html", статика из
        //    белого списка имён) — все прочие пути (файлы профилей, /api,
        //    любые другие) передаём дальше по конвейеру нетронутыми:
        //    file-сервер и websocket лаунчера работают как раньше, в том
        //    числе если домен сайта совпадает с адресом лаунчера.
        if (rootGate && (!ModsSiteConfig.siteDomainMatches(
                config() == null || config().web == null ? null : config().web.siteDomains,
                msg.headers().get(HttpHeaderNames.HOST))
                || !isSiteRootPath(msg.uri()))) {
            msg.retain();
            ctx.fireChannelRead(msg);
            return;
        }
        WebRequest req = new WebRequest(ctx, msg, context, rootGate ? "" : module.mountPath, trustProxy());
        try {
            handle0(req);
        } catch (Throwable e) {
            module.logger.error("Web handler error on {}", req.path, e);
            sendJson(req, HttpResponseStatus.INTERNAL_SERVER_ERROR, error("internal", "Internal server error"));
        }
    }

    private void handle0(WebRequest req) {
        if (!module.enabled || !config().enabled) {
            // Сайт выключен командой mods site off: браузеру — человекочитаемая
            // страница, API — JSON (иначе клиенты сайта получали бы сырой JSON)
            if (req.path.startsWith("/api/")) {
                sendJson(req, HttpResponseStatus.SERVICE_UNAVAILABLE,
                        error("disabled", "Site is disabled (console: mods site on)"));
            } else {
                sendDisabledPage(req);
            }
            return;
        }
        if (!req.method.equals("GET") && !req.method.equals("POST") && !req.method.equals("HEAD")) {
            sendJson(req, HttpResponseStatus.METHOD_NOT_ALLOWED, error("method", "Only GET/POST allowed"));
            return;
        }
        String ip = req.ip();

        // Редирект на HTTPS (только за прокси, доверенным)
        String redirect = config().web.httpsRedirectUrl;
        if (redirect != null && !redirect.isBlank() && req.isHttpViaProxy()) {
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                    HttpResponseStatus.TEMPORARY_REDIRECT, Unpooled.EMPTY_BUFFER);
            String url = redirect.endsWith("/") ? redirect.substring(0, redirect.length() - 1) : redirect;
            url += req.fullPath.equals(module.mountPath) ? "/" : req.fullPath;
            response.headers().set(HttpHeaderNames.LOCATION, url);
            applySecurityHeaders(response);
            req.ctx.writeAndFlush(response).addListener(io.netty.channel.ChannelFutureListener.CLOSE);
            return;
        }

        // Чёрный список — сразу отказ
        IpBanManager.CheckResult access = module.ipBans.checkIp(ip);
        if (access.type == IpBanManager.CheckResult.Type.BLACKLISTED) {
            sendJson(req, HttpResponseStatus.FORBIDDEN, error("blacklisted", "Access denied"));
            return;
        }
        // Активный бан
        if (access.type == IpBanManager.CheckResult.Type.BANNED) {
            long seconds = Math.max(1, (access.untilEpochMs - System.currentTimeMillis()) / 1000L);
            DefaultFullHttpResponse response = jsonResponse(HttpResponseStatus.TOO_MANY_REQUESTS,
                    error("ip_banned", "IP temporarily banned: " + seconds + "s"));
            response.headers().set("Retry-After", String.valueOf(seconds));
            applySecurityHeaders(response);
            req.ctx.writeAndFlush(response).addListener(io.netty.channel.ChannelFutureListener.CLOSE);
            return;
        }

        // Статика веб-интерфейса (+ динамический theme.js — конфигурация тем
        // для страницы до входа: доступные темы, тема/вариант по умолчанию)
        if (config().web.uiEnabled && !req.path.startsWith("/api/")) {
            if (req.path.equals("/theme.js")) {
                serveThemeJs(req);
                return;
            }
            serveStatic(req);
            return;
        }

        if (!config().web.apiEnabled) {
            sendJson(req, HttpResponseStatus.SERVICE_UNAVAILABLE, error("api_disabled", "API disabled"));
            return;
        }

        // Rate limit: бакет логина — только для /api/login, api-бакет — для остальных
        boolean whitelisted = module.ipBans.getWhitelist().stream().anyMatch(p -> IpBanManager.matches(p, ip));
        if (config().rateLimit.enabled && !whitelisted) {
            if (req.path.equals("/api/login")) {
                if (!module.rateLimiter.checkLogin(ip, config().rateLimit.loginPerMinute)) {
                    module.audit.log("rate_limited", ip, null, "login");
                    sendJson(req, HttpResponseStatus.TOO_MANY_REQUESTS, error("rate_limited", "Too many requests"));
                    return;
                }
            } else if (!module.rateLimiter.checkApi(ip, config().rateLimit.apiPerMinute)) {
                module.audit.log("rate_limited", ip, null, "api");
                sendJson(req, HttpResponseStatus.TOO_MANY_REQUESTS, error("rate_limited", "Too many requests"));
                return;
            }
        }

        route(req, ip);
    }

    // ======================================================================
    //  Статика
    // ======================================================================

    private static final org.apache.logging.log4j.Logger logger =
            org.apache.logging.log4j.LogManager.getLogger(ModsWebSeverlet.class);

    /** Динамический /theme.js:window.MODS_THEMES = {defaultTheme, defaultMode,
     *  disabled, custom} — конфигурация тем оформления. Отдаётся без сессии
     *  (нужен уже на экране входа) и без кэша (no-store), чтобы смена тем
     *  админом подхватывалась при следующей загрузке страницы. */
    private void serveThemeJs(WebRequest req) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("defaultTheme", config().themes.defaultTheme);
        payload.put("defaultMode", config().themes.defaultMode);
        payload.put("disabled", config().themes.disabled);
        payload.put("custom", customThemesView());
        byte[] body = ("window.MODS_THEMES=" + Launcher.gsonManager.gson.toJson(payload) + ";")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.OK, Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/javascript; charset=utf-8");
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-store");
        applySecurityHeaders(response);
        req.ctx.writeAndFlush(response).addListener(io.netty.channel.ChannelFutureListener.CLOSE);
    }

    /** Представление кастомных тем для клиента (JSON). */
    private List<Map<String, Object>> customThemesView() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ModsSiteConfig.CustomTheme theme : config().themes.custom) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", theme.id);
            item.put("name", theme.name);
            item.put("font", theme.font);
            item.put("radius", theme.radius);
            item.put("light", theme.light);
            item.put("dark", theme.dark);
            result.add(item);
        }
        return result;
    }

    private void serveStatic(WebRequest req) {
        // Канонический адрес каталога — со слэшем: /mods/, а не /mods.
        // Если отдать index.html прямо на /mods, браузер считает базой корень
        // сайта, и относительные ссылки страницы (style.css, app.js, i18n.js)
        // превращаются в /style.css, /app.js — мимо точки монтирования → 404.
        // Снаружи это выглядит как «сайт открылся, но кривой, всё кроме HTML 404».
        // Редирект 301 на /mods/ делает базой /mods/ и полностью решает проблему
        // (при открытии index.html локально с диска ничего не меняется — там
        // ссылки и так резолвятся относительно каталога файла).
        if (req.fullPath.equals(module.mountPath)) {
            String uri = req.msg.uri();
            int q = uri.indexOf('?');
            String query = q >= 0 ? uri.substring(q) : "";
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                    HttpResponseStatus.MOVED_PERMANENTLY, Unpooled.EMPTY_BUFFER);
            response.headers().set(HttpHeaderNames.LOCATION, module.mountPath + "/" + query);
            response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
            response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
            applySecurityHeaders(response);
            req.ctx.writeAndFlush(response).addListener(io.netty.channel.ChannelFutureListener.CLOSE);
            return;
        }
        String path = req.path.equals("/") || req.path.isEmpty() ? "/index.html" : req.path;
        String name = path.startsWith("/") ? path.substring(1) : path;
        // Статику отдаём с теми же security-заголовками (CSP, X-Frame-Options,
        // nosniff, Referrer-Policy), что и API/theme.js: раньше их получали
        // только JSON-ответы, а index.html/app.js/style.css — без защиты.
        io.netty.handler.codec.http.FullHttpResponse staticResponse = StaticResources.load(name);
        if (staticResponse != null) {
            applySecurityHeaders(staticResponse);
            req.ctx.writeAndFlush(staticResponse).addListener(io.netty.channel.ChannelFutureListener.CLOSE);
            return;
        }
        // Подробный 404: имя файла и где искали — экономит часы диагностики
        logger.warn("[ModsSite] 404 for static '{}' (not in {} and not in module jar)",
                name, module.webDir == null ? "config/ModsSite/web" : module.webDir);
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.NOT_FOUND, Unpooled.wrappedBuffer(
                        ("Not found: " + name).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
        applySecurityHeaders(response);
        req.ctx.writeAndFlush(response).addListener(io.netty.channel.ChannelFutureListener.CLOSE);
    }

    // ======================================================================
    //  Маршрутизация API
    // ======================================================================

    private void route(WebRequest req, String ip) {
        String path = req.path;
        boolean isPost = req.isPost();
        switch (path) {
            case "/api/health" -> {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("status", "ok");
                data.put("module", "ModsSite");
                data.put("version", module.versionString);
                sendJson(req, HttpResponseStatus.OK, ok(data));
            }
            case "/api/login" -> {
                if (isPost) handleLogin(req, ip);
                else sendJson(req, HttpResponseStatus.METHOD_NOT_ALLOWED, error("method", "POST only"));
            }
            case "/api/logout" -> {
                if (isPost) handleLogout(req, ip);
                else sendJson(req, HttpResponseStatus.METHOD_NOT_ALLOWED, error("method", "POST only"));
            }
            case "/api/session" -> {
                if (!isPost) handleSession(req);
                else sendJson(req, HttpResponseStatus.METHOD_NOT_ALLOWED, error("method", "GET only"));
            }
            case "/api/profiles" -> {
                if (!isPost) withSession(req, false, false, (session) -> handleProfiles(req));
                else sendJson(req, HttpResponseStatus.METHOD_NOT_ALLOWED, error("method", "GET only"));
            }
            case "/api/mods" -> {
                if (!isPost) withSession(req, false, true, (session) -> handleMods(req));
                else sendJson(req, HttpResponseStatus.METHOD_NOT_ALLOWED, error("method", "GET only"));
            }
            case "/api/mods/search" -> {
                if (!isPost) withSession(req, false, false, (session) -> handleSearch(req));
                else sendJson(req, HttpResponseStatus.METHOD_NOT_ALLOWED, error("method", "GET only"));
            }
            case "/api/mods/categories" -> {
                if (!isPost) withSession(req, false, false, (session) -> handleCategories(req));
                else sendJson(req, HttpResponseStatus.METHOD_NOT_ALLOWED, error("method", "GET only"));
            }
            case "/api/mods/suggest" -> {
                if (isPost) withSession(req, false, true, (session) -> handleSuggest(req, ip, session));
                else sendJson(req, HttpResponseStatus.METHOD_NOT_ALLOWED, error("method", "POST only"));
            }
            case "/api/mods/suggestions" -> {
                if (!isPost) withSession(req, true, false, (session) -> handleSuggestionsList(req));
                else sendJson(req, HttpResponseStatus.METHOD_NOT_ALLOWED, error("method", "GET only"));
            }
            case "/api/mods/suggestions/mine" -> {
                if (!isPost) withSession(req, false, false, (session) -> handleMySuggestions(req, session));
                else sendJson(req, HttpResponseStatus.METHOD_NOT_ALLOWED, error("method", "GET only"));
            }
            case "/api/mods/suggestions/decision" -> {
                if (isPost) withSession(req, true, true, (session) -> handleSuggestDecision(req, ip, session));
                else sendJson(req, HttpResponseStatus.METHOD_NOT_ALLOWED, error("method", "POST only"));
            }
            case "/api/mods/check" -> {
                if (isPost) withSession(req, true, true, (session) -> handleCheck(req, ip, session));
                else sendJson(req, HttpResponseStatus.METHOD_NOT_ALLOWED, error("method", "POST only"));
            }
            case "/api/mods/apply" -> {
                if (isPost) withSession(req, true, true, (session) -> handleApply(req, ip, session));
                else sendJson(req, HttpResponseStatus.METHOD_NOT_ALLOWED, error("method", "POST only"));
            }
            case "/api/mods/update" -> {
                if (isPost) withSession(req, true, true, (session) -> handleUpdateMods(req, ip, session));
                else sendJson(req, HttpResponseStatus.METHOD_NOT_ALLOWED, error("method", "POST only"));
            }
            case "/api/mods/add" -> {
                if (isPost) withSession(req, true, true, (session) -> handleAdd(req, ip, session));
                else sendJson(req, HttpResponseStatus.METHOD_NOT_ALLOWED, error("method", "POST only"));
            }
            case "/api/mods/remove" -> {
                if (isPost) withSession(req, true, true, (session) -> handleRemove(req, ip, session));
                else sendJson(req, HttpResponseStatus.METHOD_NOT_ALLOWED, error("method", "POST only"));
            }
            case "/api/mods/settings" -> {
                if (isPost) withSession(req, true, true, (session) -> handleSettings(req, ip, session));
                else sendJson(req, HttpResponseStatus.METHOD_NOT_ALLOWED, error("method", "POST only"));
            }
            case "/api/mods/skip" -> {
                if (isPost) withSession(req, true, true, (session) -> handleSkip(req, ip, session));
                else sendJson(req, HttpResponseStatus.METHOD_NOT_ALLOWED, error("method", "POST only"));
            }
            case "/api/mods/resolve-deps" -> {
                if (isPost) withSession(req, true, true, (session) -> handleResolveDeps(req, ip, session));
                else sendJson(req, HttpResponseStatus.METHOD_NOT_ALLOWED, error("method", "POST only"));
            }
            case "/api/mods/orphans" -> {
                if (!isPost) withSession(req, true, true, (session) -> handleOrphans(req));
                else sendJson(req, HttpResponseStatus.METHOD_NOT_ALLOWED, error("method", "GET only"));
            }
            case "/api/mods/clean" -> {
                if (isPost) withSession(req, true, true, (session) -> handleClean(req, ip, session));
                else sendJson(req, HttpResponseStatus.METHOD_NOT_ALLOWED, error("method", "POST only"));
            }
            case "/api/mods/import" -> {
                if (isPost) withSession(req, true, true, (session) -> handleImport(req, ip, session));
                else sendJson(req, HttpResponseStatus.METHOD_NOT_ALLOWED, error("method", "POST only"));
            }
            case "/api/settings" -> {
                if (!isPost) withSession(req, true, false, (session) -> handleSettingsGet(req));
                else withSession(req, true, true, (session) -> handleSettingsPost(req, ip, session));
            }
            case "/api/profiles/reload" -> {
                if (isPost) withSession(req, true, true, (session) -> handleProfilesReload(req, ip, session));
                else sendJson(req, HttpResponseStatus.METHOD_NOT_ALLOWED, error("method", "POST only"));
            }
            case "/api/profiles/settings" -> {
                if (isPost) withSession(req, true, true, (session) -> handleProfilesSettings(req, ip, session));
                else sendJson(req, HttpResponseStatus.METHOD_NOT_ALLOWED, error("method", "POST only"));
            }
            case "/api/rehash" -> {
                if (isPost) withSession(req, true, true, (session) -> handleRehash(req, ip, session));
                else sendJson(req, HttpResponseStatus.METHOD_NOT_ALLOWED, error("method", "POST only"));
            }
            case "/api/bans" -> {
                if (!isPost) withSession(req, true, false, (session) -> handleBans(req));
                else sendJson(req, HttpResponseStatus.METHOD_NOT_ALLOWED, error("method", "GET only"));
            }
            case "/api/bans/unban" -> {
                if (isPost) withSession(req, true, true, (session) -> handleUnban(req, ip, session));
                else sendJson(req, HttpResponseStatus.METHOD_NOT_ALLOWED, error("method", "POST only"));
            }
            case "/api/ipfilter" -> {
                if (!isPost) withSession(req, true, false, (session) -> handleIpFilterGet(req));
                else withSession(req, true, true, (session) -> handleIpFilterPost(req, ip, session));
            }
            case "/api/audit" -> {
                if (!isPost) withSession(req, true, false, (session) -> handleAudit(req));
                else sendJson(req, HttpResponseStatus.METHOD_NOT_ALLOWED, error("method", "GET only"));
            }
            case "/api/users" -> {
                if (!isPost) withSession(req, true, false, (session) -> handleUsersList(req));
                else withSession(req, true, true, (session) -> handleUserPassword(req, ip, session));
            }
            case "/api/users/password" -> {
                if (isPost) withSession(req, true, true, (session) -> handleUserPassword(req, ip, session));
                else sendJson(req, HttpResponseStatus.METHOD_NOT_ALLOWED, error("method", "POST only"));
            }
            default -> sendJson(req, HttpResponseStatus.NOT_FOUND, error("not_found", "Unknown endpoint"));
        }
    }

    // ======================================================================
    //  Аутентификация
    // ======================================================================

    private interface Handler {
        void run(SessionManager.Session session) throws Exception;
    }

    /**
     * Проверка сессии (cookie) + CSRF для POST + роли.
     *
     * @param requireAdmin требовать роль admin
     * @param requireCsrf  проверять X-Mods-CSRF (все POST кроме login)
     */
    private void withSession(WebRequest req, boolean requireAdmin, boolean requireCsrf, Handler handler) {
        SessionManager.Session session = session(req);
        if (session == null) {
            sendJson(req, HttpResponseStatus.UNAUTHORIZED, error("unauthorized", "Not authorized"));
            return;
        }
        if (requireAdmin && !"admin".equals(session.role)) {
            sendJson(req, HttpResponseStatus.FORBIDDEN, error("forbidden", "Admin role required"));
            return;
        }
        if (requireCsrf && req.isPost()) {
            String header = req.header("X-Mods-CSRF");
            if (header == null || !header.equals(session.csrf)) {
                sendJson(req, HttpResponseStatus.FORBIDDEN, error("csrf", "CSRF token invalid"));
                return;
            }
        }
        try {
            handler.run(session);
        } catch (Exception e) {
            module.logger.error("API error on {}", req.path, e);
            sendJson(req, HttpResponseStatus.INTERNAL_SERVER_ERROR, error("internal", String.valueOf(e.getMessage())));
        }
    }

    private SessionManager.Session session(WebRequest req) {
        String cookie = req.cookies().get(config().auth.cookieName);
        return cookie == null ? null : module.sessions.get(cookie);
    }

    private void handleLogin(WebRequest req, String ip) {
        String login = req.bodyString("login");
        String password = req.bodyString("password");
        String totp = req.bodyString("totp");
        int maxLen = config().auth.maxFieldLength;
        if (login == null || login.isBlank() || password == null
                || login.length() > maxLen || password.length() > maxLen) {
            // Не раскрываем, что именно неверно
            sendJson(req, HttpResponseStatus.OK, error("bad_request", "Invalid credentials"));
            return;
        }
        // Блокировка по логину
        long lockedUntil = module.ipBans.accountLockUntil(login);
        if (lockedUntil > System.currentTimeMillis()) {
            module.audit.log("login_locked", ip, login, "account locked");
            sendJson(req, HttpResponseStatus.TOO_MANY_REQUESTS,
                    error("account_locked", "Account locked until " + java.time.Instant.ofEpochMilli(lockedUntil)));
            return;
        }
        AuthService.AuthResult result = module.authService.login(login, password.toCharArray(), totp, ip);
        switch (result.status) {
            case OK -> {
                module.ipBans.onLoginSuccess(ip, login);
                StringBuilder token = new StringBuilder();
                SessionManager.Session session = module.sessions.create(
                        result.login, result.source, result.role, ip, token);
                module.audit.log("login_success", ip, result.login, result.source + "/" + result.role);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("login", session.login);
                data.put("role", session.role);
                data.put("source", session.source);
                data.put("csrf", session.csrf);
                sendJsonWithCookie(req, HttpResponseStatus.OK, ok(data), sessionCookie(req, token.toString(),
                        Math.max(5, config().auth.sessionTtlMinutes) * 60));
            }
            case NEED_TOTP -> sendJson(req, HttpResponseStatus.UNAUTHORIZED, error("need_totp", "TOTP code required"));
            case WRONG_TOTP -> {
                module.ipBans.onLoginFailed(ip, login);
                module.audit.log("login_failed", ip, login, "wrong totp");
                sendJson(req, HttpResponseStatus.UNAUTHORIZED, error("wrong_totp", "Invalid TOTP code"));
            }
            case WRONG_CREDENTIALS -> {
                long banned = module.ipBans.onLoginFailed(ip, login);
                module.audit.log("login_failed", ip, login, "wrong credentials");
                if (banned > 0) {
                    sendJson(req, HttpResponseStatus.TOO_MANY_REQUESTS, error("ip_banned",
                            "Too many failed attempts. Banned until " + java.time.Instant.ofEpochMilli(banned)));
                } else {
                    sendJson(req, HttpResponseStatus.UNAUTHORIZED, error("invalid_credentials", "Invalid login or password"));
                }
            }
            case ACCOUNT_DISABLED -> {
                module.audit.log("login_failed", ip, login, "disabled");
                sendJson(req, HttpResponseStatus.FORBIDDEN, error("account_disabled", "Account disabled"));
            }
            case NOT_ADMIN -> {
                module.ipBans.onLoginFailed(ip, login);
                module.audit.log("login_failed", ip, login, "not admin");
                sendJson(req, HttpResponseStatus.FORBIDDEN, error("not_admin", "Admins only"));
            }
            case ERROR -> sendJson(req, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    error("internal", result.message == null ? "Internal error" : result.message));
            default -> sendJson(req, HttpResponseStatus.UNAUTHORIZED, error("invalid_credentials", "Invalid login or password"));
        }
    }

    private void handleLogout(WebRequest req, String ip) {
        SessionManager.Session session = session(req);
        if (session != null) {
            String csrf = req.header("X-Mods-CSRF");
            if (csrf == null || !csrf.equals(session.csrf)) {
                sendJson(req, HttpResponseStatus.FORBIDDEN, error("csrf", "CSRF token invalid"));
                return;
            }
            String cookie = req.cookies().get(config().auth.cookieName);
            module.sessions.delete(cookie);
            module.audit.log("logout", ip, session.login, "ok");
        }
        sendJsonWithCookie(req, HttpResponseStatus.OK, ok(Map.of("loggedOut", true)), sessionCookie(req, "", 0));
    }

    private void handleSession(WebRequest req) {
        SessionManager.Session session = session(req);
        if (session == null) {
            sendJson(req, HttpResponseStatus.UNAUTHORIZED, error("unauthorized", "Not authorized"));
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("login", session.login);
        data.put("role", session.role);
        data.put("source", session.source);
        data.put("csrf", session.csrf);
        data.put("title", config().web.title);
        data.put("lang", config().web.defaultLang);
        data.put("rehashSupported", module.modpacks.rehashSupported());
        Map<String, Boolean> sources = new LinkedHashMap<>();
        for (ModsApiProvider provider : module.updates.availableProviders()) {
            sources.put(provider.id(), true);
        }
        sources.putIfAbsent("modrinth", false);
        sources.putIfAbsent("curseforge", false);
        data.put("sources", sources);
        data.put("version", module.versionString);
        // Тип контента в поиске по умолчанию (переназначается в настройках)
        data.put("defaultProjectType", Kinds.normalize(config().mods.defaultProjectType));
        // Система предложений: состояние для текущего пользователя
        data.put("suggestEnabled", config().mods.suggestions.enabled);
        data.put("suggestLimit", config().mods.suggestions.maxPerUser);
        data.put("suggestBanned", isSuggestBanned(session.login));
        data.put("suggestPending", module.suggestions.pendingCount(session.login));
        sendJson(req, HttpResponseStatus.OK, ok(data));
    }

    /** Лишён ли пользователь права предлагать. */
    private boolean isSuggestBanned(String login) {
        String lower = SuggestionService.normalizeLogin(login);
        for (String banned : config().mods.suggestions.bannedUsers) {
            if (banned != null && !banned.isBlank()
                    && SuggestionService.normalizeLogin(banned).equals(lower)) {
                return true;
            }
        }
        return false;
    }

    // ======================================================================
    //  Профили и моды
    // ======================================================================

    private void handleProfiles(WebRequest req) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ClientProfile profile : module.modpacks.profiles()) {
            Modpack modpack = module.modpacks.load(profile);
            int updates = 0;
            int mods = 0;
            int resourcepacks = 0;
            int shaders = 0;
            for (ModEntry entry : modpack.mods) {
                if (entry.isUpdateAvailable()) updates++;
                switch (entry.kindOrMod()) {
                    case Kinds.RESOURCEPACK -> resourcepacks++;
                    case Kinds.SHADER -> shaders++;
                    default -> mods++;
                }
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("dir", profile.getDir());
            item.put("uuid", String.valueOf(profile.getUUID()));
            item.put("title", profile.getTitle());
            item.put("info", profile.getInfo());
            item.put("gameVersion", module.modpacks.gameVersion(profile, modpack));
            item.put("loader", module.modpacks.loader(profile, modpack));
            item.put("modsCount", modpack.mods.size());
            item.put("modsKindCount", mods);
            item.put("resourcepacksCount", resourcepacks);
            item.put("shadersCount", shaders);
            item.put("updatesCount", updates);
            result.add(item);
        }
        sendJson(req, HttpResponseStatus.OK, ok(result));
    }

    private void handleMods(WebRequest req) {
        String profileDir = req.query("profile");
        ClientProfile profile = module.modpacks.findProfile(profileDir);
        if (profile == null) {
            sendJson(req, HttpResponseStatus.NOT_FOUND, error("profile_not_found", "Profile not found"));
            return;
        }
        Modpack modpack;
        // Подхват опциональности из updateOptional профиля (правки профиля
        // после импорта модпака) — под блокировкой, с сохранением при изменении
        var lock = module.modpacks.lock(profile.getDir());
        lock.lock();
        try {
            modpack = module.modpacks.load(profile);
            boolean changed = module.modpacks.optionalSync().adoptProfileOptionals(profile, modpack);
            // Файлы, привязанные в updateOptional профиля, но не имеющие записи
            // в модпаке (findOrphans их не показывает как «лишние», и «Импорт»
            // их не предлагал — моды навсегда оставались невидимыми), а также
            // ресурспаки/шейдеры, лежащие в своих папках (их подхват не требует
            // привязки — иначе они не попадали в модпак никогда).
            // Моды подхватываются опциональными (они заявлены в updateOptional),
            // ресурспаки/шейдеры — обязательными (опциональность задаёт админ).
            if (module.modpacks.adoptUnimportedOptionals(profile, modpack) > 0) {
                changed = true;
                // Подхваченные опциональные записи согласуются с updateOptional,
                // чтобы чекбоксы работали в лаунчере
                module.modpacks.optionalSync().reconcile(profile, modpack);
            }
            // Зависимости/конфликты из профиля (dependenciesFile/conflictFile
            // записей updateOptional) — в записи модпака
            if (module.modpacks.optionalSync().adoptProfileRelations(profile, modpack)) {
                changed = true;
            }
            if (changed) {
                module.modpacks.save(profile, modpack);
            }
        } finally {
            lock.unlock();
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("dir", profile.getDir());
        data.put("title", profile.getTitle());
        data.put("gameVersion", module.modpacks.gameVersion(profile, modpack));
        data.put("loader", module.modpacks.loader(profile, modpack));
        // Явное переопределение загрузчика модпака ("" — автоопределение)
        data.put("loaderOverride", modpack.loader == null ? "" : modpack.loader);
        data.put("gameVersionOverride", modpack.gameVersion == null ? "" : modpack.gameVersion);
        data.put("autoRehash", config().mods.autoRehash);
        List<Map<String, Object>> mods = new ArrayList<>();
        for (ModEntry entry : modpack.mods) {
            mods.add(modView(profile, modpack, entry));
        }
        data.put("mods", mods);
        data.put("orphans", module.modpacks.findOrphans(profile, modpack));
        sendJson(req, HttpResponseStatus.OK, ok(data));
    }

    private Map<String, Object> modView(ClientProfile profile, Modpack modpack, ModEntry entry) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", entry.id);
        item.put("kind", entry.kindOrMod());
        item.put("source", entry.source);
        item.put("projectId", entry.projectId);
        item.put("name", entry.name);
        item.put("description", entry.description);
        item.put("iconUrl", entry.iconUrl);
        item.put("optional", entry.optional);
        item.put("optionalGroup", entry.optionalGroup);
        item.put("channel", entry.channel);
        item.put("dependencies", entry.dependencies);
        item.put("conflicts", entry.conflicts);
        item.put("note", entry.note);
        Map<String, Object> installed = new LinkedHashMap<>();
        installed.put("fileName", entry.installedFileName);
        installed.put("versionId", entry.installedVersionId);
        installed.put("version", entry.installedVersionNumber);
        installed.put("installedAt", entry.installedAt);
        boolean fileExists = false;
        if (entry.installedFileName != null && !entry.installedFileName.isBlank()) {
            Path file = module.modpacks.kindDir(profile, modpack, entry.kindOrMod())
                    .resolve(pro.gravit.launchermodules.modssite.mods.UpdateService.sanitizeFileName(entry.installedFileName));
            fileExists = Files.exists(file);
        }
        installed.put("fileExists", fileExists);
        item.put("installed", installed);
        Map<String, Object> latest = new LinkedHashMap<>();
        latest.put("versionId", entry.latestVersionId);
        latest.put("version", entry.latestVersionNumber);
        latest.put("fileName", entry.latestFileName);
        latest.put("datePublished", entry.latestDatePublished);
        latest.put("checkedAt", entry.checkedAt);
        item.put("latest", latest);
        item.put("hasUpdate", entry.isUpdateAvailable());
        item.put("postponed", entry.isPostponed());
        return item;
    }

    private static final Set<String> SEARCH_SORTS =
            Set.of("relevance", "downloads", "follows", "newest", "updated");

    private void handleSearch(WebRequest req) {
        String profileDir = req.query("profile");
        String source = req.query("source");
        String query = req.query("query");
        // query может быть пустым — это режим каталога (обзор с фильтрами)
        String typeParam = req.query("type");
        String loaderParam = req.query("loader");
        String versionParam = req.query("gameVersion");
        String categoriesParam = req.query("categories");
        String sortParam = req.query("sort");
        int offset = parseIntOr(req.query("offset"), 0);
        int limit = parseIntOr(req.query("limit"), 20);
        if (offset < 0) offset = 0;
        if (limit < 1 || limit > 30) limit = 20;
        ClientProfile profile = module.modpacks.findProfile(profileDir);
        if (profile == null) {
            sendJson(req, HttpResponseStatus.NOT_FOUND, error("profile_not_found", "Profile not found"));
            return;
        }
        Modpack modpack = module.modpacks.load(profile);
        String profileVersion = module.modpacks.gameVersion(profile, modpack);
        String profileLoader = module.modpacks.loader(profile, modpack);
        ModsApiProvider provider = module.updates.provider(source);
        if (provider == null || !provider.available()) {
            sendJson(req, HttpResponseStatus.BAD_REQUEST, error("source_unavailable", "Source unavailable"));
            return;
        }
        ModsApiProvider.SearchRequest request = new ModsApiProvider.SearchRequest();
        request.query = query == null ? "" : query.trim();
        request.projectType = Kinds.normalize(typeParam);
        request.loader = normalizeFilter(loaderParam, profileLoader);
        request.gameVersion = normalizeFilter(versionParam, profileVersion);
        request.categories = parseCategories(categoriesParam);
        request.sort = sortParam != null && SEARCH_SORTS.contains(sortParam) ? sortParam : "relevance";
        request.offset = offset;
        request.limit = limit;
        try {
            ModsApiProvider.SearchResult found = provider.search(request);
            List<Map<String, Object>> result = new ArrayList<>();
            for (ModsApiProvider.SearchHit hit : found.hits) {
                result.add(searchHitJson(profile.getDir(), modpack, hit));
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("items", result);
            data.put("total", found.total);
            data.put("offset", request.offset);
            data.put("limit", request.limit);
            data.put("sort", request.sort);
            data.put("source", provider.id());
            data.put("type", request.projectType);
            data.put("gameVersion", request.gameVersion);
            data.put("loader", request.loader);
            if (found.warning != null) {
                data.put("warning", found.warning);
            }
            sendJson(req, HttpResponseStatus.OK, ok(data));
        } catch (Exception e) {
            sendJson(req, HttpResponseStatus.BAD_GATEWAY, error("source_error", String.valueOf(e.getMessage())));
        }
    }

    /** Пустой/any параметр → «нет фильтра» (null); absent → дефолт профиля. */
    private String normalizeFilter(String param, String profileDefault) {
        if (param == null) return profileDefault;
        String v = param.trim();
        if (v.isEmpty() || "any".equalsIgnoreCase(v)) return null;
        return v;
    }

    /** CSV-список слагов категорий: lowercase, безопасные символы, максимум 8. */
    private List<String> parseCategories(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        for (String raw : csv.split(",")) {
            String c = raw.trim().toLowerCase();
            if (c.isEmpty() || !c.matches("[a-z0-9][a-z0-9_-]{0,40}")) continue;
            if (!result.contains(c)) result.add(c);
            if (result.size() >= 8) break;
        }
        return result;
    }

    private static int parseIntOr(String value, int def) {
        if (value == null || value.isBlank()) return def;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private Map<String, Object> searchHitJson(String profileDir, Modpack modpack, ModsApiProvider.SearchHit hit) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("source", hit.source);
        item.put("projectId", hit.projectId);
        item.put("slug", hit.slug);
        item.put("title", hit.title);
        item.put("description", hit.description);
        item.put("iconUrl", hit.iconUrl);
        item.put("downloads", hit.downloads);
        item.put("follows", hit.follows);
        item.put("dateModified", hit.dateModified);
        item.put("categories", hit.categories);
        item.put("alreadyAdded", modpack.findByProject(hit.source, hit.projectId) != null);
        // есть ли ожидающее предложение этого проекта в этот профиль (чип «Предложено»)
        item.put("suggested", module.suggestions.isDuplicate(profileDir, hit.source, hit.projectId));
        return item;
    }

    /** GET /api/mods/categories?source=&type= — категории для фильтров поиска. */
    private void handleCategories(WebRequest req) {
        String source = req.query("source");
        String type = Kinds.normalize(req.query("type"));
        ModsApiProvider provider = source == null ? null : module.updates.provider(source);
        if (provider == null || !provider.available()) {
            sendJson(req, HttpResponseStatus.BAD_REQUEST, error("source_unavailable", "Source unavailable"));
            return;
        }
        try {
            List<Map<String, Object>> result = new ArrayList<>();
            for (ModsApiProvider.CategoryInfo category : provider.categories(type)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", category.id);
                item.put("name", category.name);
                result.add(item);
            }
            sendJson(req, HttpResponseStatus.OK, ok(result));
        } catch (Exception e) {
            sendJson(req, HttpResponseStatus.BAD_GATEWAY, error("source_error", String.valueOf(e.getMessage())));
        }
    }

    /** POST /api/mods/skip — отложить обновление мода (не ставить последнюю версию). */
    private void handleSkip(WebRequest req, String ip, SessionManager.Session session) {
        String profileDir = req.bodyString("profile");
        String id = req.bodyString("id");
        boolean skip = !req.body().has("skip") || req.body().get("skip").getAsBoolean();
        ClientProfile profile = module.modpacks.findProfile(profileDir);
        if (profile == null) {
            sendJson(req, HttpResponseStatus.NOT_FOUND, error("profile_not_found", "Profile not found"));
            return;
        }
        var lock = module.modpacks.lock(profile.getDir());
        lock.lock();
        try {
            Modpack modpack = module.modpacks.load(profile);
            ModEntry entry = modpack.find(id);
            if (entry == null) {
                sendJson(req, HttpResponseStatus.NOT_FOUND, error("mod_not_found", "Mod not found"));
                return;
            }
            if (skip) {
                if (entry.latestVersionId == null) {
                    sendJson(req, HttpResponseStatus.BAD_REQUEST, error("no_version",
                            "No known version to postpone — run update check first"));
                    return;
                }
                entry.skippedVersionId = entry.latestVersionId;
            } else {
                entry.skippedVersionId = null;
            }
            module.modpacks.save(profile, modpack);
            module.audit.log("mods_skip", ip, session.login,
                    profileDir + " " + id + " " + (skip ? "postpone " + entry.latestVersionId : "resume"));
            sendJson(req, HttpResponseStatus.OK, ok(Map.of("entry", modView(profile, modpack, entry))));
        } finally {
            lock.unlock();
        }
    }

    /** GET /api/settings — настройки модуля для панели «Настройки». */
    private void handleSettingsGet(WebRequest req) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("updateSourcePriority", config().mods.updateSourcePriority);
        Map<String, Object> sources = new LinkedHashMap<>();
        sources.put("modrinth", module.modrinthProvider.available());
        sources.put("curseforge", module.curseforgeProvider.available());
        data.put("sources", sources);
        data.put("defaultChannel", config().mods.defaultChannel);
        data.put("cacheTtlMinutes", config().mods.cacheTtlMinutes);
        data.put("verifyHash", config().mods.verifyHash);
        data.put("allowWithoutHash", config().mods.allowWithoutHash);
        data.put("autoRehash", config().mods.autoRehash);
        data.put("autoResolveDeps", config().mods.autoResolveDeps);
        data.put("syncProfileOptional", config().mods.syncProfileOptional);
        data.put("defaultOptionalEnabled", config().mods.defaultOptionalEnabled);
        data.put("modsSubdir", config().mods.modsSubdir);
        data.put("defaultProjectType", Kinds.normalize(config().mods.defaultProjectType));
        data.put("ignoreMods", config().mods.modsIgnore);
        data.put("ignoreResourcepacks", config().mods.resourcepacksIgnore);
        data.put("ignoreShaders", config().mods.shadersIgnore);
        data.put("suggestEnabled", config().mods.suggestions.enabled);
        data.put("suggestMaxPerUser", config().mods.suggestions.maxPerUser);
        data.put("suggestBannedUsers", config().mods.suggestions.bannedUsers);
        data.put("siteDomains", config().web.siteDomains);
        // Консоль и темы оформления
        data.put("consoleLevel", config().logging.consoleLevel);
        data.put("builtinThemes", ModsSiteConfig.BUILTIN_THEME_IDS);
        data.put("themeDefault", config().themes.defaultTheme);
        data.put("themeMode", config().themes.defaultMode);
        data.put("themesDisabled", config().themes.disabled);
        data.put("themesCustom", customThemesView());
        sendJson(req, HttpResponseStatus.OK, ok(data));
    }

    /** POST /api/settings — сохранить настройки (админ). */
    private void handleSettingsPost(WebRequest req, String ip, SessionManager.Session session) {
        JsonObject body = req.body();
        boolean priorityChanged = false;
        if (body.has("updateSourcePriority") && body.get("updateSourcePriority").isJsonArray()) {
            List<String> order = new ArrayList<>();
            for (JsonElement e : body.get("updateSourcePriority").getAsJsonArray()) {
                String v = e.getAsString().trim().toLowerCase();
                if (("modrinth".equals(v) || "curseforge".equals(v)) && !order.contains(v)) {
                    order.add(v);
                }
            }
            if (order.isEmpty()) {
                sendJson(req, HttpResponseStatus.BAD_REQUEST, error("bad_priority", "Empty source priority"));
                return;
            }
            for (String id : List.of("modrinth", "curseforge")) {
                if (!order.contains(id)) order.add(id);
            }
            priorityChanged = !order.equals(config().mods.updateSourcePriority);
            config().mods.updateSourcePriority = order;
        }
        if (body.has("defaultChannel") && !body.get("defaultChannel").isJsonNull()) {
            String channel = body.get("defaultChannel").getAsString();
            if (List.of("release", "beta", "alpha").contains(channel)) {
                config().mods.defaultChannel = channel;
            }
        }
        if (body.has("cacheTtlMinutes") && !body.get("cacheTtlMinutes").isJsonNull()) {
            try {
                int ttl = body.get("cacheTtlMinutes").getAsInt();
                config().mods.cacheTtlMinutes = Math.max(1, Math.min(1440, ttl));
            } catch (RuntimeException ignored) {
            }
        }
        for (String field : List.of("verifyHash", "allowWithoutHash", "autoRehash",
                "autoResolveDeps", "syncProfileOptional", "defaultOptionalEnabled")) {
            if (body.has(field) && !body.get(field).isJsonNull()) {
                try {
                    boolean value = body.get(field).getAsBoolean();
                    switch (field) {
                        case "verifyHash" -> config().mods.verifyHash = value;
                        case "allowWithoutHash" -> config().mods.allowWithoutHash = value;
                        case "autoRehash" -> config().mods.autoRehash = value;
                        case "autoResolveDeps" -> config().mods.autoResolveDeps = value;
                        case "syncProfileOptional" -> config().mods.syncProfileOptional = value;
                        case "defaultOptionalEnabled" -> config().mods.defaultOptionalEnabled = value;
                    }
                } catch (RuntimeException ignored) {
                }
            }
        }
        // Тип поиска по умолчанию и система предложений
        if (body.has("defaultProjectType") && !body.get("defaultProjectType").isJsonNull()) {
            config().mods.defaultProjectType = Kinds.normalize(body.get("defaultProjectType").getAsString());
        }
        if (body.has("suggestEnabled") && !body.get("suggestEnabled").isJsonNull()) {
            try {
                config().mods.suggestions.enabled = body.get("suggestEnabled").getAsBoolean();
            } catch (RuntimeException ignored) {
            }
        }
        if (body.has("suggestMaxPerUser") && !body.get("suggestMaxPerUser").isJsonNull()) {
            try {
                config().mods.suggestions.maxPerUser = Math.max(0, Math.min(100,
                        body.get("suggestMaxPerUser").getAsInt()));
            } catch (RuntimeException ignored) {
            }
        }
        if (body.has("suggestBannedUsers") && body.get("suggestBannedUsers").isJsonArray()) {
            // Полный снимок списка: чистим, дедуплицируем, ограничиваем размер
            List<String> banned = new ArrayList<>();
            for (JsonElement e : body.get("suggestBannedUsers").getAsJsonArray()) {
                if (e == null || !e.isJsonPrimitive()) continue;
                String login = SuggestionService.normalizeLogin(e.getAsString());
                if (!login.isEmpty() && login.length() <= 64 && !banned.contains(login)) {
                    banned.add(login);
                }
                if (banned.size() >= 500) break;
            }
            config().mods.suggestions.bannedUsers = banned;
        }
        // Шаблоны игнорирования (.gitignore) для папок категорий:
        // полный снимок списка, порядок важен (правила «!» — исключения)
        config().mods.modsIgnore = sanitizeIgnoreList(body, "ignoreMods",
                config().mods.modsIgnore == null || config().mods.modsIgnore.isEmpty()
                        ? List.of(".index") : config().mods.modsIgnore);
        config().mods.resourcepacksIgnore = sanitizeIgnoreList(body, "ignoreResourcepacks",
                config().mods.resourcepacksIgnore);
        config().mods.shadersIgnore = sanitizeIgnoreList(body, "ignoreShaders",
                config().mods.shadersIgnore);
        // Домены root-режима (сайт в корне этих хостов): полный снимок списка,
        // санитизация как в конфиге (регистр, порт, дубли). Применяется сразу:
        // корневой сервлет читает живой конфиг на каждый запрос.
        if (body.has("siteDomains") && body.get("siteDomains").isJsonArray()) {
            List<String> domains = new ArrayList<>();
            for (JsonElement e : body.get("siteDomains").getAsJsonArray()) {
                if (e == null || !e.isJsonPrimitive()) continue;
                domains.add(e.getAsString());
            }
            config().web.siteDomains = ModsSiteConfig.sanitizeSiteDomains(domains);
        }
        // Уровень вывода в консоль (off/error/warn/all) — применяется сразу
        if (body.has("consoleLevel") && !body.get("consoleLevel").isJsonNull()) {
            String level = ModsSiteLogLevels.normalize(body.get("consoleLevel").getAsString());
            config().logging.consoleLevel = level;
            ModsSiteLogLevels.apply(level);
        }
        // Темы оформления: тема/вариант по умолчанию, выключенные встроенные,
        // кастомные темы (полный снимок списка; добавление/удаление — здесь же)
        if (body.has("themeMode") && !body.get("themeMode").isJsonNull()) {
            config().themes.defaultMode = "light".equals(body.get("themeMode").getAsString()) ? "light" : "dark";
        }
        if (body.has("themesDisabled") && body.get("themesDisabled").isJsonArray()) {
            List<String> disabled = new ArrayList<>();
            for (JsonElement e : body.get("themesDisabled").getAsJsonArray()) {
                if (e == null || !e.isJsonPrimitive()) continue;
                String id = e.getAsString().trim().toLowerCase(Locale.ROOT);
                if (ModsSiteConfig.BUILTIN_THEME_IDS.contains(id) && !disabled.contains(id)) {
                    disabled.add(id);
                }
            }
            config().themes.disabled = disabled;
        }
        if (body.has("themesCustom") && body.get("themesCustom").isJsonArray()) {
            List<ModsSiteConfig.CustomTheme> custom = new ArrayList<>();
            for (JsonElement e : body.get("themesCustom").getAsJsonArray()) {
                if (e == null || !e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                ModsSiteConfig.CustomTheme theme = new ModsSiteConfig.CustomTheme();
                if (o.has("id") && o.get("id").isJsonPrimitive()) {
                    theme.id = o.get("id").getAsString();
                }
                if (o.has("name") && o.get("name").isJsonPrimitive()) {
                    theme.name = o.get("name").getAsString();
                }
                if (o.has("font") && o.get("font").isJsonPrimitive()) {
                    theme.font = o.get("font").getAsString();
                }
                if (o.has("radius") && o.get("radius").isJsonPrimitive()) {
                    theme.radius = o.get("radius").getAsString();
                }
                theme.light = stringMap(o, "light");
                theme.dark = stringMap(o, "dark");
                custom.add(theme);
            }
            // санитизация: отбраковка тем с невалидными именами/цветами,
            // генерация уникальных id, лимит количества
            config().themes.custom = ModsSiteConfig.sanitizeCustomThemes(custom);
        }
        if (body.has("themeDefault") && !body.get("themeDefault").isJsonNull()) {
            config().themes.defaultTheme = body.get("themeDefault").getAsString().trim().toLowerCase(Locale.ROOT);
        }
        // тема по умолчанию обязана быть доступной (выключена/удалена —
        // normalizeThemes подберёт запасной вариант)
        config().normalize();
        module.saveConfig();
        // приоритет/кэш изменились — сбрасываем кэши источников
        if (priorityChanged) {
            module.modrinthProvider.clearCache();
            module.curseforgeProvider.clearCache();
        }
        module.audit.log("settings_save", ip, session.login,
                "priority=" + config().mods.updateSourcePriority
                        + " domains=" + config().web.siteDomains
                        + " console=" + config().logging.consoleLevel
                        + " themes=default:" + config().themes.defaultTheme + "/" + config().themes.defaultMode
                        + " disabled=" + config().themes.disabled.size()
                        + " custom=" + config().themes.custom.size());
        // отдаём актуальное состояние (как GET)
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("updateSourcePriority", config().mods.updateSourcePriority);
        Map<String, Object> sources = new LinkedHashMap<>();
        sources.put("modrinth", module.modrinthProvider.available());
        sources.put("curseforge", module.curseforgeProvider.available());
        data.put("sources", sources);
        data.put("defaultChannel", config().mods.defaultChannel);
        data.put("cacheTtlMinutes", config().mods.cacheTtlMinutes);
        data.put("verifyHash", config().mods.verifyHash);
        data.put("allowWithoutHash", config().mods.allowWithoutHash);
        data.put("autoRehash", config().mods.autoRehash);
        data.put("autoResolveDeps", config().mods.autoResolveDeps);
        data.put("syncProfileOptional", config().mods.syncProfileOptional);
        data.put("defaultOptionalEnabled", config().mods.defaultOptionalEnabled);
        data.put("modsSubdir", config().mods.modsSubdir);
        data.put("defaultProjectType", Kinds.normalize(config().mods.defaultProjectType));
        data.put("ignoreMods", config().mods.modsIgnore);
        data.put("ignoreResourcepacks", config().mods.resourcepacksIgnore);
        data.put("ignoreShaders", config().mods.shadersIgnore);
        data.put("suggestEnabled", config().mods.suggestions.enabled);
        data.put("suggestMaxPerUser", config().mods.suggestions.maxPerUser);
        data.put("suggestBannedUsers", config().mods.suggestions.bannedUsers);
        data.put("siteDomains", config().web.siteDomains);
        data.put("consoleLevel", config().logging.consoleLevel);
        data.put("builtinThemes", ModsSiteConfig.BUILTIN_THEME_IDS);
        data.put("themeDefault", config().themes.defaultTheme);
        data.put("themeMode", config().themes.defaultMode);
        data.put("themesDisabled", config().themes.disabled);
        data.put("themesCustom", customThemesView());
        sendJson(req, HttpResponseStatus.OK, ok(data));
    }

    /** Объект JSON -> Map<String,String> (для цветов кастомных тем). */
    private static Map<String, String> stringMap(JsonObject object, String field) {
        Map<String, String> result = new LinkedHashMap<>();
        if (object.has(field) && object.get(field).isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : object.get(field).getAsJsonObject().entrySet()) {
                if (entry.getValue() != null && entry.getValue().isJsonPrimitive()) {
                    result.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        }
        return result;
    }

    /** Санитизация списка шаблонов игнорирования: трим, без пустых строк,
     *  без точных дублей, максимум 200 шаблонов по 250 символов. Комментарии
     *  («#») сохраняются — матчер их пропускает, а админу они полезны.
     *  Поле в запросе отсутствует — прежнее значение без изменений. */
    private static List<String> sanitizeIgnoreList(JsonObject body, String field,
                                                   List<String> previous) {
        JsonElement element = body.get(field);
        if (element == null || !element.isJsonArray()) return previous;
        List<String> result = new ArrayList<>();
        for (JsonElement e : element.getAsJsonArray()) {
            if (e == null || !e.isJsonPrimitive()) continue;
            String line = e.getAsString().strip();
            if (line.isEmpty()) continue;
            if (line.length() > 250) line = line.substring(0, 250);
            if (!result.contains(line)) result.add(line);
            if (result.size() >= 200) break;
        }
        return result;
    }

    /**
     * POST /api/profiles/reload — перечитать конфиги профилей с диска
     * (profiles/*.json): чтобы ручные правки (updateOptional, свойства
     * modsLoader/modsGameVersion и т.п.) учитывались без перезапуска.
     * После перечитывания — подхват опциональности из updateOptional
     * (моды, привязанные в профиле, становятся опциональными) и согласование
     * записей (если включена синхронизация).
     */
    private void handleProfilesReload(WebRequest req, String ip, SessionManager.Session session) {
        if (!(module.server.config.profilesProvider instanceof
                pro.gravit.launchserver.auth.profiles.LocalProfilesProvider local)) {
            sendJson(req, HttpResponseStatus.BAD_REQUEST, error("not_supported",
                    "Profile reload supported only for local profiles provider"));
            return;
        }
        try {
            local.readProfilesDir();
        } catch (Exception e) {
            module.logger.error("Profiles reload failed", e);
            sendJson(req, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    error("reload_failed", String.valueOf(e.getMessage())));
            return;
        }
        int count = module.modpacks.profiles().size();
        int adoptedPacks = 0;
        // Подхват правок профилей в модпаки (опциональность из updateOptional)
        for (ClientProfile profile : module.modpacks.profiles()) {
            var lock = module.modpacks.lock(profile.getDir());
            lock.lock();
            try {
                Modpack modpack = module.modpacks.load(profile);
                boolean changed = module.modpacks.optionalSync().adoptProfileOptionals(profile, modpack);
                changed |= module.modpacks.optionalSync().adoptProfileRelations(profile, modpack);
                if (module.modpacks.optionalSync().enabled()) {
                    changed |= module.modpacks.optionalSync().reconcile(profile, modpack);
                }
                if (changed) {
                    module.modpacks.save(profile, modpack);
                    adoptedPacks++;
                }
            } catch (Throwable t) {
                module.logger.warn("Optional sync after reload failed for '{}'", profile.getDir(), t);
            } finally {
                lock.unlock();
            }
        }
        module.audit.log("profiles_reload", ip, session.login, count + " profiles");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("profiles", count);
        data.put("updatedModpacks", adoptedPacks);
        sendJson(req, HttpResponseStatus.OK, ok(data));
    }

    /**
     * POST /api/profiles/settings — настройки модпака профиля: загрузчик
     * по умолчанию (применяется автоматически в поиске/проверке обновлений,
     * в поиске его можно поменять точечно). loader: "" — автоопределение
     * (свойство modsLoader/имя профиля), "any" — явно без фильтра,
     * fabric|forge|neoforge|quilt — конкретный загрузчик (modpack.loader).
     */
    private void handleProfilesSettings(WebRequest req, String ip, SessionManager.Session session) {
        String profileDir = req.bodyString("profile");
        ClientProfile profile = module.modpacks.findProfile(profileDir);
        if (profile == null) {
            sendJson(req, HttpResponseStatus.NOT_FOUND, error("profile_not_found", "Profile not found"));
            return;
        }
        String loader = req.bodyString("loader");
        if (loader != null) {
            loader = loader.trim().toLowerCase();
            if (!loader.isBlank() && !"any".equals(loader) && !"fabric".equals(loader)
                    && !"forge".equals(loader) && !"neoforge".equals(loader) && !"quilt".equals(loader)) {
                sendJson(req, HttpResponseStatus.BAD_REQUEST, error("bad_loader",
                        "loader must be one of: any, fabric, forge, neoforge, quilt"));
                return;
            }
        }
        var lock = module.modpacks.lock(profile.getDir());
        lock.lock();
        try {
            Modpack modpack = module.modpacks.load(profile);
            if (loader != null) {
                // "" — автоопределение (properties/имя профиля), "any" — явное
                // «без фильтра загрузчика», прочие — конкретный загрузчик
                String normalized = loader.isBlank() ? "" : loader;
                if (!normalized.equals(modpack.loader)) {
                    modpack.loader = normalized;
                    module.modpacks.save(profile, modpack);
                    module.audit.log("profile_settings", ip, session.login,
                            profileDir + " loader=" + (normalized.isEmpty() ? "auto" : normalized));
                }
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("loader", module.modpacks.loader(profile, modpack));
            data.put("loaderOverride", modpack.loader == null ? "" : modpack.loader);
            data.put("gameVersion", module.modpacks.gameVersion(profile, modpack));
            sendJson(req, HttpResponseStatus.OK, ok(data));
        } finally {
            lock.unlock();
        }
    }

    private void handleAdd(WebRequest req, String ip, SessionManager.Session session) {
        String profileDir = req.bodyString("profile");
        String source = req.bodyString("source");
        String projectId = req.bodyString("projectId");
        boolean optional = Boolean.parseBoolean(String.valueOf(req.body().get("optional")));
        String channel = req.bodyString("channel");
        String kind = Kinds.normalize(req.bodyString("kind"));
        ClientProfile profile = module.modpacks.findProfile(profileDir);
        if (profile == null) {
            sendJson(req, HttpResponseStatus.NOT_FOUND, error("profile_not_found", "Profile not found"));
            return;
        }
        ModsApiProvider provider = module.updates.provider(source);
        if (provider == null || !provider.available()) {
            sendJson(req, HttpResponseStatus.BAD_REQUEST, error("source_unavailable", "Source unavailable"));
            return;
        }
        var lock = module.modpacks.lock(profile.getDir());
        lock.lock();
        try {
            Modpack modpack = module.modpacks.load(profile);
            ModpackService.AddResult result = module.modpacks.add(
                    profile, modpack, provider, projectId, optional, channel, module.updates, kind);
            if (config().mods.autoRehash) {
                try {
                    module.modpacks.rehash(profile.getDir());
                } catch (Exception e) {
                    module.logger.warn("Rehash failed", e);
                }
            }
            module.audit.log("mods_add", ip, session.login, profileDir + " " + result.entry.id,
                    Map.of("source", result.entry.source, "projectId", String.valueOf(result.entry.projectId)));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("entry", modView(profile, modpack, result.entry));
            data.put("missingDependencies", projectInfos(result.missingDependencies));
            data.put("missingConflicts", projectInfos(result.missingConflicts));
            sendJson(req, HttpResponseStatus.OK, ok(data));
        } catch (Exception e) {
            sendJson(req, HttpResponseStatus.BAD_REQUEST, error("add_failed", String.valueOf(e.getMessage())));
        } finally {
            lock.unlock();
        }
    }

    private List<Map<String, String>> projectInfos(List<ModsApiProvider.ProjectInfo> infos) {
        List<Map<String, String>> result = new ArrayList<>();
        for (ModsApiProvider.ProjectInfo info : infos) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("source", info.source);
            item.put("projectId", info.projectId);
            item.put("name", info.title);
            item.put("iconUrl", info.iconUrl);
            result.add(item);
        }
        return result;
    }

    private void handleRemove(WebRequest req, String ip, SessionManager.Session session) {
        String profileDir = req.bodyString("profile");
        JsonElement idsElement = req.body().get("ids");
        if (idsElement == null || !idsElement.isJsonArray()) {
            sendJson(req, HttpResponseStatus.BAD_REQUEST, error("bad_request", "ids required"));
            return;
        }
        ClientProfile profile = module.modpacks.findProfile(profileDir);
        if (profile == null) {
            sendJson(req, HttpResponseStatus.NOT_FOUND, error("profile_not_found", "Profile not found"));
            return;
        }
        var lock = module.modpacks.lock(profile.getDir());
        lock.lock();
        try {
            Modpack modpack = module.modpacks.load(profile);
            List<String> ids = new ArrayList<>();
            for (JsonElement e : idsElement.getAsJsonArray()) {
                ids.add(e.getAsString());
            }
            List<String> removed = module.modpacks.remove(profile, modpack, ids);
            if (config().mods.autoRehash) {
                try {
                    module.modpacks.rehash(profile.getDir());
                } catch (Exception e) {
                    module.logger.warn("Rehash failed", e);
                }
            }
            module.audit.log("mods_remove", ip, session.login, profileDir + " " + removed);
            sendJson(req, HttpResponseStatus.OK, ok(Map.of("removed", removed)));
        } catch (Exception e) {
            sendJson(req, HttpResponseStatus.INTERNAL_SERVER_ERROR, error("remove_failed", String.valueOf(e.getMessage())));
        } finally {
            lock.unlock();
        }
    }

    private void handleSettings(WebRequest req, String ip, SessionManager.Session session) {
        String profileDir = req.bodyString("profile");
        String id = req.bodyString("id");
        ClientProfile profile = module.modpacks.findProfile(profileDir);
        if (profile == null) {
            sendJson(req, HttpResponseStatus.NOT_FOUND, error("profile_not_found", "Profile not found"));
            return;
        }
        var lock = module.modpacks.lock(profile.getDir());
        lock.lock();
        try {
            Modpack modpack = module.modpacks.load(profile);
            ModEntry entry = modpack.find(id);
            if (entry == null) {
                sendJson(req, HttpResponseStatus.NOT_FOUND, error("mod_not_found", "Mod not found"));
                return;
            }
            JsonObject body = req.body();
            if (body.has("optional") && !body.get("optional").isJsonNull()) {
                entry.optional = body.get("optional").getAsBoolean();
                if (!entry.optional) entry.enabled = true;
                // Могильник «обязательности»: явное решение админа переживает
                // автоподхват опциональности из профиля; «Опциональный» снимает
                entry.requiredTombstone = !entry.optional;
            }
            if (body.has("channel") && !body.get("channel").isJsonNull()) {
                String channel = body.get("channel").getAsString();
                if (List.of("release", "beta", "alpha").contains(channel)) {
                    entry.channel = channel;
                }
            }
            if (body.has("note") && !body.get("note").isJsonNull()) {
                String note = body.get("note").getAsString();
                entry.note = note.length() > 500 ? note.substring(0, 500) : note;
            }
            if (body.has("optionalGroup") && !body.get("optionalGroup").isJsonNull()) {
                // Смена группы с могильниками (applyGroupChange): мод, удалённый
                // из группы, не возвращается автоподхватом из профиля
                ModpackService.applyGroupChange(entry, body.get("optionalGroup").getAsString().trim());
            }
            // Зависимости/конфликты: цели — только опциональные моды, конфликты
            // симметричны, вычищенное не подтягивается обратно автоподтяжкой из API
            // (могильники). См. ModpackService.applyRelationEdit.
            List<String> newDeps = null;
            List<String> newConflicts = null;
            for (String field : List.of("dependencies", "conflicts")) {
                if (body.has(field) && body.get(field).isJsonArray()) {
                    List<String> values = new ArrayList<>();
                    for (JsonElement e : body.get(field).getAsJsonArray()) {
                        if (e != null && e.isJsonPrimitive()) values.add(e.getAsString());
                    }
                    if ("dependencies".equals(field)) newDeps = values;
                    else newConflicts = values;
                }
            }
            ModpackService.applyRelationEdit(modpack, entry, newDeps, newConflicts);
            // Согласовать updateOptional (создать/убрать/обновить запись мода)
            module.modpacks.optionalSync().reconcile(profile, modpack);
            module.modpacks.save(profile, modpack);
            module.audit.log("mods_settings", ip, session.login, profileDir + " " + id);
            sendJson(req, HttpResponseStatus.OK, ok(Map.of("entry", modView(profile, modpack, entry))));
        } finally {
            lock.unlock();
        }
    }

    private void handleResolveDeps(WebRequest req, String ip, SessionManager.Session session) {
        String profileDir = req.bodyString("profile");
        String id = req.bodyString("id");
        ClientProfile profile = module.modpacks.findProfile(profileDir);
        if (profile == null) {
            sendJson(req, HttpResponseStatus.NOT_FOUND, error("profile_not_found", "Profile not found"));
            return;
        }
        var lock = module.modpacks.lock(profile.getDir());
        lock.lock();
        try {
            Modpack modpack = module.modpacks.load(profile);
            ModEntry entry = modpack.find(id);
            if (entry == null) {
                sendJson(req, HttpResponseStatus.NOT_FOUND, error("mod_not_found", "Mod not found"));
                return;
            }
            ModsApiProvider provider = module.updates.provider(entry.source);
            if (provider == null || !provider.available()) {
                sendJson(req, HttpResponseStatus.BAD_REQUEST, error("source_unavailable", "Source unavailable"));
                return;
            }
            ModpackService.AddResult result = new ModpackService.AddResult();
            result.entry = entry;
            module.modpacks.linkDependencies(profile, modpack, entry, provider, module.updates, result);
            module.modpacks.save(profile, modpack);
            module.audit.log("mods_resolve_deps", ip, session.login, profileDir + " " + id);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("entry", modView(profile, modpack, entry));
            data.put("missingDependencies", projectInfos(result.missingDependencies));
            data.put("missingConflicts", projectInfos(result.missingConflicts));
            sendJson(req, HttpResponseStatus.OK, ok(data));
        } catch (Exception e) {
            sendJson(req, HttpResponseStatus.BAD_GATEWAY, error("resolve_failed", String.valueOf(e.getMessage())));
        } finally {
            lock.unlock();
        }
    }

    private void handleCheck(WebRequest req, String ip, SessionManager.Session session) {
        String profileDir = req.bodyString("profile");
        boolean force = !req.body().has("force") || req.body().get("force").getAsBoolean();
        ClientProfile profile = module.modpacks.findProfile(profileDir);
        if (profile == null) {
            sendJson(req, HttpResponseStatus.NOT_FOUND, error("profile_not_found", "Profile not found"));
            return;
        }
        var lock = module.modpacks.lock(profile.getDir());
        lock.lock();
        try {
            Modpack modpack = module.modpacks.load(profile);
            // Подхват опциональности и связей из updateOptional — до проверки,
            // чтобы ответ сразу содержал корректные флаги
            boolean adopted = module.modpacks.optionalSync().adoptProfileOptionals(profile, modpack);
            adopted |= module.modpacks.optionalSync().adoptProfileRelations(profile, modpack);
            UpdateService.CheckSummary summary = module.updates.checkUpdates(
                    profile, modpack, module.modpacks, force);
            List<UpdateService.UpdateCheck> checks = summary.checks;
            if (adopted) {
                module.modpacks.save(profile, modpack);
            }
            module.audit.log("mods_check", ip, session.login, profileDir);
            int updated = 0;
            int relinked = 0;
            List<Map<String, Object>> result = new ArrayList<>();
            for (int i = 0; i < modpack.mods.size(); i++) {
                UpdateService.UpdateCheck check = i < checks.size() ? checks.get(i) : null;
                if (check != null && check.hasUpdate) updated++;
                if (check != null && check.relinked) relinked++;
                result.add(modView(profile, modpack, modpack.mods.get(i)));
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("updatesAvailable", updated);
            data.put("relinked", relinked);
            data.put("mods", result);
            if (!summary.warnings.isEmpty()) {
                data.put("warnings", summary.warnings);
            }
            sendJson(req, HttpResponseStatus.OK, ok(data));
        } finally {
            lock.unlock();
        }
    }

    private void handleApply(WebRequest req, String ip, SessionManager.Session session) {
        String profileDir = req.bodyString("profile");
        ClientProfile profile = module.modpacks.findProfile(profileDir);
        if (profile == null) {
            sendJson(req, HttpResponseStatus.NOT_FOUND, error("profile_not_found", "Profile not found"));
            return;
        }
        var lock = module.modpacks.lock(profile.getDir());
        lock.lock();
        try {
            Modpack modpack = module.modpacks.load(profile);
            // 1.1.0: включение/выключение модов убрано — «скачан, значит нужен»:
            // состав = весь модпак, поле selection из запроса игнорируется.
            // Конфликты состав не блокируют (ограничение — только на игрока
            // в лаунчере, через conflictFile).
            Set<String> effective = module.modpacks.enabledIds(modpack);
            List<UpdateService.InstallResult> installed = module.updates.applyComposition(
                    profile, modpack, module.modpacks);
            boolean rehashed = false;
            if (config().mods.autoRehash) {
                try {
                    module.modpacks.rehash(profile.getDir());
                    rehashed = true;
                } catch (Exception e) {
                    module.logger.warn("Rehash failed", e);
                }
            }
            module.audit.log("mods_apply", ip, session.login, profileDir + " " + effective.size() + " mods",
                    Map.of("installed", installed.size(), "rehashed", rehashed));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("applied", new ArrayList<>(effective));
            data.put("installed", installed);
            data.put("rehashed", rehashed);
            List<Map<String, Object>> mods = new ArrayList<>();
            for (ModEntry entry : modpack.mods) {
                mods.add(modView(profile, modpack, entry));
            }
            data.put("mods", mods);
            sendJson(req, HttpResponseStatus.OK, ok(data));
        } catch (Exception e) {
            sendJson(req, HttpResponseStatus.INTERNAL_SERVER_ERROR, error("apply_failed", String.valueOf(e.getMessage())));
        } finally {
            lock.unlock();
        }
    }

    private void handleUpdateMods(WebRequest req, String ip, SessionManager.Session session) {
        String profileDir = req.bodyString("profile");
        Set<String> ids = new HashSet<>();
        JsonElement idsElement = req.body().get("ids");
        if (idsElement != null && idsElement.isJsonArray()) {
            for (JsonElement e : idsElement.getAsJsonArray()) {
                ids.add(e.getAsString());
            }
        }
        boolean includeDisabled = req.body().has("includeDisabled")
                && req.body().get("includeDisabled").getAsBoolean();
        ClientProfile profile = module.modpacks.findProfile(profileDir);
        if (profile == null) {
            sendJson(req, HttpResponseStatus.NOT_FOUND, error("profile_not_found", "Profile not found"));
            return;
        }
        var lock = module.modpacks.lock(profile.getDir());
        lock.lock();
        try {
            Modpack modpack = module.modpacks.load(profile);
            module.updates.checkUpdates(profile, modpack, module.modpacks, false);
            List<UpdateService.InstallResult> results = module.updates.updateMods(
                    profile, modpack, module.modpacks, ids.isEmpty() ? null : ids, includeDisabled);
            boolean rehashed = false;
            if (config().mods.autoRehash) {
                try {
                    module.modpacks.rehash(profile.getDir());
                    rehashed = true;
                } catch (Exception e) {
                    module.logger.warn("Rehash failed", e);
                }
            }
            module.audit.log("mods_update", ip, session.login, profileDir,
                    Map.of("updated", results.size(), "rehashed", rehashed));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("results", results);
            data.put("rehashed", rehashed);
            List<Map<String, Object>> mods = new ArrayList<>();
            for (ModEntry entry : modpack.mods) {
                mods.add(modView(profile, modpack, entry));
            }
            data.put("mods", mods);
            sendJson(req, HttpResponseStatus.OK, ok(data));
        } catch (Exception e) {
            sendJson(req, HttpResponseStatus.INTERNAL_SERVER_ERROR, error("update_failed", String.valueOf(e.getMessage())));
        } finally {
            lock.unlock();
        }
    }

    private void handleOrphans(WebRequest req) {
        String profileDir = req.query("profile");
        ClientProfile profile = module.modpacks.findProfile(profileDir);
        if (profile == null) {
            sendJson(req, HttpResponseStatus.NOT_FOUND, error("profile_not_found", "Profile not found"));
            return;
        }
        Modpack modpack = module.modpacks.load(profile);
        sendJson(req, HttpResponseStatus.OK, ok(Map.of("orphans", module.modpacks.findOrphans(profile, modpack))));
    }

    private void handleClean(WebRequest req, String ip, SessionManager.Session session) {
        String profileDir = req.bodyString("profile");
        ClientProfile profile = module.modpacks.findProfile(profileDir);
        if (profile == null) {
            sendJson(req, HttpResponseStatus.NOT_FOUND, error("profile_not_found", "Profile not found"));
            return;
        }
        var lock = module.modpacks.lock(profile.getDir());
        lock.lock();
        try {
            Modpack modpack = module.modpacks.load(profile);
            // files (необязательно): удалить только выбранные «лишние» файлы
            List<ModpackService.OrphanFile> files = bodyOrphanList(req, "files");
            int removed = module.modpacks.cleanOrphans(profile, modpack, files);
            if (config().mods.autoRehash) {
                try {
                    module.modpacks.rehash(profile.getDir());
                } catch (Exception e) {
                    module.logger.warn("Rehash failed", e);
                }
            }
            module.audit.log("mods_clean", ip, session.login, profileDir + " " + removed);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("removed", removed);
            data.put("orphans", module.modpacks.findOrphans(profile, modpack));
            sendJson(req, HttpResponseStatus.OK, ok(data));
        } finally {
            lock.unlock();
        }
    }

    /** POST /api/mods/import — импорт файлов из папок контента в модпак (по хэшам Modrinth/CurseForge). */
    private void handleImport(WebRequest req, String ip, SessionManager.Session session) {
        String profileDir = req.bodyString("profile");
        ClientProfile profile = module.modpacks.findProfile(profileDir);
        if (profile == null) {
            sendJson(req, HttpResponseStatus.NOT_FOUND, error("profile_not_found", "Profile not found"));
            return;
        }
        List<ModpackService.OrphanFile> files = bodyOrphanList(req, "files");
        if (files.isEmpty()) {
            sendJson(req, HttpResponseStatus.BAD_REQUEST, error("no_files", "No files to import"));
            return;
        }
        var lock = module.modpacks.lock(profile.getDir());
        lock.lock();
        try {
            Modpack modpack = module.modpacks.load(profile);
            ImportService.Report report = module.imports.importOrphans(profile, modpack, files);
            boolean rehashed = false;
            if (config().mods.autoRehash && (report.imported > 0 || report.local > 0)) {
                try {
                    module.modpacks.rehash(profile.getDir());
                    rehashed = true;
                } catch (Exception e) {
                    module.logger.warn("Rehash after import failed", e);
                }
            }
            module.audit.log("mods_import", ip, session.login, profileDir
                    + " imported=" + report.imported + " local=" + report.local
                    + " failed=" + report.failed + " skipped=" + report.skipped);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("results", report.results);
            data.put("imported", report.imported);
            data.put("local", report.local);
            data.put("failed", report.failed);
            data.put("skipped", report.skipped);
            data.put("limit", report.limit);
            data.put("rehashed", rehashed);
            if (!report.warnings.isEmpty()) {
                data.put("warnings", report.warnings);
            }
            data.put("orphans", module.modpacks.findOrphans(profile, modpack));
            sendJson(req, HttpResponseStatus.OK, ok(data));
        } catch (Exception e) {
            sendJson(req, HttpResponseStatus.INTERNAL_SERVER_ERROR, error("import_failed", String.valueOf(e.getMessage())));
        } finally {
            lock.unlock();
        }
    }

    /** JSON-массив строк из тела запроса (поле files); пустой список при отсутствии. */
    private static List<String> bodyStringList(WebRequest req, String field) {
        try {
            return req.bodyArray(field);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** Массив «лишних» файлов из тела: объекты {name, kind} или строки (старый формат — моды). */
    private static List<ModpackService.OrphanFile> bodyOrphanList(WebRequest req, String field) {
        List<ModpackService.OrphanFile> result = new ArrayList<>();
        JsonElement element;
        try {
            element = req.body().get(field);
        } catch (RuntimeException e) {
            return result;
        }
        if (element == null || !element.isJsonArray()) return result;
        for (JsonElement e : element.getAsJsonArray()) {
            if (e == null || e.isJsonNull()) continue;
            if (e.isJsonObject()) {
                JsonObject object = e.getAsJsonObject();
                String name = object.has("name") && object.get("name").isJsonPrimitive()
                        ? object.get("name").getAsString() : null;
                String kind = object.has("kind") && object.get("kind").isJsonPrimitive()
                        ? object.get("kind").getAsString() : null;
                if (name != null && !name.isBlank()) {
                    result.add(new ModpackService.OrphanFile(Kinds.normalize(kind), name));
                }
            } else if (e.isJsonPrimitive()) {
                result.add(new ModpackService.OrphanFile(Kinds.MOD, e.getAsString()));
            }
        }
        return result;
    }

    // ======================================================================
    //  Система предложений
    // ======================================================================

    /** Вид предложения для интерфейса. */
    private Map<String, Object> suggestionView(SuggestionService.Suggestion suggestion) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", suggestion.id);
        item.put("login", suggestion.login);
        item.put("kind", suggestion.kind);
        item.put("source", suggestion.source);
        item.put("projectId", suggestion.projectId);
        item.put("slug", suggestion.slug);
        item.put("title", suggestion.title);
        item.put("iconUrl", suggestion.iconUrl);
        item.put("profileDir", suggestion.profileDir);
        item.put("profileTitle", suggestion.profileTitle);
        item.put("createdAt", suggestion.createdAt);
        return item;
    }

    /** POST /api/mods/suggest — предложить проект в сборку профиля (наблюдатель). */
    private void handleSuggest(WebRequest req, String ip, SessionManager.Session session) {
        if (!config().mods.suggestions.enabled) {
            sendJson(req, HttpResponseStatus.FORBIDDEN, error("suggest_disabled", "Suggestions are disabled"));
            return;
        }
        if (isSuggestBanned(session.login)) {
            sendJson(req, HttpResponseStatus.FORBIDDEN, error("suggest_banned", "You are not allowed to suggest"));
            return;
        }
        String profileDir = req.bodyString("profile");
        String source = req.bodyString("source");
        String projectId = req.bodyString("projectId");
        String kind = Kinds.normalize(req.bodyString("kind"));
        ClientProfile profile = module.modpacks.findProfile(profileDir);
        if (profile == null) {
            sendJson(req, HttpResponseStatus.NOT_FOUND, error("profile_not_found", "Profile not found"));
            return;
        }
        ModsApiProvider provider = module.updates.provider(source);
        if (provider == null || !provider.available()) {
            sendJson(req, HttpResponseStatus.BAD_REQUEST, error("source_unavailable", "Source unavailable"));
            return;
        }
        if (projectId == null || projectId.isBlank()) {
            sendJson(req, HttpResponseStatus.BAD_REQUEST, error("bad_request", "projectId required"));
            return;
        }
        String project = projectId.trim();
        // Ограничение длины идентификатора: без него гигантская строка
        // уходит в URL запроса к источнику (тратится запрос ради гарантированной ошибки)
        if (project.length() > 256) {
            sendJson(req, HttpResponseStatus.BAD_REQUEST, error("bad_request", "projectId too long"));
            return;
        }
        // Уже в сборке — предлагать нечего
        Modpack modpack = module.modpacks.load(profile);
        if (modpack.findByProject(provider.id(), project) != null) {
            sendJson(req, HttpResponseStatus.CONFLICT, error("already_in_modpack", "Already in modpack"));
            return;
        }
        // Уже предложено (кем угодно) — дубликаты не плодим
        if (module.suggestions.isDuplicate(profile.getDir(), provider.id(), project)) {
            sendJson(req, HttpResponseStatus.CONFLICT, error("already_suggested", "Already suggested"));
            return;
        }
        // Лимит ожидающих предложений пользователя
        int limit = config().mods.suggestions.maxPerUser;
        int pending = module.suggestions.pendingCount(session.login);
        if (limit > 0 && pending >= limit) {
            sendJson(req, HttpResponseStatus.TOO_MANY_REQUESTS, error("suggest_limit",
                    "Suggestion limit reached (" + limit + ")"));
            return;
        }
        // Данные проекта — заодно проверяем, что он существует
        ModsApiProvider.ProjectInfo info;
        try {
            info = provider.project(project);
        } catch (Exception e) {
            sendJson(req, HttpResponseStatus.BAD_GATEWAY, error("source_error", String.valueOf(e.getMessage())));
            return;
        }
        SuggestionService.Suggestion suggestion = module.suggestions.create(
                session.login, kind, provider.id(), project,
                info == null ? null : info.slug,
                info == null ? null : info.title,
                info == null ? null : info.iconUrl,
                profile.getDir(), profile.getTitle());
        module.audit.log("suggest_create", ip, session.login,
                profile.getDir() + " " + suggestion.kind + " " + String.valueOf(suggestion.title));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("suggestion", suggestionView(suggestion));
        data.put("pending", module.suggestions.pendingCount(session.login));
        data.put("limit", limit);
        sendJson(req, HttpResponseStatus.OK, ok(data));
    }

    /** GET /api/mods/suggestions?q=&kind= — очередь предложений (админ). */
    private void handleSuggestionsList(WebRequest req) {
        String q = req.query("q");
        String kind = req.query("kind");
        List<SuggestionService.Suggestion> all = module.suggestions.pending();
        List<Map<String, Object>> result = new ArrayList<>();
        for (SuggestionService.Suggestion suggestion : all) {
            if (kind != null && !kind.isBlank()
                    && !Kinds.normalize(kind).equals(suggestion.kind)) {
                continue;
            }
            if (q != null && !q.isBlank()) {
                String needle = q.trim().toLowerCase(Locale.ROOT);
                boolean hit = suggestion.title != null
                        && suggestion.title.toLowerCase(Locale.ROOT).contains(needle);
                if (!hit) {
                    hit = suggestion.login != null
                            && suggestion.login.toLowerCase(Locale.ROOT).contains(needle);
                }
                if (!hit) continue;
            }
            result.add(suggestionView(suggestion));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", result);
        data.put("total", result.size());
        data.put("pendingTotal", all.size());
        data.put("limit", config().mods.suggestions.maxPerUser);
        sendJson(req, HttpResponseStatus.OK, ok(data));
    }

    /** GET /api/mods/suggestions/mine — свои предложения (для наблюдателя). */
    private void handleMySuggestions(WebRequest req, SessionManager.Session session) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (SuggestionService.Suggestion suggestion : module.suggestions.pendingOf(session.login)) {
            result.add(suggestionView(suggestion));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", result);
        data.put("pending", result.size());
        data.put("limit", config().mods.suggestions.maxPerUser);
        data.put("banned", isSuggestBanned(session.login));
        data.put("enabled", config().mods.suggestions.enabled);
        sendJson(req, HttpResponseStatus.OK, ok(data));
    }

    /** POST /api/mods/suggestions/decision — скачать в сборку или отклонить (админ). */
    private void handleSuggestDecision(WebRequest req, String ip, SessionManager.Session session) {
        String id = req.bodyString("id");
        String action = req.bodyString("action");
        if (!"approve".equals(action) && !"reject".equals(action)) {
            sendJson(req, HttpResponseStatus.BAD_REQUEST, error("bad_request", "action must be approve|reject"));
            return;
        }
        SuggestionService.Suggestion suggestion = module.suggestions.find(id);
        if (suggestion == null) {
            sendJson(req, HttpResponseStatus.NOT_FOUND, error("not_found", "Suggestion not found"));
            return;
        }
        if ("reject".equals(action)) {
            module.suggestions.remove(id);
            module.audit.log("suggest_reject", ip, session.login,
                    suggestion.profileDir + " " + suggestion.kind + " " + suggestion.title);
            sendJson(req, HttpResponseStatus.OK, ok(Map.of("rejected", true)));
            return;
        }
        // approve: добавить в модпак как обязательный элемент и скачать файл
        ClientProfile profile = module.modpacks.findProfile(suggestion.profileDir);
        if (profile == null) {
            sendJson(req, HttpResponseStatus.NOT_FOUND, error("profile_not_found",
                    "Profile '" + suggestion.profileDir + "' no longer exists — reject instead"));
            return;
        }
        ModsApiProvider provider = module.updates.provider(suggestion.source);
        if (provider == null || !provider.available()) {
            sendJson(req, HttpResponseStatus.BAD_REQUEST, error("source_unavailable", "Source unavailable"));
            return;
        }
        var lock = module.modpacks.lock(profile.getDir());
        lock.lock();
        try {
            Modpack modpack = module.modpacks.load(profile);
            ModEntry existing = modpack.findByProject(provider.id(), suggestion.projectId);
            ModpackService.AddResult result;
            boolean alreadyThere = existing != null;
            if (alreadyThere) {
                result = new ModpackService.AddResult();
                result.entry = existing;
            } else {
                try {
                    result = module.modpacks.add(profile, modpack, provider, suggestion.projectId,
                            false, null, module.updates, suggestion.kind);
                } catch (IOException e) {
                    // Гонка: проект добавили вручную между предложением и решением —
                    // это не ошибка, а тот же исход «уже в сборке»
                    if (e.getMessage() != null && e.getMessage().contains("already in modpack")) {
                        ModEntry raced = modpack.findByProject(provider.id(), suggestion.projectId);
                        if (raced != null) {
                            result = new ModpackService.AddResult();
                            result.entry = raced;
                            alreadyThere = true;
                        } else {
                            throw e;
                        }
                    } else {
                        throw e;
                    }
                }
            }
            // скачать файл сразу — «скачать в сборку» одной кнопкой.
            // ВАЖНО: раньше ошибка скачивания (нет совместимой версии, HTTP-сбой,
            // канал release при beta-файле и т.п.) ГЛОТАЛАСЬ молча — админ видел
            // зелёный тост «скачано», а файл не появлялся нигде. Теперь результат
            // проверяется и возвращается админу (downloadError в ответе + в аудит).
            // Для записи «уже в сборке» без файла на диске кнопка тоже доставляет
            // файл — иначе одобрение превращается в пустышку.
            String downloadError = null;
            if (!alreadyThere || !hasInstalledFile(profile, modpack, result.entry)) {
                try {
                    List<UpdateService.InstallResult> installed = module.updates.updateMods(
                            profile, modpack, module.modpacks, Set.of(result.entry.id), true);
                    for (UpdateService.InstallResult ir : installed) {
                        if (ir != null && ir.error != null && !ir.error.isBlank()) {
                            downloadError = ir.error;
                        }
                    }
                } catch (Exception e) {
                    downloadError = String.valueOf(e.getMessage());
                    module.logger.warn("Suggest approve: download failed", e);
                }
            }
            if (config().mods.autoRehash) {
                try {
                    module.modpacks.rehash(profile.getDir());
                } catch (Exception e) {
                    module.logger.warn("Rehash failed", e);
                }
            }
            module.suggestions.remove(id);
            Map<String, Object> auditDetails = new LinkedHashMap<>();
            auditDetails.put("author", String.valueOf(suggestion.login));
            auditDetails.put("entry", String.valueOf(result.entry.id));
            if (downloadError != null) {
                auditDetails.put("downloadError", downloadError);
            }
            module.audit.log("suggest_approve", ip, session.login,
                    profile.getDir() + " " + suggestion.kind + " " + suggestion.title, auditDetails);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("approved", true);
            data.put("alreadyInModpack", alreadyThere);
            if (downloadError != null) {
                data.put("downloadError", downloadError);
            }
            data.put("entry", modView(profile, modpack, result.entry));
            sendJson(req, HttpResponseStatus.OK, ok(data));
        } catch (Exception e) {
            sendJson(req, HttpResponseStatus.BAD_REQUEST, error("approve_failed", String.valueOf(e.getMessage())));
        } finally {
            lock.unlock();
        }
    }

    /** Есть ли у записи установленный файл на диске (для кнопки «Скачать в сборку»). */
    private boolean hasInstalledFile(ClientProfile profile, Modpack modpack, ModEntry entry) {
        if (entry == null || entry.installedFileName == null || entry.installedFileName.isBlank()) return false;
        Path file = module.modpacks.kindDir(profile, modpack, entry.kindOrMod())
                .resolve(UpdateService.sanitizeFileName(entry.installedFileName));
        return Files.isRegularFile(file);
    }

    // ======================================================================
    //  Хэши (кнопка «Обновить хэши»)
    // ======================================================================

    private void handleRehash(WebRequest req, String ip, SessionManager.Session session) {
        String profileDir = req.bodyString("profile");
        boolean all = profileDir == null || profileDir.isBlank() || "all".equals(profileDir);
        try {
            if (all) {
                module.modpacks.rehash(null);
                module.audit.log("rehash", ip, session.login, "all");
                sendJson(req, HttpResponseStatus.OK, ok(Map.of("rehashed", "all")));
            } else {
                ClientProfile profile = module.modpacks.findProfile(profileDir);
                if (profile == null) {
                    sendJson(req, HttpResponseStatus.NOT_FOUND, error("profile_not_found", "Profile not found"));
                    return;
                }
                module.modpacks.rehash(profile.getDir());
                module.audit.log("rehash", ip, session.login, profileDir);
                sendJson(req, HttpResponseStatus.OK, ok(Map.of("rehashed", profileDir)));
            }
        } catch (Exception e) {
            sendJson(req, HttpResponseStatus.INTERNAL_SERVER_ERROR, error("rehash_failed", String.valueOf(e.getMessage())));
        }
    }

    // ======================================================================
    //  Безопасность (панель админа)
    // ======================================================================

    private void handleBans(WebRequest req) {
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> bans = new LinkedHashMap<>();
        module.ipBans.getActiveBans().forEach((ip, info) -> {
            bans.put(ip, Map.of("until", info[0], "level", info[1], "totalFails", info[2]));
        });
        data.put("ipBans", bans);
        Map<String, Object> locks = new LinkedHashMap<>();
        module.ipBans.getActiveAccountLocks().forEach((login, info) -> {
            locks.put(login, Map.of("until", info[0], "level", info[1]));
        });
        data.put("accountLocks", locks);
        data.put("whitelist", module.ipBans.getWhitelist());
        data.put("blacklist", module.ipBans.getBlacklist());
        sendJson(req, HttpResponseStatus.OK, ok(data));
    }

    private void handleUnban(WebRequest req, String ip, SessionManager.Session session) {
        String target = req.bodyString("ip");
        String login = req.bodyString("login");
        Map<String, Object> data = new LinkedHashMap<>();
        if (target != null && !target.isBlank()) {
            data.put("unbanned", module.ipBans.unban(target));
            module.audit.log("unban", ip, session.login, target);
        }
        if (login != null && !login.isBlank()) {
            data.put("unlocked", module.ipBans.unlockAccount(login));
            module.audit.log("unlock_account", ip, session.login, login);
        }
        sendJson(req, HttpResponseStatus.OK, ok(data));
    }

    private void handleIpFilterGet(WebRequest req) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("whitelist", module.ipBans.getWhitelist());
        data.put("blacklist", module.ipBans.getBlacklist());
        sendJson(req, HttpResponseStatus.OK, ok(data));
    }

    private void handleIpFilterPost(WebRequest req, String ip, SessionManager.Session session) {
        String action = req.bodyString("action");
        String list = req.bodyString("list");
        String target = req.bodyString("ip");
        if (!"add".equals(action) && !"remove".equals(action)) {
            sendJson(req, HttpResponseStatus.BAD_REQUEST, error("bad_request", "action must be add|remove"));
            return;
        }
        boolean ok;
        if ("white".equals(list)) {
            ok = "add".equals(action) ? module.ipBans.whitelist(target) : module.ipBans.unwhitelist(target);
        } else if ("black".equals(list)) {
            ok = "add".equals(action) ? module.ipBans.blacklist(target) : module.ipBans.unblacklist(target);
        } else {
            sendJson(req, HttpResponseStatus.BAD_REQUEST, error("bad_request", "list must be white|black"));
            return;
        }
        module.audit.log("ipfilter", ip, session.login, action + " " + list + " " + target);
        module.saveConfig(); // сохранить изменения и в конфиге (source of truth для списков)
        sendJson(req, HttpResponseStatus.OK, ok(Map.of("success", ok)));
    }

    private void handleAudit(WebRequest req) {
        int offset = parseInt(req.query("offset"), 0);
        int limit = Math.min(200, parseInt(req.query("limit"), 50));
        List<String> lines = module.audit.readLast(offset, limit);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("lines", lines);
        data.put("offset", offset);
        data.put("limit", limit);
        sendJson(req, HttpResponseStatus.OK, ok(data));
    }

    private void handleUsersList(WebRequest req) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ModsSiteConfig.AccountConfig account : config().accounts) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("login", account.login);
            item.put("role", account.role);
            item.put("enabled", account.enabled);
            item.put("totp", account.totpSecret != null && !account.totpSecret.isBlank());
            item.put("source", "local");
            result.add(item);
        }
        sendJson(req, HttpResponseStatus.OK, ok(result));
    }

    private void handleUserPassword(WebRequest req, String ip, SessionManager.Session session) {
        String login = req.bodyString("login");
        String newPassword = req.bodyString("newPassword");
        if (login == null || newPassword == null || newPassword.length() < 6) {
            sendJson(req, HttpResponseStatus.BAD_REQUEST, error("bad_request", "Password too short (min 6)"));
            return;
        }
        for (ModsSiteConfig.AccountConfig account : config().accounts) {
            if (account.login != null && account.login.equalsIgnoreCase(login)) {
                account.password = null;
                account.passwordHash = pro.gravit.launchermodules.modssite.auth.PasswordHasher
                        .hash(newPassword, 210_000);
                module.saveConfig();
                module.sessions.deleteAllFor(account.login);
                module.audit.log("password_change", ip, session.login, login);
                sendJson(req, HttpResponseStatus.OK, ok(Map.of("changed", true)));
                return;
            }
        }
        sendJson(req, HttpResponseStatus.NOT_FOUND, error("user_not_found", "Local account not found"));
    }

    private static int parseInt(String s, int def) {
        if (s == null) return def;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ======================================================================
    //  Ответы
    // ======================================================================

    private static Map<String, Object> ok(Object data) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("data", data);
        return result;
    }

    private static Map<String, Object> error(String code, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        Map<String, String> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        result.put("error", error);
        return result;
    }

    private void sendJson(WebRequest req, HttpResponseStatus status, Object payload) {
        sendJsonWithCookie(req, status, payload, null);
    }

    private void sendJsonWithCookie(WebRequest req, HttpResponseStatus status, Object payload, String cookie) {
        DefaultFullHttpResponse response = jsonResponse(status, payload);
        if (cookie != null) {
            response.headers().set(HttpHeaderNames.SET_COOKIE, cookie);
        }
        applySecurityHeaders(response);
        req.ctx.writeAndFlush(response).addListener(io.netty.channel.ChannelFutureListener.CLOSE);
    }

    /** Минимальная страница «Сайт закрыт» (сайт выключен командой mods site off). */
    private void sendDisabledPage(WebRequest req) {
        String html = "<!DOCTYPE html><html lang=\"ru\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>Сайт закрыт / Site closed</title><style>"
                + "body{font-family:system-ui,-apple-system,'Segoe UI',sans-serif;display:flex;"
                + "align-items:center;justify-content:center;min-height:100vh;margin:0;"
                + "background:#0f172a;color:#e2e8f0;text-align:center}"
                + "main{padding:2rem;max-width:36rem}h1{font-size:1.5rem;margin:0 0 .5rem}"
                + "p{color:#94a3b8;margin:.3rem 0;font-size:.95rem}"
                + "hr{border:0;border-top:1px solid #1e293b;margin:1.4rem auto;width:60%}"
                + "</style></head><body><main>"
                + "<h1>Сайт временно закрыт</h1>"
                + "<p>Администратор выключил панель управления модами.</p>"
                + "<p>Работа лаунчера и сервера не затронута.</p>"
                + "<hr>"
                + "<h1>Site temporarily closed</h1>"
                + "<p>The administrator has disabled the mod management panel.</p>"
                + "<p>Launcher and server operation are not affected.</p>"
                + "</main></body></html>";
        byte[] bytes = html.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.SERVICE_UNAVAILABLE, Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=utf-8");
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-store");
        applySecurityHeaders(response);
        req.ctx.writeAndFlush(response).addListener(io.netty.channel.ChannelFutureListener.CLOSE);
    }

    private DefaultFullHttpResponse jsonResponse(HttpResponseStatus status, Object payload) {
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
                Unpooled.wrappedBuffer(Launcher.gsonManager.gson.toJson(payload).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON);
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-store");
        return response;
    }

    private String sessionCookie(WebRequest req, String token, int maxAgeSeconds) {
        StringBuilder sb = new StringBuilder();
        sb.append(config().auth.cookieName).append('=').append(token);
        sb.append("; Path=").append(module.mountPath.isEmpty() || "/".equals(module.mountPath) ? "/" : module.mountPath);
        sb.append("; HttpOnly");
        String sameSite = config().web.cookieSameSite;
        if (sameSite != null && !sameSite.isBlank()) {
            sb.append("; SameSite=").append(sameSite);
        }
        // Secure: auto (по факту HTTPS текущего соединения), always или never
        boolean secure = switch (config().web.cookieSecure == null ? "auto" : config().web.cookieSecure.toLowerCase()) {
            case "always" -> true;
            case "never" -> false;
            default -> req.isHttps();
        };
        if (secure) sb.append("; Secure");
        if (maxAgeSeconds > 0) {
            sb.append("; Max-Age=").append(maxAgeSeconds);
        } else {
            sb.append("; Max-Age=0");
        }
        return sb.toString();
    }

    private void applySecurityHeaders(FullHttpResponse response) {
        if (config().web.securityHeaders) {
            io.netty.handler.codec.http.HttpHeaders headers = response.headers();
            headers.set("X-Content-Type-Options", "nosniff");
            headers.set("X-Frame-Options", "DENY");
            headers.set("Referrer-Policy", "no-referrer");
            StringBuilder csp = new StringBuilder(
                    "default-src 'self'; img-src 'self' https: data:; style-src 'self' 'unsafe-inline'; "
                            + "script-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'");
            if (config().web.cspExtra != null && !config().web.cspExtra.isBlank()) {
                csp.append("; ").append(config().web.cspExtra);
            }
            headers.set("Content-Security-Policy", csp.toString());
        }
    }
}
