package pro.gravit.launchermodules.modssite.mods;

import java.util.ArrayList;
import java.util.List;

/** Унифицированные данные о версии мода (Modrinth / CurseForge). */
public class RemoteVersionInfo {
    public String source;         // modrinth | curseforge
    public String projectId;
    public String versionId;      // MR version id | CF file id
    public String versionNumber;  // MR version_number | CF displayName
    public String fileName;
    public String url;
    public String sha1;
    public String sha512;
    public long size;
    public long datePublished;
    public String releaseType = "release"; // release | beta | alpha
    public List<RemoteDependency> dependencies = new ArrayList<>();
    public List<String> gameVersions = new ArrayList<>();
    public List<String> loaders = new ArrayList<>();

    public static class RemoteDependency {
        public String source;
        public String projectId;
        /** required | optional | incompatible */
        public String type;

        public RemoteDependency() {
        }

        public RemoteDependency(String source, String projectId, String type) {
            this.source = source;
            this.projectId = projectId;
            this.type = type;
        }
    }

    public boolean hasHash() {
        return sha1 != null && !sha1.isBlank() || sha512 != null && !sha512.isBlank();
    }
}
