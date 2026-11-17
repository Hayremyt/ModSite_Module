package pro.gravit.launchermodules.modssite.commands;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pro.gravit.launchermodules.modssite.ModsSiteConfig;
import pro.gravit.launchermodules.modssite.ModsSiteModule;
import pro.gravit.launchermodules.modssite.auth.PasswordHasher;
import pro.gravit.launchermodules.modssite.auth.SessionManager;
import pro.gravit.launchermodules.modssite.auth.TotpProvider;
import pro.gravit.launchserver.LaunchServer;
import pro.gravit.launchserver.command.Command;

/**
 * mods user add|remove|list|pass|role|enable|disable|totp|totpoff
 */
public class UserCommand extends Command {
    private static final Logger logger = LogManager.getLogger(UserCommand.class);
    private final ModsSiteModule module;

    public UserCommand(LaunchServer server, ModsSiteModule module) {
        super(server);
        this.module = module;
    }

    @Override
    public String getArgsDescription() {
        return "[add login password (admin|viewer)] [remove login] [list] [pass login password] "
                + "[role login (admin|viewer)] [enable|disable login] [totp login] [totpoff login]";
    }

    @Override
    public String getUsageDescription() {
        return "Manage local web accounts";
    }

    @Override
    public void invoke(String... args) throws Exception {
        verifyArgs(args, 1);
        switch (args[0]) {
            case "add" -> {
                verifyArgs(args, 3);
                String role = args.length > 3 ? args[3] : "viewer";
                add(args[1], args[2], role);
            }
            case "remove" -> {
                verifyArgs(args, 2);
                remove(args[1]);
            }
            case "list" -> list();
            case "pass" -> {
                verifyArgs(args, 3);
                setPassword(args[1], args[2]);
            }
            case "role" -> {
                verifyArgs(args, 3);
                setRole(args[1], args[2]);
            }
            case "enable" -> {
                verifyArgs(args, 2);
                setEnabled(args[1], true);
            }
            case "disable" -> {
                verifyArgs(args, 2);
                setEnabled(args[1], false);
            }
            case "totp" -> {
                verifyArgs(args, 2);
                generateTotp(args[1]);
            }
            case "totpoff" -> {
                verifyArgs(args, 2);
                disableTotp(args[1]);
            }
            default -> throw new IllegalStateException("Unknown subcommand: " + args[0]);
        }
    }

    private ModsSiteConfig.AccountConfig find(String login) {
        return module.config.accounts.stream()
                .filter(a -> a.login != null && a.login.equalsIgnoreCase(login))
                .findFirst().orElse(null);
    }

    private void add(String login, String password, String role) throws Exception {
        if (login.length() > 64 || login.isBlank()) throw new IllegalArgumentException("Invalid login");
        if (password.length() < 6) throw new IllegalArgumentException("Password must be at least 6 characters");
        if (!"admin".equals(role) && !"viewer".equals(role)) throw new IllegalArgumentException("Role: admin|viewer");
        if (find(login) != null) throw new IllegalStateException("Account already exists");
        ModsSiteConfig.AccountConfig account = new ModsSiteConfig.AccountConfig(login, null, role);
        account.passwordHash = PasswordHasher.hash(password, 210_000);
        module.config.accounts.add(account);
        module.saveConfig();
        logger.info("Account '{}' added with role '{}'", login, role);
    }

    private void remove(String login) throws Exception {
        boolean removed = module.config.accounts.removeIf(a -> a.login != null && a.login.equalsIgnoreCase(login));
        if (removed) {
            module.sessions.deleteAllFor(login);
            module.saveConfig();
            logger.info("Account '{}' removed", login);
        } else {
            logger.warn("Account '{}' not found", login);
        }
    }

    private void list() {
        if (module.config.accounts.isEmpty()) {
            logger.info("No local accounts (auth mode: {})", module.config.auth.mode);
            return;
        }
        for (ModsSiteConfig.AccountConfig account : module.config.accounts) {
            logger.info("{} [{}] role={} totp={} enabled={}", account.login,
                    account.passwordHash != null ? "hashed" : "PLAIN(!)",
                    account.role, account.totpSecret != null ? "on" : "off", account.enabled);
        }
    }

    private void setPassword(String login, String password) throws Exception {
        if (password.length() < 6) throw new IllegalArgumentException("Password must be at least 6 characters");
        ModsSiteConfig.AccountConfig account = find(login);
        if (account == null) throw new IllegalStateException("Account not found");
        account.password = null;
        account.passwordHash = PasswordHasher.hash(password, 210_000);
        module.sessions.deleteAllFor(login);
        module.saveConfig();
        logger.info("Password for '{}' changed", login);
    }

    private void setRole(String login, String role) throws Exception {
        if (!"admin".equals(role) && !"viewer".equals(role)) throw new IllegalArgumentException("Role: admin|viewer");
        ModsSiteConfig.AccountConfig account = find(login);
        if (account == null) throw new IllegalStateException("Account not found");
        account.role = role;
        if ("viewer".equals(role)) {
            module.sessions.deleteAllFor(login);
        }
        module.saveConfig();
        logger.info("Role for '{}' set to '{}'", login, role);
    }

    private void setEnabled(String login, boolean enabled) throws Exception {
        ModsSiteConfig.AccountConfig account = find(login);
        if (account == null) throw new IllegalStateException("Account not found");
        account.enabled = enabled;
        if (!enabled) {
            module.sessions.deleteAllFor(login);
        }
        module.saveConfig();
        logger.info("Account '{}' {}", login, enabled ? "enabled" : "disabled");
    }

    private void generateTotp(String login) throws Exception {
        ModsSiteConfig.AccountConfig account = find(login);
        if (account == null) throw new IllegalStateException("Account not found");
        String secret = TotpProvider.generateSecret();
        account.totpSecret = secret;
        module.saveConfig();
        logger.info("TOTP enabled for '{}'", login);
        logger.info("Secret (base32): {}", secret);
        logger.info("Add to authenticator: {}", TotpProvider.otpauthUri(secret, login, "ModsSite"));
        logger.info("Open the URI as a QR code, or use any otpauth QR generator.");
    }

    private void disableTotp(String login) throws Exception {
        ModsSiteConfig.AccountConfig account = find(login);
        if (account == null) throw new IllegalStateException("Account not found");
        account.totpSecret = null;
        module.saveConfig();
        logger.info("TOTP disabled for '{}'", login);
    }
}
