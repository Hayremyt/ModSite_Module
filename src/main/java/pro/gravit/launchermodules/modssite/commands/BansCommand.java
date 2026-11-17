package pro.gravit.launchermodules.modssite.commands;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pro.gravit.launchermodules.modssite.ModsSiteModule;
import pro.gravit.launchserver.LaunchServer;
import pro.gravit.launchserver.command.Command;

import java.time.Instant;
import java.util.Map;

public class BansCommand extends Command {
    private static final Logger logger = LogManager.getLogger(BansCommand.class);
    private final ModsSiteModule module;

    public BansCommand(LaunchServer server, ModsSiteModule module) {
        super(server);
        this.module = module;
    }

    @Override
    public String getArgsDescription() {
        return "[]";
    }

    @Override
    public String getUsageDescription() {
        return "List active IP bans and account locks";
    }

    @Override
    public void invoke(String... args) {
        Map<String, long[]> bans = module.ipBans.getActiveBans();
        if (bans.isEmpty()) {
            logger.info("No active IP bans");
        } else {
            logger.info("Active IP bans:");
            bans.forEach((ip, info) -> logger.info("  {} - until {} (level {}, total fails {})",
                    ip, Instant.ofEpochMilli(info[0]), info[1], info[2]));
        }
        Map<String, long[]> locks = module.ipBans.getActiveAccountLocks();
        if (locks.isEmpty()) {
            logger.info("No locked accounts");
        } else {
            logger.info("Locked accounts:");
            locks.forEach((login, info) -> logger.info("  {} - until {} (level {})",
                    login, Instant.ofEpochMilli(info[0]), info[1]));
        }
    }
}
