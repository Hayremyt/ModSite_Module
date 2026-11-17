package pro.gravit.launchermodules.modssite.commands;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pro.gravit.launchermodules.modssite.ModsSiteModule;
import pro.gravit.launcher.base.profiles.ClientProfile;
import pro.gravit.launchermodules.modssite.mods.Modpack;
import pro.gravit.launchserver.LaunchServer;
import pro.gravit.launchserver.command.Command;

public class RehashCommand extends Command {
    private static final Logger logger = LogManager.getLogger(RehashCommand.class);
    private final ModsSiteModule module;

    public RehashCommand(LaunchServer server, ModsSiteModule module) {
        super(server);
        this.module = module;
    }

    @Override
    public String getArgsDescription() {
        return "[profile|all]";
    }

    @Override
    public String getUsageDescription() {
        return "Recalculate update hashes (same as the web button)";
    }

    @Override
    public void invoke(String... args) throws Exception {
        if (!module.modpacks.rehashSupported()) {
            logger.warn("profilesProvider is not local: rehash unavailable");
            return;
        }
        if (args.length == 0 || "all".equals(args[0])) {
            module.modpacks.rehash(null);
            logger.info("All updates dirs rehashed");
            return;
        }
        ClientProfile profile = module.modpacks.findProfile(args[0]);
        if (profile == null) {
            logger.warn("Profile '{}' not found", args[0]);
            return;
        }
        module.modpacks.rehash(profile.getDir());
        logger.info("Profile '{}' rehashed", profile.getDir());
    }
}
