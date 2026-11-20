package pro.gravit.launchermodules.modssite;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pro.gravit.launcher.base.config.JsonConfigurable;
import pro.gravit.launcher.base.modules.LauncherInitContext;
import pro.gravit.launcher.base.modules.LauncherModule;
import pro.gravit.launcher.base.modules.LauncherModuleInfoBuilder;
import pro.gravit.launcher.base.modules.events.ClosePhase;
import pro.gravit.launchermodules.modssite.auth.AdminRoleOverrides;
import pro.gravit.launchermodules.modssite.auth.AuthService;
import pro.gravit.launchermodules.modssite.auth.LauncherAuthChecker;
import pro.gravit.launchermodules.modssite.auth.PasswordHasher;
import pro.gravit.launchermodules.modssite.auth.RoleResolver;
import pro.gravit.launchermodules.modssite.auth.SessionManager;
import pro.gravit.launchermodules.modssite.commands.ModsCommand;
import pro.gravit.launchermodules.modssite.mods.BackupService;
import pro.gravit.launchermodules.modssite.mods.CurseForgeApiProvider;
import pro.gravit.launchermodules.modssite.mods.ImportService;
import pro.gravit.launchermodules.modssite.mods.ModpackService;
import pro.gravit.launchermodules.modssite.mods.ModrinthApiProvider;
import pro.gravit.launchermodules.modssite.mods.SuggestionService;
import pro.gravit.launchermodules.modssite.mods.UpdateService;
import pro.gravit.launchermodules.modssite.security.AuditLogger;
import pro.gravit.launchermodules.modssite.security.IpBanManager;
import pro.gravit.launchermodules.modssite.security.RateLimiter;
import pro.gravit.launchermodules.modssite.web.IconProxyService;
import pro.gravit.launchermodules.modssite.web.ModsWebSeverlet;
import pro.gravit.launchermodules.modssite.web.StaticResources;
import pro.gravit.launchermodules.modssite.web.WebResourcesManager;
import pro.gravit.launchserver.LaunchServer;
import pro.gravit.launchserver.modules.events.LaunchServerPostInitPhase;
import pro.gravit.launchserver.modules.impl.LaunchServerInitContext;
import pro.gravit.launchserver.socket.handlers.NettyWebAPIHandler;
import pro.gravit.utils.Version;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * ModsSite — модуль LaunchServer (GravitLauncher 5.7.x):
 * веб-панель для управления модпаками профилей (Modrinth/CurseForge):
 * выбор опциональных модов, зависимости/конфликты, обновления со сменой имён
 * файлов, пересчёт хэшей; гибридная авторизация, защита от брутфорса
 * (эскалация банов IP, whitelist/blacklist), TOTP, аудит.
 */
public class ModsSiteModule extends LauncherModule {
    public static final Version version = new Version(1, 1, 2, 0, Version.Type.LTS);
    public final String versionString = "1.1.2";
    public final Logger logger = LogManager.getLogger(ModsSiteModule.class);

    // Состояние и сервисы (инициализируются в init)
    public ModsSiteConfig config;
    public JsonConfigurable<ModsSiteConfig> configurable;
    public LaunchServer server;
    public boolean enabled = false;
    public String mountPath = "/mods";
    /** Каталог веб-файлов интерфейса (config/ModsSite/web), задаётся в init. */
    public Path webDir;

    public IpBanManager ipBans;
    public RateLimiter rateLimiter = new RateLimiter();
    public AuditLogger audit;
    public SessionManager sessions;
    public AuthService authService;
    public LauncherAuthChecker launcherAuthChecker;
    /** Живые роли: пересчёт админа/суперадмина/наблюдателя на каждом запросе. */
    public RoleResolver roleResolver;
    /** Оверрайды «наблюдатель» для админов (панель Безопасность -> Роли, суперадмин). */
    public AdminRoleOverrides adminRoles;
    public ModpackService modpacks;
    public UpdateService updates;
    public ImportService imports;
    public SuggestionService suggestions;
    public ModrinthApiProvider modrinthProvider;
    public CurseForgeApiProvider curseforgeProvider;
    public BackupService backups;
    /** Прокси картинок Modrinth/CurseForge (иконки грузятся с домена сайта). */
    public IconProxyService iconProxy;
    /** Сервлет на /mods (сайт + API по пути /mods). */
    public NettyWebAPIHandler.SeverletPathPair severletPair;
    /** Сервлет в корне "/" (root-режим для доменов из web.siteDomains):
     *  запросы других хостов передаются дальше по конвейеру
     *  (file-сервер лаунчера), поэтому регистрация безвредна всегда. */
    public NettyWebAPIHandler.SeverletPathPair rootSeverletPair;

    public ModsSiteModule() {
        super(new LauncherModuleInfoBuilder()
                .setName("ModsSite")
                .setVersion(version)
                .setDependencies(new String[]{"LaunchServerCore"})
                .createLauncherModuleInfo());
    }

    /** Путь файла, из которого загружен класс модуля (для диагностики). */
    private static String jarLocation() {
        try {
            return new java.io.File(ModsSiteModule.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).getPath();
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void init(LauncherInitContext initContext) {
        Path configDir = modulesConfigManager.getModuleConfigDir(moduleInfo.name);
        ModsSiteModule module = this;
        configurable = new JsonConfigurable<>(ModsSiteConfig.class,
                modulesConfigManager.getModuleConfig(moduleInfo.name)) {
            @Override
            public ModsSiteConfig getConfig() {
                return module.config;
            }

            @Override
            public void setConfig(ModsSiteConfig config) {
                module.config = config;
            }

            @Override
            public ModsSiteConfig getDefaultConfig() {
                ModsSiteConfig defaultConfig = new ModsSiteConfig();
                String generatedPassword = pro.gravit.utils.helper.SecurityHelper.randomStringToken();
                defaultConfig.accounts = new ArrayList<>(List.of(
                        new ModsSiteConfig.AccountConfig("admin", generatedPassword, "admin")));
                // Пароль показывается ОДИН раз — крупный баннер, который трудно
                // пропустить в логе. Забытый пароль не восстанавливается — только
                // сброс командой mods user pass admin <новый> из консоли сервера.
                logger.warn("");
                logger.warn("=====================================================================");
                logger.warn(" [ModsSite] FIRST START — web login created (shown ONCE):");
                logger.warn("     login:    admin");
                logger.warn("     password: {}", generatedPassword);
                logger.warn(" Save it now. Password reset (console): mods user pass admin <New>");
                logger.warn("=====================================================================");
                logger.warn("");
                return defaultConfig;
            }
        };
        if (initContext instanceof LaunchServerInitContext launchServerInitContext) {
            // Горячая загрузка модуля командой modules add
            init(launchServerInitContext.server, configDir);
            return;
        }
        registerEvent(this::finish, LaunchServerPostInitPhase.class);
        registerEvent(this::close, ClosePhase.class);
        // init(context) вызывается и при старте сервера до создания LaunchServer —
        // отложенная инициализация в finish()
        pendingConfigDir = configDir;
    }

    private transient Path pendingConfigDir;

    public void finish(LaunchServerPostInitPhase event) {
        init(event.server, pendingConfigDir != null ? pendingConfigDir
                : modulesConfigManager.getModuleConfigDir(moduleInfo.name));
    }

    private synchronized void init(LaunchServer server, Path configDir) {
        this.server = server;
        // 1. Конфиг (путь запоминаем для живой перечитки при изменении файла)
        configFilePath = modulesConfigManager.getModuleConfig(moduleInfo.name);
        try {
            configurable.loadConfig();
        } catch (IOException e) {
            logger.error("Config load failed, using defaults", e);
            config = configurable.getDefaultConfig();
        }
        config.normalize();
        recordConfigMtime();
        // Уровень вывода в консоль (logging.consoleLevel): применяем как можно
        // раньше — чтобы и сами сообщения инициализации подчинялись настройке
        ModsSiteLogLevels.apply(config.logging.consoleLevel);
        if (config.relaxedDefaults > 0) {
            logger.info("[ModsSite] Security defaults relaxed to 1.0.5 (old defaults detected in config): "
                    + "more attempts before ban, shorter bans, more logins per minute. "
                    + "Admin-customized values are left untouched.");
        }
        migratePasswordsToHashes();
        saveConfig();
        // 2. Безопасность
        ipBans = new IpBanManager(configDir.resolve("ipfilter.json"), config);
        ipBans.load();
        rateLimiter = new RateLimiter();
        audit = new AuditLogger(configDir, config.audit.file, config.audit.enabled,
                config.audit.maxFileMb, config.audit.keepFiles);
        sessions = new SessionManager(configDir.resolve("sessions.json"), config);
        sessions.load();
        launcherAuthChecker = new LauncherAuthChecker(server, config);
        authService = new AuthService(config, ipBans, launcherAuthChecker);
        adminRoles = new AdminRoleOverrides(configDir.resolve("admin-roles.json"));
        adminRoles.load();
        roleResolver = new RoleResolver(this);
        // 3. Моды
        modrinthProvider = new ModrinthApiProvider(config.mods.modrinth, Math.max(1, config.mods.cacheTtlMinutes) * 60_000L);
        curseforgeProvider = new CurseForgeApiProvider(config.mods.curseforge, Math.max(1, config.mods.cacheTtlMinutes) * 60_000L);
        updates = new UpdateService(config, modrinthProvider, curseforgeProvider);
        modpacks = new ModpackService(server, config, configDir);
        imports = new ImportService(config, modpacks, updates);
        suggestions = new SuggestionService(configDir.resolve("suggestions.json"));
        backups = new BackupService(config, modpacks, updates, audit,
                configDir.resolve("backups"), versionString);
        // Прокси картинок: у части клиентов CDN Modrinth/CurseForge заблокированы,
        // иконки/превью теперь отдаются сервером (GET /api/icon?url=...) из кэша на диске
        iconProxy = new IconProxyService(this, configDir.resolve("iconcache"));
        iconProxy.sweep();
        // 4. Команды
        server.commandHandler.registerCommand("mods", new ModsCommand(server, this));
        // 5. Веб
        enabled = config.enabled;
        mountPath = "/mods";
        // Сайт работает ОДНОВРЕМЕННО в двух адресах:
        // 1) http(s)://<ip>:<порт>/mods/  — классический путь, настроен всегда;
        // 2) в КОРНЕ домена (http(s)://mods.example.com/) — для хостов из
        //    config.web.siteDomains. Корневой сервлет зарегистрирован всегда,
        //    но пропускает только запросы с совпавшим Host; всё остальное
        //    передаётся дальше по конвейеру (file-сервер лаунчера), поэтому
        //    ни лаунчер, ни загрузка файлов не затронуты. Список доменов
        //    читается из живого конфига — «Настройки» сайта или mods reload
        //    применяют его без перезапуска.
        severletPair = NettyWebAPIHandler.addUnsafeSeverlet(mountPath, new ModsWebSeverlet(this));
        rootSeverletPair = NettyWebAPIHandler.addUnsafeSeverlet("/", new ModsWebSeverlet(this, true));
        if (!config.web.siteDomains.isEmpty()) {
            logger.info("[ModsSite] Root mount active for domains: {} — сайт доступен и как /mods/, и в корне этих доменов",
                    config.web.siteDomains);
        }
        // Веб-файлы интерфейса живут в config/ModsSite/web: администратор правит
        // стили/разметку напрямую, изменения применяются сразу (перезапуск не нужен).
        // Jar — источник первичной записи и восстановления: при каждом старте
        // отсутствующие файлы восстанавливаются, неизменённые — обновляются,
        // правки администратора сохраняются (см. WebResourcesManager).
        webDir = configDir.resolve("web");
        StaticResources.setExternalDir(webDir);
        WebResourcesManager.Report report =
                WebResourcesManager.sync(webDir, versionString, StaticResources.bundledAll());
        String jarPath = jarLocation();
        if (!report.extracted.isEmpty()) {
            logger.info("[ModsSite] Web UI files extracted to {}: {}", webDir, report.extracted);
        }
        if (!report.updated.isEmpty()) {
            logger.info("[ModsSite] Web UI files updated from jar: {}", report.updated);
        }
        if (!report.kept.isEmpty()) {
            logger.info("[ModsSite] Web UI files kept (admin-modified): {}", report.kept);
        }
        if (!report.missingInBundle.isEmpty()) {
            logger.warn("[ModsSite] Web files missing from module jar ({}): {}",
                    jarPath == null ? "<unknown>" : jarPath, report.missingInBundle);
        }
        if (report.hasProblems()) {
            logger.error("[ModsSite] Web files unavailable BOTH in jar and in {}: {}",
                    webDir, report.missingEverywhere);
            logger.error("[ModsSite] The UI will be incomplete (404 on some of index.html/style.css/app.js).");
            logger.error("[ModsSite] Fix: use the prebuilt ModsSite_module.jar from the release zip,");
            logger.error("[ModsSite] then restart (missing files are restored to {})", webDir);
        } else {
            logger.info("[ModsSite] Web UI ready: {} (editable, changes apply immediately)", webDir);
        }
        if (config.mods.curseforge.enabled
                && (config.mods.curseforge.apiKey == null || config.mods.curseforge.apiKey.isBlank())) {
            logger.info("[ModsSite] CurseForge disabled: apiKey not set (get one at https://console.overwolf.com/)");
        }
        logger.info("[ModsSite] initialized. Web UI: {}{} | auth mode: {} | local accounts: {}",
                "http(s)://<launchserver>", mountPath, config.auth.mode, config.accounts.size());
        // 6. Периодические задачи
        try {
            server.service.scheduleAtFixedRate(() -> {
                try {
                    ipBans.tick();
                    sessions.purgeExpired();
                    rateLimiter.sweep();
                    iconProxy.sweep();
                } catch (Throwable t) {
                    logger.debug("Maintenance error", t);
                }
            }, 5, 5, TimeUnit.MINUTES);
        } catch (Throwable t) {
            logger.warn("Cannot schedule maintenance task", t);
        }
    }

    private static String normalizeMountPath(String path) {
        if (path == null || path.isBlank()) return "/mods";
        if (!path.startsWith("/")) path = "/" + path;
        while (path.endsWith("/") && path.length() > 1) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    /** Точка монтирования для внешних ссылок (не используется для маршрутизации). */
    public String mountPathNormalized() {
        return normalizeMountPath(mountPath);
    }

    /** Заменить открытые пароли в конфиге на PBKDF2-хэши (один раз). */
    private void migratePasswordsToHashes() {
        boolean changed = false;
        for (ModsSiteConfig.AccountConfig account : config.accounts) {
            if (account.password != null && !account.password.isEmpty()) {
                account.passwordHash = PasswordHasher.hash(account.password, 210_000);
                account.password = null;
                changed = true;
                logger.info("[ModsSite] Password of '{}' hashed (plaintext removed from config)", account.login);
            }
        }
        if (changed) {
            try {
                saveConfig();
            } catch (Exception e) {
                logger.error("Cannot rewrite config with hashes", e);
            }
        }
    }

    public void saveConfig() {
        try {
            configurable.saveConfig();
            recordConfigMtime(); // сохранение само не должно триггерить перечитку
        } catch (IOException e) {
            logger.error("Cannot save config", e);
        }
    }

    /** Путь конфиг-файла модуля (для живой перечитки). */
    private transient Path configFilePath;
    /** mtime конфига на момент последней загрузки/сохранения. */
    private volatile long configLoadedMtime = -1;

    private void recordConfigMtime() {
        if (configFilePath == null) return;
        try {
            configLoadedMtime = Files.getLastModifiedTime(configFilePath).toMillis();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Живая подгрузка конфига: админ правит Config.json вручную (аккаунты,
     * роли, суперадмины, домены) — изменения подхватываются при следующем
     * же запросе, без «mods reload» и без перезапуска сервера.
     * Стоимость на запрос — один stat() файла.
     */
    public void ensureFreshConfig() {
        Path path = configFilePath;
        if (path == null) return;
        long mtime;
        try {
            mtime = Files.getLastModifiedTime(path).toMillis();
        } catch (Throwable t) {
            return; // файл недоступен — работаем на текущем конфиге
        }
        if (mtime == configLoadedMtime) return;
        synchronized (this) {
            try {
                if (Files.getLastModifiedTime(path).toMillis() == configLoadedMtime) return;
            } catch (Throwable t) {
                return;
            }
            try {
                reloadConfig();
                logger.info("[ModsSite] Config file changed on disk — reloaded automatically");
            } catch (Throwable t) {
                logger.error("[ModsSite] Cannot reload changed config — keeping previous", t);
                configLoadedMtime = mtime; // не повторять ошибку на каждом запросе
            }
        }
    }

    /** Перезагрузить конфиг (команда mods reload). */
    public synchronized void reloadConfig() throws IOException {
        configurable.loadConfig();
        config.normalize();
        ModsSiteLogLevels.apply(config.logging.consoleLevel);
        migratePasswordsToHashes();
        saveConfig();
        ipBans.setConfig(config);
        sessions.setConfig(config);
        // Auth-сервисы тоже держали ссылку на СТАРЫЙ экземпляр конфига:
        // правки launcherAuth.adminCheck (type=sql, query, url, trueValues),
        // auth.mode, superAdmins, extraTotp не применялись при reloadConfig —
        // выполнялся прежний SQL-запрос, и все админы из БД получали viewer.
        if (launcherAuthChecker != null) launcherAuthChecker.setConfig(config);
        if (authService != null) authService.setConfig(config);
        audit.setEnabled(config.audit.enabled);
        enabled = config.enabled;
        // КРИТИЧНО: сервисы и провайдеры держали ссылки на под-конфиги СТАРОГО
        // экземпляра — раньше apiKey/baseUrl, поправленные в Config.json,
        // НЕ применялись после mods reload (CurseForge оставался со старым
        // ключом вплоть до перезапуска сервера). Подменяем конфиги на живые.
        modrinthProvider.updateConfig(config.mods.modrinth);
        curseforgeProvider.updateConfig(config.mods.curseforge);
        updates.updateConfig(config);
        modpacks.updateConfig(config);
        imports.updateConfig(config);
        backups.updateConfig(config);
        modrinthProvider.clearCache();
        curseforgeProvider.clearCache();
        logger.info("[ModsSite] config reloaded");
        recordConfigMtime();
    }

    public void close(ClosePhase phase) {
        if (severletPair != null) {
            NettyWebAPIHandler.removeSeverlet(severletPair);
        }
        if (rootSeverletPair != null) {
            NettyWebAPIHandler.removeSeverlet(rootSeverletPair);
        }
        if (updates != null) {
            updates.shutdown();
        }
        if (imports != null) {
            imports.shutdown();
        }
        if (backups != null) {
            backups.shutdown();
        }
    }
}
