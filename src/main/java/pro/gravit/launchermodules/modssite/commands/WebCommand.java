package pro.gravit.launchermodules.modssite.commands;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pro.gravit.launchermodules.modssite.ModsSiteModule;
import pro.gravit.launchermodules.modssite.web.StaticResources;
import pro.gravit.launchermodules.modssite.web.WebResourcesManager;
import pro.gravit.launchserver.LaunchServer;
import pro.gravit.launchserver.command.Command;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Управление веб-файлами интерфейса (config/ModsSite/web).
 *
 * <pre>
 *   mods web           — статус файлов (где лежат, изменены ли администратором)
 *   mods web restore   — принудительно перезаписать ВСЕ файлы из jar
 *                        (правки администратора будут потеряны)
 * </pre>
 */
public class WebCommand extends Command {
    private static final Logger logger = LogManager.getLogger(WebCommand.class);
    private final ModsSiteModule module;

    public WebCommand(LaunchServer server, ModsSiteModule module) {
        super(server);
        this.module = module;
    }

    @Override
    public String getArgsDescription() {
        return "[restore]";
    }

    @Override
    public String getUsageDescription() {
        return "Web UI files: status / force-restore from jar";
    }

    @Override
    public void invoke(String... args) throws Exception {
        Path webDir = module.webDir;
        if (webDir == null) {
            logger.warn("Module is not initialized (webDir unknown)");
            return;
        }
        Map<String, byte[]> bundle = StaticResources.bundledAll();
        if (args.length > 0 && "restore".equals(args[0])) {
            WebResourcesManager.Report report =
                    WebResourcesManager.forceRestore(webDir, module.versionString, bundle);
            logger.info("Restored {} web file(s) to {} from jar", report.extracted.size(), webDir);
            if (!report.missingInBundle.isEmpty()) {
                logger.warn("Not found in jar (left as is): {}", report.missingInBundle);
            }
            logger.info("Admin modifications were overwritten. Changes apply immediately, reload the page (Ctrl+F5).");
            return;
        }
        // Статус (только чтение)
        logger.info("Web UI dir: {}", webDir);
        for (String name : WebResourcesManager.UI_FILES) {
            byte[] bundled = bundle.get(name);
            Path file = webDir.resolve(name);
            boolean onDisk = Files.isRegularFile(file);
            String where;
            if (bundled != null && onDisk) {
                where = Files.size(file) + " bytes, editable";
            } else if (bundled != null) {
                where = "in jar only (restored on next start)";
            } else if (onDisk) {
                where = Files.size(file) + " bytes, on disk only";
            } else {
                where = "MISSING everywhere (404 until restored)";
            }
            logger.info("  {:<12} {}", name, where);
        }
        logger.info("Edit files in {} and reload the page — no restart needed.", webDir);
        logger.info("'mods web restore' overwrites ALL files from jar (admin edits lost).");
    }
}
