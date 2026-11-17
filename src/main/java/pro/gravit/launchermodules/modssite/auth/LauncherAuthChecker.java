package pro.gravit.launchermodules.modssite.auth;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pro.gravit.launcher.base.ClientPermissions;
import pro.gravit.launcher.base.request.auth.password.AuthPlainPassword;
import pro.gravit.launcher.base.request.auth.AuthRequest;
import pro.gravit.launchserver.LaunchServer;
import pro.gravit.launchserver.auth.AuthException;
import pro.gravit.launchserver.auth.AuthProviderPair;
import pro.gravit.launchserver.auth.core.AuthCoreProvider;
import pro.gravit.launchserver.auth.core.User;
import pro.gravit.launchermodules.modssite.ModsSiteConfig;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

/**
 * Проверка учётных данных аккаунтов лаунчера и определение «админа»:
 *  1) тип permissions — по правам аккаунта (perms/roles);
 *  2) тип sql         — собственный запрос к БД авторизации (столбец isAdmin и т.п.);
 *  3) тип none        — аккаунты лаунчера не админы.
 */
public class LauncherAuthChecker {
    private static final Logger logger = LogManager.getLogger(LauncherAuthChecker.class);

    public static class Result {
        public enum Status {OK, USER_NOT_FOUND, WRONG_PASSWORD, DENIED, ERROR}

        public final Status status;
        public final String username;
        public final boolean admin;
        public final String error;

        private Result(Status status, String username, boolean admin, String error) {
            this.status = status;
            this.username = username;
            this.admin = admin;
            this.error = error;
        }

        static Result ok(String username, boolean admin) {
            return new Result(Status.OK, username, admin, null);
        }

        static Result of(Status status) {
            return new Result(status, null, false, null);
        }

        static Result error(String message) {
            return new Result(Status.ERROR, null, false, message);
        }
    }

    private final LaunchServer server;
    private final ModsSiteConfig config;
    private volatile String cachedSqlUrl;
    private volatile String cachedSqlUser;
    private volatile String cachedSqlPassword;

    public LauncherAuthChecker(LaunchServer server, ModsSiteConfig config) {
        this.server = server;
        this.config = config;
    }

    public AuthProviderPair resolvePair() {
        String authId = config.launcherAuth.authId;
        if (authId != null && !authId.isBlank()) {
            AuthProviderPair pair = server.config.getAuthProviderPair(authId);
            if (pair == null) {
                throw new IllegalStateException("AuthProviderPair '" + authId + "' not found in LaunchServer.json");
            }
            return pair;
        }
        return server.config.getAuthProviderPair();
    }

    /** Проверить логин/пароль через auth-провайдер лаунчера. */
    public Result check(String login, char[] password) {
        AuthProviderPair pair;
        try {
            pair = resolvePair();
        } catch (Throwable e) {
            return Result.error(e.getMessage());
        }
        if (pair == null || pair.core == null) {
            return Result.error("auth core not available");
        }
        AuthCoreProvider core = pair.core;
        try {
            // Полная проверка пароля (те же пути, что и вход в лаунчер)
            core.authorize(login, null, new AuthPlainPassword(new String(password)), false);
        } catch (AuthException e) {
            String message = String.valueOf(e.getMessage());
            if (message != null && message.contains("wrong password")) {
                return Result.of(Result.Status.WRONG_PASSWORD);
            }
            return Result.of(Result.Status.WRONG_PASSWORD);
        } catch (Exception e) {
            logger.error("Launcher auth check failed for '{}'", login, e);
            return Result.error("auth core error");
        }
        User user;
        try {
            user = core.getUserByUsername(login);
        } catch (Throwable e) {
            user = null;
        }
        if (user == null) {
            return Result.of(Result.Status.USER_NOT_FOUND);
        }
        boolean admin = isAdmin(user);
        return Result.ok(user.getUsername(), admin);
    }

    /** Определить признак админа согласно конфигу. */
    public boolean isAdmin(User user) {
        String type = config.launcherAuth.adminCheck.type == null
                ? "permissions" : config.launcherAuth.adminCheck.type.trim().toLowerCase();
        switch (type) {
            case "permissions" -> {
                return isAdminByPermissions(user);
            }
            case "sql" -> {
                return isAdminBySql(user);
            }
            default -> {
                return false;
            }
        }
    }

    private boolean isAdminByPermissions(User user) {
        ClientPermissions permissions = user.getPermissions();
        if (permissions == null) return false;
        String perm = config.launcherAuth.adminCheck.permission;
        if (perm != null && !perm.isBlank() && permissions.hasPerm(perm)) {
            return true;
        }
        String role = config.launcherAuth.adminCheck.role;
        return role != null && !role.isBlank() && permissions.hasRole(role);
    }

    private boolean isAdminBySql(User user) {
        ModsSiteConfig.SqlAdminCheck sql = config.launcherAuth.adminCheck.sql;
        if (sql == null || sql.query == null || sql.query.isBlank()) {
            logger.warn("sql adminCheck: query is empty");
            return false;
        }
        String url = sql.url;
        String dbUser = sql.user;
        String dbPassword = sql.password;
        if (url == null || url.isBlank()) {
            // Пробуем переиспользовать соединение первого SQL-провайдера лаунчера
            if (cachedSqlUrl == null) {
                fillFromLauncherSqlSource();
            }
            url = cachedSqlUrl;
            dbUser = cachedSqlUser;
            dbPassword = cachedSqlPassword;
        }
        if (url == null || url.isBlank()) {
            logger.warn("sql adminCheck: no jdbc url (set launcherAuth.adminCheck.sql.url)");
            return false;
        }
        try (Connection connection = DriverManager.getConnection(url, dbUser, dbPassword);
             PreparedStatement statement = connection.prepareStatement(sql.query)) {
            List<String> params = sql.params;
            if (params != null) {
                for (int i = 0; i < params.size(); i++) {
                    statement.setString(i + 1, resolveParam(params.get(i), user));
                }
            }
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    String value = rs.getString(1);
                    return value != null && sql.trueValues != null
                            && sql.trueValues.stream().anyMatch(v -> v.equalsIgnoreCase(value.trim()));
                }
            }
            return false;
        } catch (Exception e) {
            logger.error("sql adminCheck failed for '{}'", user.getUsername(), e);
            return false;
        }
    }

    private String resolveParam(String param, User user) {
        if (param == null) return null;
        return param.replace("{login}", String.valueOf(user.getUsername()))
                .replace("{uuid}", String.valueOf(user.getUUID()))
                .replace("{username}", String.valueOf(user.getUsername()));
    }

    /** Рефлексивно достаём jdbcUrl/username/password из SQL-провайдера лаунчера. */
    private void fillFromLauncherSqlSource() {
        try {
            outer:
            for (AuthProviderPair pair : server.config.auth.values()) {
                if (pair == null || pair.core == null) continue;
                Class<?> clazz = pair.core.getClass();
                while (clazz != null && clazz != Object.class) {
                    for (Field field : clazz.getDeclaredFields()) {
                        if (field.getName().equals("sqlSource")) {
                            field.setAccessible(true);
                            Object source = field.get(pair.core);
                            if (source != null) {
                                cachedSqlUrl = readStringField(source, "jdbcUrl", "url");
                                cachedSqlUser = readStringField(source, "username", "user");
                                cachedSqlPassword = readStringField(source, "password");
                                if (cachedSqlUrl != null) {
                                    logger.info("sql adminCheck: reusing auth core connection ({}...)",
                                            cachedSqlUrl.substring(0, Math.min(30, cachedSqlUrl.length())));
                                    break outer;
                                }
                            }
                        }
                    }
                    clazz = clazz.getSuperclass();
                }
            }
        } catch (Throwable e) {
            logger.debug("Cannot reuse launcher SQL source", e);
        }
        if (cachedSqlUrl == null) {
            logger.warn("sql adminCheck: launcher SQL source not found; set launcherAuth.adminCheck.sql.url manually");
        }
    }

    private static String readStringField(Object target, String... names) {
        for (String name : names) {
            try {
                Field field = target.getClass().getDeclaredField(name);
                field.setAccessible(true);
                Object value = field.get(target);
                if (value instanceof String s) return s;
            } catch (NoSuchFieldException | IllegalAccessException ignored) {
            }
            Class<?> clazz = target.getClass();
            while (clazz != null && clazz != Object.class) {
                try {
                    Field field = clazz.getDeclaredField(name);
                    field.setAccessible(true);
                    Object value = field.get(target);
                    if (value instanceof String s) return s;
                } catch (NoSuchFieldException | IllegalAccessException ignored) {
                }
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }
}
