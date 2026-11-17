package pro.gravit.launchermodules.modssite.commands;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pro.gravit.launchermodules.modssite.ModsSiteModule;
import pro.gravit.launchserver.LaunchServer;
import pro.gravit.launchserver.command.Command;

import java.util.List;

public class UnbanCommand extends Command {
    private static final Logger logger = LogManager.getLogger(UnbanCommand.class);
    private final ModsSiteModule module;

    public UnbanCommand(LaunchServer server, ModsSiteModule module) {
        super(server);
        this.module = module;
    }

    @Override
    public String getArgsDescription() {
        return "<ip> | account <login>";
    }

    @Override
    public String getUsageDescription() {
        return "Unban IP or unlock account";
    }

    @Override
    public void invoke(String... args) throws Exception {
        verifyArgs(args, 1);
        if (args[0].equals("account")) {
            verifyArgs(args, 2);
            boolean unlocked = module.ipBans.unlockAccount(args[1]);
            logger.info("Account '{}': {}", args[1], unlocked ? "unlocked" : "was not locked");
            return;
        }
        boolean unbanned = module.ipBans.unban(args[0]);
        logger.info("IP '{}': {}", args[0], unbanned ? "unbanned" : "was not banned");
    }
}
