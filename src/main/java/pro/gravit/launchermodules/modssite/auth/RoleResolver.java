package pro.gravit.launchermodules.modssite.auth;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pro.gravit.launchermodules.modssite.ModsSiteConfig;
import pro.gravit.launchermodules.modssite.ModsSiteModule;

import java.util.List;

/**
 * «Живые» роли: признак админа пересчитывается на КАЖДОМ запросе, а не
 * запоминается в сессии при входе.
 *
 *  - суперадмин: логин перечислен вручную в config.superAdmins
 *    (конфиг-файл модуля); действует независимо от источника аккаунта —
 *    локальная запись или БД лаунчера;
 *  - «наблюдатель» (viewer): оверрайд из панели Безопасность -> Роли
 *    (файл admin-roles.json), запись админа в конфиге/БД не трогается;
 *  - базовая роль: для локальных аккаунтов — свежий config.accounts
 *    (конфиг перечитывается автоматически при изменении файла);
 *    для аккаунтов лаунчера — свежая запись из auth core + adminCheck:
 *    назначил админа в БД (или в правах лаунчера), перезагрузил страницу —
 *    уже админ; снял админа — права исчезают при следующем запросе.
 *
 * При недоступности auth core (сбой БД) возвращается прежняя роль сессии,
 * чтобы кратковременная ошибка не разлогинивала всех админов; явное снятие
 * админа (USER_NOT_FOUND) или выключение локального аккаунта применяется сразу.
 */
public class RoleResolver {
    public static final String ROLE_SUPERADMIN = "superadmin";
    public static final String ROLE_ADMIN = "admin";
    public static final String ROLE_VIEWER = "viewer";

    private static final Logger logger = LogManager.getLogger(RoleResolver.class);

    private final ModsSiteModule module;

    public RoleResolver(ModsSiteModule module) {
        this.module = module;
    }

    /** Суперадмин по конфигу (сравнение без учёта регистра). */
    public boolean isSuperAdmin(String login) {
        String lower = AdminRoleOverrides.normalize(login);
        if (lower == null || lower.isEmpty()) return false;
        List<String> superAdmins = module.config == null ? null : module.config.superAdmins;
        if (superAdmins == null) return false;
        for (String candidate : superAdmins) {
            if (candidate == null) continue;
            if (AdminRoleOverrides.normalize(candidate).equals(lower)) return true;
        }
        return false;
    }

    /** Пересчитать роль сессии и обновить session.role (живая проверка). */
    public void refresh(SessionManager.Session session) {
        if (session == null || session.login == null) return;
        session.role = effectiveRole(session.login, session.source, session.role);
    }

    /**
     * Актуальная роль логина. fallback — прежняя роль сессии; используется
     * только если источник данных сейчас недоступен (fail-open для доступности).
     */
    public String effectiveRole(String login, String source, String fallback) {
        if (isSuperAdmin(login)) return ROLE_SUPERADMIN;
        AdminRoleOverrides overrides = module.adminRoles;
        if (overrides != null && overrides.isForcedViewer(login)) return ROLE_VIEWER;
        if ("local".equals(source)) {
            return localRole(login);
        }
        return launcherRole(login, fallback);
    }

    /** Базовая роль локального аккаунта: живой config.accounts. */
    private String localRole(String login) {
        ModsSiteConfig.AccountConfig account = findLocal(login);
        if (account == null || !account.enabled) {
            // Аккаунт удалили или выключили — админские права снимаются сразу.
            return ROLE_VIEWER;
        }
        return ROLE_ADMIN.equalsIgnoreCase(account.role) ? ROLE_ADMIN : ROLE_VIEWER;
    }

    /** Базовая роль аккаунта лаунчера: свежая запись из auth core + adminCheck. */
    private String launcherRole(String login, String fallback) {
        LauncherAuthChecker checker = module.launcherAuthChecker;
        if (checker == null) return fallback == null ? ROLE_VIEWER : fallback;
        LauncherAuthChecker.Result result = checker.checkAdminOnly(login);
        return switch (result.status) {
            case OK -> result.admin ? ROLE_ADMIN : ROLE_VIEWER;
            case ERROR -> {
                logger.debug("Live admin check unavailable for '{}' — keeping session role", login);
                yield fallback == null ? ROLE_VIEWER : fallback;
            }
            default -> ROLE_VIEWER; // USER_NOT_FOUND и прочее: аккаунта больше нет
        };
    }

    private ModsSiteConfig.AccountConfig findLocal(String login) {
        if (login == null || module.config == null || module.config.accounts == null) return null;
        for (ModsSiteConfig.AccountConfig account : module.config.accounts) {
            if (account != null && account.login != null
                    && account.login.equalsIgnoreCase(login)) {
                return account;
            }
        }
        return null;
    }

    /** Является ли роль «админской» (admin или superadmin). */
    public static boolean isAdminRole(String role) {
        return ROLE_ADMIN.equals(role) || ROLE_SUPERADMIN.equals(role);
    }
}
