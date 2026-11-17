package pro.gravit.launchermodules.modssite.commands;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pro.gravit.launchermodules.modssite.ModsSiteModule;
import pro.gravit.launchserver.LaunchServer;
import pro.gravit.launchserver.command.Command;

public class StatusCommand extends Command {
    private static final Logger logger = LogManager.getLogger(StatusCommand.class);
    private final ModsSiteModule module;

    public StatusCommand(LaunchServer server, ModsSiteModule module) {
        super(server);
        this.module = module;
    }

    @Override
    public String getArgsDescription() {
        return "[]";
    }

    @Override
    public String getUsageDescription() {
        return "Show module status";
    }

    @Override
    public void invoke(String... args) {
        logger.info("=== ModsSite module v{} ===", module.versionString);
        logger.info("Enabled: {}", module.enabled);
        logger.info("Mount path: {}", module.mountPath);
        logger.info("Web UI: {} | API: {} | title: '{}'",
                module.config.web.uiEnabled, module.config.web.apiEnabled, module.config.web.title);
        logger.info("Auth mode: {} | onlyAdminsCanLogin: {}",
                module.config.auth.mode, module.config.auth.onlyAdminsCanLogin);
        logger.info("Local accounts: {} ({})", module.config.accounts.size(),
                module.config.accounts.stream().map(a -> a.login + (a.enabled ? "" : " [off]"))
                        .reduce((a, b) -> a + ", " + b).orElse("-"));
        logger.info("Modrinth: {} | CurseForge: {}",
                module.updates.provider("modrinth") != null && module.updates.provider("modrinth").available()
                        ? "enabled" : "disabled",
                module.updates.provider("curseforge") != null && module.updates.provider("curseforge").available()
                        ? "enabled (apiKey set)" : "disabled (no apiKey)");
        logger.info("Brute-force protection: {} (schedule {} min) | Account lock: {} | Rate limit: {} | TOTP: {} | Audit: {}",
                module.config.security.enabled ? "on" : "off",
                module.config.security.banScheduleMinutes,
                module.config.security.accountLock.enabled ? "on" : "off",
                module.config.rateLimit.enabled ? "on" : "off",
                module.config.auth.totpEnabled ? "on" : "off",
                module.config.audit.enabled ? "on" : "off");
        logger.info("Whitelist: {} | Blacklist: {}",
                module.ipBans.getWhitelist(), module.ipBans.getBlacklist());
        logger.info("Active sessions: {}", module.sessions.count());
        if (!module.modpacks.rehashSupported()) {
            logger.warn("profilesProvider is not local: rehash unavailable");
        }
    }
}
