package pro.gravit.launchermodules.modssite.commands;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pro.gravit.launchermodules.modssite.ModsSiteModule;
import pro.gravit.launchserver.LaunchServer;
import pro.gravit.launchserver.command.Command;

/**
 * Диагностика признака админа для аккаунта лаунчера: печатает каждый шаг
 * живой проверки (пара auth, пользователь, тип adminCheck, SQL-запрос,
 * сырое значение первого столбца, совпадение с trueValues или текст ошибки).
 * Помогает понять, почему аккаунт БД видится как viewer.
 */
public class CheckAdminCommand extends Command {
    private static final Logger logger = LogManager.getLogger(CheckAdminCommand.class);
    private final ModsSiteModule module;

    public CheckAdminCommand(LaunchServer server, ModsSiteModule module) {
        super(server);
        this.module = module;
    }

    @Override
    public String getArgsDescription() {
        return "[login]";
    }

    @Override
    public String getUsageDescription() {
        return "Diagnose launcher admin check (permissions/sql) for a login";
    }

    @Override
    public void invoke(String... args) throws Exception {
        if (args.length < 1) {
            logger.warn("Usage: mods checkadmin <login>");
            return;
        }
        if (module.launcherAuthChecker == null) {
            logger.warn("LauncherAuthChecker is not initialized (module not started?)");
            return;
        }
        logger.info("Diagnosing admin check for '{}'...", args[0]);
        for (String line : module.launcherAuthChecker.diagnose(args[0]).split("\n")) {
            logger.info("  {}", line);
        }
        logger.info("Tip: viewer-override (Security -> Roles / admin-roles.json) is applied ON TOP of this result.");
    }
}
