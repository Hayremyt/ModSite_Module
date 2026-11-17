package pro.gravit.launchermodules.modssite.commands;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pro.gravit.launcher.base.profiles.ClientProfile;
import pro.gravit.launcher.base.profiles.optional.OptionalFile;
import pro.gravit.launcher.base.profiles.optional.actions.OptionalAction;
import pro.gravit.launcher.base.profiles.optional.actions.OptionalActionFile;
import pro.gravit.launchermodules.modssite.ModsSiteModule;
import pro.gravit.launchermodules.modssite.mods.ModEntry;
import pro.gravit.launchermodules.modssite.mods.Modpack;
import pro.gravit.launchermodules.modssite.mods.ProfileOptionalSync;
import pro.gravit.launchserver.LaunchServer;
import pro.gravit.launchserver.command.Command;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * «mods profile optionals [профиль]» — диагностика опциональных модов профиля:
 * как модуль видит updateOptional (разобранный лаунчером + прочитанный из
 * profile.json напрямую) и почему конкретный мод модпака остаётся
 * «обязательным» (не совпало ни имя файла, ни имя записи).
 *
 * Команда появилась после ситуации «опциональные в профиле есть, а мод
 * всё равно обязательный»: без этого дампа невозможно понять, в каком виде
 * записи лежат в profile.json и что именно не совпадает.
 *
 * Примеры:
 * <pre>
 *   mods profile optionals           — все профили
 *   mods profile optionals testpack  — один профиль (dir)
 * </pre>
 */
public class ProfileCommand extends Command {
    private static final Logger logger = LogManager.getLogger(ProfileCommand.class);
    private final ModsSiteModule module;

    public ProfileCommand(LaunchServer server, ModsSiteModule module) {
        super(server);
        this.module = module;
        childCommands.put("optionals", new Optionals(server, module));
    }

    @Override
    public String getArgsDescription() {
        return "[optionals [profile]]";
    }

    @Override
    public String getUsageDescription() {
        return "Profile diagnostics: how updateOptional optionals are seen and why a mod is not optional";
    }

    @Override
    public void invoke(String... args) throws Exception {
        invokeSubcommands(args);
    }

    private static class Optionals extends Command {
        private final ModsSiteModule module;

        Optionals(LaunchServer server, ModsSiteModule module) {
            super(server);
            this.module = module;
        }

        @Override
        public String getArgsDescription() {
            return "[profile]";
        }

        @Override
        public String getUsageDescription() {
            return "Dump updateOptional entries (parsed + raw profile.json) and modpack optional matching";
        }

        @Override
        public void invoke(String... args) throws Exception {
            List<ClientProfile> profiles = new ArrayList<>(module.modpacks.profiles());
            if (args.length > 0) {
                ClientProfile profile = module.modpacks.findProfile(args[0]);
                if (profile == null) {
                    logger.warn("Профиль '{}' не найден. Доступные: {}", args[0],
                            profiles.stream().map(ClientProfile::getDir).toList());
                    return;
                }
                profiles = List.of(profile);
            }
            for (ClientProfile profile : profiles) {
                dumpProfile(profile);
            }
        }

        private void dumpProfile(ClientProfile profile) {
            ProfileOptionalSync sync = module.modpacks.optionalSync();
            ProfileOptionalSync.OptionalsView view = sync.view(profile);
            ProfileOptionalSync.RawOptionals raw = view.raw;
            Modpack modpack = module.modpacks.load(profile);

            logger.info("================================================================");
            logger.info("Профиль '{}' (dir: {})", profile.getTitle(), profile.getDir());
            logger.info("----------------------------------------------------------------");

            // 1. Разобранный лаунчером updateOptional
            logger.info("updateOptional в памяти лаунчера: {} записей", view.parsedEntries);
            for (OptionalFile file : profile.getOptional()) {
                if (file == null) continue;
                List<String> bound = new ArrayList<>();
                if (file.actions != null) {
                    for (OptionalAction action : file.actions) {
                        if (action instanceof OptionalActionFile actionFile && actionFile.files != null) {
                            bound.addAll(actionFile.files.keySet());
                        }
                    }
                }
                logger.info("  name='{}' visible={} mark={} привязки: {}",
                        file.name, file.visible, file.mark,
                        bound.isEmpty() ? "нет" : bound);
            }

            // 2. Сырой profile.json
            if (raw == null || raw.path == null) {
                logger.info("profile.json на диске: путь не определён (сторонний провайдер профилей?)");
            } else if (!raw.read) {
                logger.warn("profile.json на диске: {} — не прочитан{}",
                        raw.path,
                        raw.readError == null ? " (файл отсутствует?)" : " (" + raw.readError + ")");
            } else {
                logger.info("profile.json на диске: {}", raw.path);
                logger.info("  распознанные формы записей: {}",
                        raw.formats.isEmpty() ? "нет файловых привязок" : String.join("; ", raw.formats));
                logger.info("  записей: {}, привязок к файлам: {}, имён записей: {}",
                        raw.entries, raw.boundFileNames.size(), raw.entryNames.size());
                if (!raw.boundFileNames.isEmpty()) {
                    logger.info("  привязанные файлы: {}", raw.boundFileNames);
                }
                if (!raw.entryNames.isEmpty()) {
                    logger.info("  имена записей: {}", raw.entryNames);
                }
                if (view.parsedEntries == 0 && raw.entries > 0) {
                    logger.warn("  ВНИМАНИЕ: лаунчер не разобрал ни одной записи (updateOptional в памяти пуст),");
                    logger.warn("  но в файле они есть — профиль, вероятно, в нестандартном формате");
                    logger.warn("  (например, optionalMods[].list вместо updateOptional[].actions).");
                    logger.warn("  В таком случае чекбоксы опциональных модов НЕ работают и в самом лаунчере");
                    logger.warn("  — стоит привести profile.json к формату 5.7.x (см. README модуля).");
                }
            }

            // 3. Моды модпака: опциональные/обязательные и почему
            logger.info("----------------------------------------------------------------");
            logger.info("Модпак: {} модов", modpack.mods.size());
            Set<String> boundLower = lowerAll(view.boundFileNames);
            Set<String> namesLower = lowerAll(view.entryNames);
            int optionalCount = 0;
            for (ModEntry entry : modpack.mods) {
                if (entry == null) continue;
                if (entry.optional) {
                    optionalCount++;
                    continue;
                }
                String why;
                if (view.boundFileNames.isEmpty() && namesLower.isEmpty()) {
                    why = "в профиле нет ни привязок, ни имён записей";
                } else if (matchesFile(entry, boundLower)) {
                    why = "файл совпал по привязке — станет опциональным при открытии вкладки «Моды»";
                } else if (matchesName(entry, namesLower)) {
                    why = "имя совпало — станет опциональным при открытии вкладки «Моды»";
                } else {
                    why = String.format("нет совпадений: файл='%s' modId='%s' id='%s'",
                            entry.installedFileName, entry.installedModId, entry.id);
                }
                logger.info("  ОБЯЗАТЕЛЬНЫЙ {}: {}", entry.name == null ? entry.id : entry.name, why);
            }
            logger.info("Итого: опциональных {}, обязательных {}", optionalCount,
                    modpack.mods.size() - optionalCount);
            if (optionalCount == 0 && !namesLower.isEmpty() && !modpack.mods.isEmpty()) {
                logger.warn("В профиле есть записи updateOptional, но ни один мод модпака не опознан как опциональный.");
                logger.warn("Проверьте, что имена файлов/записей в updateOptional соответствуют модам.");
            }
        }

        private static boolean matchesFile(ModEntry entry, Set<String> boundLower) {
            if (entry.installedFileName != null && boundLower.contains(
                    entry.installedFileName.toLowerCase(Locale.ROOT))) {
                return true;
            }
            if (entry.previousFileNames != null) {
                for (String previous : entry.previousFileNames) {
                    if (previous != null && boundLower.contains(previous.toLowerCase(Locale.ROOT))) {
                        return true;
                    }
                }
            }
            return false;
        }

        private static boolean matchesName(ModEntry entry, Set<String> namesLower) {
            return namesLower.contains(lower(entry.id))
                    || namesLower.contains(lower(entry.profileOptionalName))
                    || namesLower.contains(lower(entry.installedModId));
        }

        private static String lower(String s) {
            return s == null ? null : s.trim().toLowerCase(Locale.ROOT);
        }

        private static Set<String> lowerAll(Set<String> values) {
            Set<String> result = new LinkedHashSet<>();
            for (String value : values) {
                String lower = lower(value);
                if (lower != null && !lower.isEmpty()) result.add(lower);
            }
            return result;
        }
    }
}
