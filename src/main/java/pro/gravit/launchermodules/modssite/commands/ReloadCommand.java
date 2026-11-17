package pro.gravit.launchermodules.modssite.commands;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pro.gravit.launchermodules.modssite.ModsSiteModule;
import pro.gravit.launchserver.LaunchServer;
import pro.gravit.launchserver.command.Command;

public class ReloadCommand extends Command {
    private static final Logger logger = LogManager.getLogger(ReloadCommand.class);
    private final ModsSiteModule module;

    public ReloadCommand(LaunchServer server, ModsSiteModule module) {
        super(server);
        this.module = module;
    }

    @Override
    public String getArgsDescription() {
        return "[]";
    }

    @Override
    public String getUsageDescription() {
        return "Reload module config (accounts, security, sources)";
    }

    @Override
    public void invoke(String... args) throws Exception {
        module.reloadConfig();
        logger.info("ModsSite config reloaded");
    }
}
