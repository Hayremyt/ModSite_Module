package pro.gravit.launchermodules.modssite.commands;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pro.gravit.launchermodules.modssite.ModsSiteModule;
import pro.gravit.launchserver.LaunchServer;
import pro.gravit.launchserver.command.Command;

import java.time.Instant;

public class WhitelistCommand extends Command {
    private static final Logger logger = LogManager.getLogger(WhitelistCommand.class);
    private final ModsSiteModule module;

    public WhitelistCommand(LaunchServer server, ModsSiteModule module) {
        super(server);
        this.module = module;
    }

    @Override
    public String getArgsDescription() {
        return "[add|remove|list] [ip|cidr]";
    }

    @Override
    public String getUsageDescription() {
        return "IPs that are never banned by brute-force protection";
    }

    @Override
    public void invoke(String... args) throws Exception {
        verifyArgs(args, 1);
        switch (args[0]) {
            case "add" -> {
                verifyArgs(args, 2);
                if (module.ipBans.whitelist(args[1])) {
                    logger.info("'{}' whitelisted (never banned)", args[1]);
                } else {
                    logger.warn("Invalid IP/CIDR: {}", args[1]);
                }
            }
            case "remove" -> {
                verifyArgs(args, 2);
                if (module.ipBans.unwhitelist(args[1])) {
                    module.saveConfig();
                    logger.info("'{}' removed from whitelist", args[1]);
                } else {
                    logger.warn("Invalid IP/CIDR: {}", args[1]);
                }
            }
            case "list" -> {
                logger.info("Whitelist: {}", module.ipBans.getWhitelist());
            }
            default -> throw new IllegalStateException("Unknown subcommand: " + args[0]);
        }
    }
}
