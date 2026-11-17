package pro.gravit.launchermodules.modssite.mods;

import pro.gravit.launchermodules.modssite.ModsSiteConfig;

import java.util.List;
import java.util.Locale;

/**
 * Типы контента модпака: моды, ресурспаки и шейдеры.
 * Все три типа проходят один и тот же конвейер (поиск/установка/обновления/
 * опциональность/предложения) и отличаются только целевой папкой профиля
 * и фильтрами поиска у источников.
 *
 * <p>Значения: "mod" | "resourcepack" | "shader" (совпадают с project_type
 * Modrinth; у CurseForge — classId 6 / 12 / 6552). Записи модпаков старых
 * версий модуля не имеют поля kind — для них тип всегда "mod".
 */
public final class Kinds {
    public static final String MOD = "mod";
    public static final String RESOURCEPACK = "resourcepack";
    public static final String SHADER = "shader";

    public static final List<String> ALL = List.of(MOD, RESOURCEPACK, SHADER);

    private Kinds() {
    }

    /** Нормализовать тип: неизвестное/пустое → "mod" (обратная совместимость). */
    public static String normalize(String kind) {
        if (kind == null) return MOD;
        String value = kind.trim().toLowerCase(Locale.ROOT);
        return RESOURCEPACK.equals(value) || SHADER.equals(value) ? value : MOD;
    }

    /** Валидное ли значение типа. */
    public static boolean isValid(String kind) {
        return kind != null && ALL.contains(kind.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Поддиректория профиля для типа контента с учётом переопределений
     * модпака (modsDir/resourcepacksDir/shaderpacksDir) и конфига.
     */
    public static String subdir(ModsSiteConfig config, Modpack modpack, String kind) {
        String normalized = normalize(kind);
        String override = null;
        String fallback;
        switch (normalized) {
            case RESOURCEPACK -> {
                if (modpack != null) override = modpack.resourcepacksDir;
                fallback = config.mods.resourcepacksSubdir;
            }
            case SHADER -> {
                if (modpack != null) override = modpack.shaderpacksDir;
                fallback = config.mods.shadersSubdir;
            }
            default -> {
                if (modpack != null) override = modpack.modsDir;
                fallback = config.mods.modsSubdir;
            }
        }
        String subdir = override != null && !override.isBlank() ? override : fallback;
        subdir = subdir == null ? "" : subdir.replace('\\', '/');
        while (subdir.startsWith("/")) subdir = subdir.substring(1);
        while (subdir.endsWith("/")) subdir = subdir.substring(0, subdir.length() - 1);
        return subdir;
    }

    /**
     * Применим ли фильтр загрузчика (fabric/forge/…) к типу: у модов — да;
     * у ресурспаков загрузчика нет (Modrinth использует "minecraft"),
     * у шейдеров «загрузчик» — это iris/optifine/canvas, что учитывается
     * отдельно в поиске; при проверке обновлений фильтр для них отключается.
     */
    public static boolean loaderApplicable(String kind) {
        return MOD.equals(normalize(kind));
    }

    /**
     * Шаблоны игнорирования (.gitignore) для папки контента типа:
     * modsIgnore / resourcepacksIgnore / shadersIgnore. См. {@link IgnoreMatcher}.
     */
    public static List<String> ignorePatterns(ModsSiteConfig config, String kind) {
        if (config == null || config.mods == null) return List.of();
        String normalized = normalize(kind);
        List<String> patterns;
        switch (normalized) {
            case RESOURCEPACK -> patterns = config.mods.resourcepacksIgnore;
            case SHADER -> patterns = config.mods.shadersIgnore;
            default -> patterns = config.mods.modsIgnore;
        }
        return patterns == null ? List.of() : patterns;
    }
}
