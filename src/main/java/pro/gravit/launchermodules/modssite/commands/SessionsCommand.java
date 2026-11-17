package pro.gravit.launchermodules.modssite.commands;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pro.gravit.launchermodules.modssite.ModsSiteModule;
import pro.gravit.launchermodules.modssite.auth.SessionManager;
import pro.gravit.launchserver.LaunchServer;
import pro.gravit.launchserver.command.Command;

import java.time.Instant;
import java.util.List;

public class SessionsCommand extends Command {
    private static final Logger logger = LogManager.getLogger(SessionsCommand.class);
    private final ModsSiteModule module;

    public SessionsCommand(LaunchServer server, ModsSiteModule module) {
        super(server);
        this.module = module;
    }

    @Override
    public String getArgsDescription() {
        return "[list|clear]";
    }

    @Override
    public String getUsageDescription() {
        return "View or drop web sessions";
    }

    @Override
    public void invoke(String... args) throws Exception {
        String action = args.length > 0 ? args[0] : "list";
        switch (action) {
            case "list" -> {
                List<SessionManager.Session> sessions = module.sessions.list();
                if (sessions.isEmpty()) {
                    logger.info("No active sessions");
                    return;
                }
                logger.info("Active sessions: {}", sessions.size());
                for (SessionManager.Session session : sessions) {
                    logger.info("  {} [{}] {} from {}, expires {}",
                            session.login, session.role, session.source,
                            session.ip == null ? "?" : session.ip,
                            Instant.ofEpochMilli(session.expiresAt));
                }
            }
            case "clear" -> {
                module.sessions.purgeExpired();
                logger.info("Expired sessions purged");
            }
            default -> throw new IllegalStateException("Unknown subcommand: " + action);
        }
    }
}
