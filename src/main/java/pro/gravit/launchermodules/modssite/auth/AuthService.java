package pro.gravit.launchermodules.modssite.auth;

import pro.gravit.launchermodules.modssite.ModsSiteConfig;
import pro.gravit.launchermodules.modssite.security.IpBanManager;

/**
 * Оркестрация входа: локальные аккаунты + аккаунты лаунчера (гибрид),
 * TOTP, блокировки по IP и по логину, режим «только админы».
 */
public class AuthService {
    public static class AuthResult {
        public enum Status {
            OK, WRONG_CREDENTIALS, NEED_TOTP, WRONG_TOTP, ACCOUNT_DISABLED,
            NOT_ADMIN, IP_BANNED, IP_BLACKLISTED, ACCOUNT_LOCKED, RATE_LIMITED, DISABLED, ERROR
        }

        public final Status status;
        public final String login;
        public final String source; // "local" | "launcher"
        public final String role;   // "admin" | "viewer"
        public final String banUntilIso;
        public final String message;

        private AuthResult(Status status, String login, String source, String role, String banUntilIso, String message) {
            this.status = status;
            this.login = login;
            this.source = source;
            this.role = role;
            this.banUntilIso = banUntilIso;
            this.message = message;
        }

        static AuthResult ok(String login, String source, String role) {
            return new AuthResult(Status.OK, login, source, role, null, null);
        }

        static AuthResult of(Status status) {
            return new AuthResult(status, null, null, null, null, null);
        }

        static AuthResult banned(long until) {
            return new AuthResult(Status.IP_BANNED, null, null, null,
                    java.time.Instant.ofEpochMilli(until).toString(), null);
        }

        static AuthResult error(String message) {
            return new AuthResult(Status.ERROR, null, null, null, null, message);
        }
    }

    private final ModsSiteConfig config;
    private final IpBanManager ipBans;
    private final LauncherAuthChecker launcherAuth;

    public AuthService(ModsSiteConfig config, IpBanManager ipBans, LauncherAuthChecker launcherAuth) {
        this.config = config;
        this.ipBans = ipBans;
        this.launcherAuth = launcherAuth;
    }

    /**
     * Попытка входа. ipBanned/locked/rateLimited должны проверяться вызывающим кодом
     * ДО вызова (см. ModsWebSeverlet).
     *
     * @param login     логин
     * @param password  пароль (открытый)
     * @param totpCode  код 2FA или null
     */
    public AuthResult login(String login, char[] password, String totpCode, String ip) {
        String mode = config.auth.mode == null ? "hybrid" : config.auth.mode.trim().toLowerCase();
        try {
            switch (mode) {
                case "local" -> {
                    return loginLocal(login, password, totpCode, ip, false);
                }
                case "launcher" -> {
                    return loginLauncherOnly(login, password, ip);
                }
                default -> {
                    return loginHybrid(login, password, totpCode, ip);
                }
            }
        } catch (Exception e) {
            return AuthResult.error(e.getMessage());
        }
    }

    private AuthResult loginHybrid(String login, char[] password, String totpCode, String ip) {
        ModsSiteConfig.AccountConfig local = findLocal(login);
        if (local != null) {
            return loginLocal(login, password, totpCode, ip, true);
        }
        return loginLauncher(login, password, totpCode, ip);
    }

    private AuthResult loginLocal(String login, char[] password, String totpCode, String ip, boolean hybrid) {
        ModsSiteConfig.AccountConfig account = findLocal(login);
        if (account == null || account.login == null) {
            return AuthResult.of(AuthResult.Status.WRONG_CREDENTIALS);
        }
        if (!account.enabled) {
            return AuthResult.of(AuthResult.Status.ACCOUNT_DISABLED);
        }
        boolean passwordOk;
        if (account.passwordHash != null && !account.passwordHash.isEmpty()) {
            passwordOk = PasswordHasher.verify(new String(password), account.passwordHash);
        } else if (account.password != null && !account.password.isEmpty()) {
            passwordOk = Utils2.constantTimeEquals(account.password, new String(password));
        } else {
            passwordOk = false;
        }
        if (!passwordOk) {
            return AuthResult.of(AuthResult.Status.WRONG_CREDENTIALS);
        }
        boolean superAdmin = isSuperAdmin(account.login);
        String role = "admin".equalsIgnoreCase(account.role) || superAdmin ? "admin" : "viewer";
        // TOTP для локальных аккаунтов
        if (config.auth.totpEnabled && account.totpSecret != null && !account.totpSecret.isBlank()) {
            if (totpCode == null || totpCode.isBlank()) {
                return AuthResult.of(AuthResult.Status.NEED_TOTP);
            }
            if (!TotpProvider.verify(totpCode, account.totpSecret)) {
                return AuthResult.of(AuthResult.Status.WRONG_TOTP);
            }
        }
        if (config.auth.onlyAdminsCanLogin && !"admin".equals(role)) {
            return AuthResult.of(AuthResult.Status.NOT_ADMIN);
        }
        return AuthResult.ok(account.login, "local", role);
    }

    private AuthResult loginLauncherOnly(String login, char[] password, String ip) {
        AuthResult result = loginLauncher(login, password, null, ip);
        if (result.status == AuthResult.Status.OK) {
            return result;
        }
        return result;
    }

    private AuthResult loginLauncher(String login, char[] password, String totpCode, String ip) {
        LauncherAuthChecker.Result check = launcherAuth.check(login, password);
        switch (check.status) {
            case WRONG_PASSWORD, USER_NOT_FOUND -> {
                return AuthResult.of(AuthResult.Status.WRONG_CREDENTIALS);
            }
            case DENIED -> {
                return AuthResult.of(AuthResult.Status.NOT_ADMIN);
            }
            case ERROR -> {
                return AuthResult.error(check.error);
            }
            case OK -> {
                break;
            }
            default -> {
                return AuthResult.of(AuthResult.Status.WRONG_CREDENTIALS);
            }
        }
        boolean superAdmin = isSuperAdmin(login);
        String role = check.admin || superAdmin ? "admin" : "viewer";
        if ("deny".equalsIgnoreCase(config.launcherAuth.treatNonAdminAs) && !check.admin && !superAdmin) {
            return AuthResult.of(AuthResult.Status.NOT_ADMIN);
        }
        // Опциональный TOTP для аккаунтов лаунчера (extraTotp)
        String extraSecret = config.launcherAuth.extraTotp == null ? null
                : config.launcherAuth.extraTotp.get(login);
        if (config.auth.totpEnabled && extraSecret != null && !extraSecret.isBlank()) {
            if (totpCode == null || totpCode.isBlank()) {
                return AuthResult.of(AuthResult.Status.NEED_TOTP);
            }
            if (!TotpProvider.verify(totpCode, extraSecret)) {
                return AuthResult.of(AuthResult.Status.WRONG_TOTP);
            }
        }
        if (config.auth.onlyAdminsCanLogin && !"admin".equals(role)) {
            return AuthResult.of(AuthResult.Status.NOT_ADMIN);
        }
        return AuthResult.ok(login, "launcher", role);
    }

    private ModsSiteConfig.AccountConfig findLocal(String login) {
        if (login == null || config.accounts == null) return null;
        for (ModsSiteConfig.AccountConfig account : config.accounts) {
            if (account != null && account.login != null
                    && account.login.equalsIgnoreCase(login)) {
                return account;
            }
        }
        return null;
    }

    /** Суперадмин задан вручную в config.superAdmins — действует независимо
     *  от источника аккаунта (локальная запись или БД лаунчера), без учёта регистра. */
    private boolean isSuperAdmin(String login) {
        String lower = login == null ? null : login.trim().toLowerCase(java.util.Locale.ROOT);
        if (lower == null || lower.isEmpty() || config.superAdmins == null) {
            return false;
        }
        for (String candidate : config.superAdmins) {
            if (candidate == null) continue;
            if (candidate.trim().toLowerCase(java.util.Locale.ROOT).equals(lower)) return true;
        }
        return false;
    }

    /** Утилита сравнения строк постоянным временем. */
    private static final class Utils2 {
        static boolean constantTimeEquals(String a, String b) {
            return java.security.MessageDigest.isEqual(
                    a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
