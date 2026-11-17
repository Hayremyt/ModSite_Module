package pro.gravit.launchermodules.modssite.commands;

import pro.gravit.launchermodules.modssite.ModsSiteModule;
import pro.gravit.launchserver.LaunchServer;
import pro.gravit.launchserver.command.Command;

/**
 * Корневая команда «mods» со всеми подкомандами модуля.
 * <p>
 * Примеры:
 * <pre>
 *   mods status
 *   mods user add admin MyPassword admin
 *   mods user pass admin NewPassword
 *   mods user totp admin
 *   mods user list
 *   mods whitelist add 192.168.1.5
 *   mods blacklist add 10.0.0.0/24
 *   mods bans
 *   mods unban 1.2.3.4
 *   mods rehash [profile|all]
 *   mods check [profile]
 *   mods reload
 *   mods site [on|off|status] — выключатель сайта (503 + сброс сессий)
 *   mods web [restore]
 *   mods curseforge test
 * </pre>
 */
public class ModsCommand extends Command {
    public ModsCommand(LaunchServer server, ModsSiteModule module) {
        super(server);
        childCommands.put("status", new StatusCommand(server, module));
        childCommands.put("user", new UserCommand(server, module));
        childCommands.put("whitelist", new WhitelistCommand(server, module));
        childCommands.put("blacklist", new BlacklistCommand(server, module));
        childCommands.put("bans", new BansCommand(server, module));
        childCommands.put("unban", new UnbanCommand(server, module));
        childCommands.put("rehash", new RehashCommand(server, module));
        childCommands.put("check", new CheckCommand(server, module));
        childCommands.put("reload", new ReloadCommand(server, module));
        childCommands.put("sessions", new SessionsCommand(server, module));
        childCommands.put("site", new SiteCommand(server, module));
        childCommands.put("web", new WebCommand(server, module));
        childCommands.put("curseforge", new CurseForgeCommand(server, module));
        childCommands.put("profile", new ProfileCommand(server, module));
    }

    @Override
    public String getArgsDescription() {
        return "[status|user|whitelist|blacklist|bans|unban|rehash|check|reload|sessions|site|web|curseforge|profile] [args]";
    }

    @Override
    public String getUsageDescription() {
        return "Manage ModsSite module (web UI + Modrinth/CurseForge mod packs)";
    }

    @Override
    public void invoke(String... args) throws Exception {
        invokeSubcommands(args);
    }
}
