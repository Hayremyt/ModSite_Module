package pro.gravit.launchermodules.modssite.mods;

import java.util.ArrayList;
import java.util.List;

/**
 * Модпак профиля (config/ModsSite/modpacks/<dir>.json).
 */
public class Modpack {
    /** Переопределение версии игры ("" — из профиля). */
    public String gameVersion = "";
    /** Переопределение загрузчика: fabric | forge | neoforge | quilt | any ("" — авто). */
    public String loader = "";
    /** Переопределение поддиректории модов ("" — mods.modsSubdir). */
    public String modsDir = "";
    /** Переопределение поддиректории ресурспаков ("" — mods.resourcepacksSubdir). */
    public String resourcepacksDir = "";
    /** Переопределение поддиректории шейдеров ("" — mods.shadersSubdir). */
    public String shaderpacksDir = "";
    public List<ModEntry> mods = new ArrayList<>();

    public ModEntry find(String id) {
        if (id == null) return null;
        for (ModEntry entry : mods) {
            if (id.equals(entry.id)) return entry;
        }
        return null;
    }

    public ModEntry findByProject(String source, String projectId) {
        if (source == null || projectId == null) return null;
        for (ModEntry entry : mods) {
            if (source.equals(entry.source) && (projectId.equals(entry.projectId)
                    || projectId.equals(entry.id))) {
                return entry;
            }
        }
        return null;
    }
}
