package pro.gravit.launchermodules.modssite.commands;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pro.gravit.launchermodules.modssite.ModsSiteModule;
import pro.gravit.launchserver.LaunchServer;
import pro.gravit.launchserver.command.Command;

/**
 * Выключатель сайта (аварийный режим «закрыто»).
 *
 * <pre>
 *   mods site          — статус (включён/выключен)
 *   mods site on       — включить сайт (то же: enable)
 *   mods site off      — выключить сайт: ВСЕ запросы к /mods (страницы и API)
 *                        получают 503, все сессии сбрасываются
 *                        (то же: disable)
 * </pre>
 *
 * Состояние сохраняется в config/ModsSite/ModsSiteConfig.json (enabled: true/false)
 * и ПЕРЕЖИВАЕТ перезапуск LaunchServer: выключенный сайт остаётся выключенным,
 * пока администратор не включит его обратно командой или правкой конфига.
 */
public class SiteCommand extends Command {
    private static final Logger logger = LogManager.getLogger(SiteCommand.class);
    private final ModsSiteModule module;

    public SiteCommand(LaunchServer server, ModsSiteModule module) {
        super(server);
        this.module = module;
    }

    @Override
    public String getArgsDescription() {
        return "[on|off|status]";
    }

    @Override
    public String getUsageDescription() {
        return "Site switch: enable / disable / show status (persists in config)";
    }

    @Override
    public void invoke(String... args) {
        String action = args.length > 0 ? args[0].toLowerCase() : "status";
        switch (action) {
            case "on":
            case "enable":
            case "вкл":
            case "включить":
                setEnabled(true);
                break;
            case "off":
            case "disable":
            case "выкл":
            case "выключить":
                setEnabled(false);
                break;
            case "status":
            case "статус":
            default:
                status();
                break;
        }
    }

    private void setEnabled(boolean on) {
        boolean was = module.enabled && module.config.enabled;
        if (was == on) {
            logger.info("Site is already {}", on ? "ENABLED" : "DISABLED");
            status();
            return;
        }
        module.config.enabled = on;
        module.enabled = on;
        module.saveConfig();
        if (!on) {
            // Не оставлять живые сессии на выключенном сайте
            int sessions = module.sessions.count();
            module.sessions.clearAll();
            module.audit.log("site_disabled", "console", "console", "sessions dropped: " + sessions);
            logger.warn("=====================================================");
            logger.warn(" [ModsSite] Site is DISABLED — {} responds with 503,", module.mountPathNormalized());
            logger.warn(" all {} session(s) dropped. Launcher is NOT affected.", sessions);
            logger.warn(" Enable it back:  mods site on");
            logger.warn("=====================================================");
        } else {
            module.audit.log("site_enabled", "console", "console", "");
            logger.info("[ModsSite] Site is ENABLED: http(s)://<launchserver>{}", module.mountPathNormalized());
        }
    }

    private void status() {
        boolean runtime = module.enabled;
        boolean config = module.config.enabled;
        logger.info("Site: {} (config enabled: {}) — http(s)://<launchserver>{}",
                runtime ? "ENABLED" : "DISABLED", config, module.mountPathNormalized());
        if (!runtime) {
            logger.info("All requests to the site return 503. Launcher profiles/downloads are NOT affected.");
            logger.info("Turn it on:  mods site on");
        }
    }
}
