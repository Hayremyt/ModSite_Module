package pro.gravit.launchermodules.modssite.commands;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pro.gravit.launcher.base.profiles.ClientProfile;
import pro.gravit.launchermodules.modssite.ModsSiteModule;
import pro.gravit.launchermodules.modssite.mods.ModEntry;
import pro.gravit.launchermodules.modssite.mods.Modpack;
import pro.gravit.launchermodules.modssite.mods.UpdateService;
import pro.gravit.launchserver.LaunchServer;
import pro.gravit.launchserver.command.Command;

import java.util.List;

public class CheckCommand extends Command {
    private static final Logger logger = LogManager.getLogger(CheckCommand.class);
    private final ModsSiteModule module;

    public CheckCommand(LaunchServer server, ModsSiteModule module) {
        super(server);
        this.module = module;
    }

    @Override
    public String getArgsDescription() {
        return "[profile]";
    }

    @Override
    public String getUsageDescription() {
        return "Check mod updates (all profiles if not specified)";
    }

    @Override
    public void invoke(String... args) throws Exception {
        List<ClientProfile> profiles = module.modpacks.profiles();
        if (args.length > 0) {
            ClientProfile profile = module.modpacks.findProfile(args[0]);
            if (profile == null) {
                logger.warn("Profile '{}' not found", args[0]);
                return;
            }
            profiles = List.of(profile);
        }
        for (ClientProfile profile : profiles) {
            var lock = module.modpacks.lock(profile.getDir());
            lock.lock();
            try {
                Modpack modpack = module.modpacks.load(profile);
                if (modpack.mods.isEmpty()) {
                    logger.info("[{}] no mods configured", profile.getDir());
                    continue;
                }
                UpdateService.CheckSummary summary = module.updates.checkUpdates(
                        profile, modpack, module.modpacks, false);
                List<UpdateService.UpdateCheck> checks = summary.checks;
                for (String warning : summary.warnings) {
                    logger.warn("[{}] {}", profile.getDir(), warning);
                }
                int updates = 0;
                for (int i = 0; i < modpack.mods.size(); i++) {
                    ModEntry entry = modpack.mods.get(i);
                    UpdateService.UpdateCheck check = i < checks.size() ? checks.get(i) : null;
                    if (check != null && check.hasUpdate) {
                        updates++;
                        logger.info("[{}] {} : {} -> {}",
                                profile.getDir(), entry.name,
                                entry.installedVersionNumber == null ? "?" : entry.installedVersionNumber,
                                entry.latestVersionNumber);
                    } else if (check != null && check.error != null) {
                        logger.warn("[{}] {} : {}", profile.getDir(), entry.name, check.error);
                    }
                }
                logger.info("[{}] {} mods, {} updates available (game {}, loader {})",
                        profile.getDir(), modpack.mods.size(), updates,
                        module.modpacks.gameVersion(profile, modpack),
                        module.modpacks.loader(profile, modpack));
            } finally {
                lock.unlock();
            }
        }
    }
}
