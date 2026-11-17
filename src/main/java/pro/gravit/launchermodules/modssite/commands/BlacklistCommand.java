package pro.gravit.launchermodules.modssite.commands;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pro.gravit.launchermodules.modssite.ModsSiteModule;
import pro.gravit.launchserver.LaunchServer;
import pro.gravit.launchserver.command.Command;

import java.time.Instant;

public class BlacklistCommand extends Command {
    private static final Logger logger = LogManager.getLogger(BlacklistCommand.class);
    private final ModsSiteModule module;

    public BlacklistCommand(LaunchServer server, ModsSiteModule module) {
        super(server);
        this.module = module;
    }

    @Override
    public String getArgsDescription() {
        return "[add|remove|list] [ip|cidr]";
    }

    @Override
    public String getUsageDescription() {
        return "IPs that are always rejected";
    }

    @Override
    public void invoke(String... args) throws Exception {
        verifyArgs(args, 1);
        switch (args[0]) {
            case "add" -> {
                verifyArgs(args, 2);
                if (module.ipBans.blacklist(args[1])) {
                    logger.info("'{}' blacklisted (always rejected)", args[1]);
                } else {
                    logger.warn("Invalid IP/CIDR: {}", args[1]);
                }
            }
            case "remove" -> {
                verifyArgs(args, 2);
                if (module.ipBans.unblacklist(args[1])) {
                    module.saveConfig();
                    logger.info("'{}' removed from blacklist", args[1]);
                } else {
                    logger.warn("Invalid IP/CIDR: {}", args[1]);
                }
            }
            case "list" -> {
                logger.info("blacklist: {}", module.ipBans.getBlacklist());
            }
            default -> throw new IllegalStateException("Unknown subcommand: " + args[0]);
        }
    }
}
