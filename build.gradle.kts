plugins {
    id("java")
}

group = "pro.gravit.launchermodules"
version = "1.1.2"

// Toolchain 21 — как LaunchServer 5.7.x: его jar'ы в lib/ собраны байткодом
// Java 21, и javac более старого JDK не может их читать ("class file has wrong
// version"). Нужного JDK нет? Скачается сам (foojay).
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

// Модуль запускается внутри LaunchServer, поэтому все зависимости — compileOnly.
// Сборка — ТОЛЬКО против jar'ов вашей сборки LaunchServer: распакуйте
// LaunchServerBuild.zip из релиза GravitLauncher и укажите путь:\n//   ./gradlew build -PlaunchserverDir=/путь/к/LaunchServer
// (путь также можно задать один раз в gradle.properties).
val launchserverDirProp = providers.gradleProperty("launchserverDir")
    .orElse(providers.systemProperty("launchserverDir"))
    .orElse("../LaunchServer")
val launchserverDir = file(launchserverDirProp.get())

if (!launchserverDir.resolve("lib").isDirectory) {
    throw GradleException("""[ModsSite] LaunchServer lib dir not found at "${launchserverDir.resolve("lib")}".
[ModsSite] Pass the path to an unpacked LaunchServer build (LaunchServerBuild.zip
[ModsSite] from the GravitLauncher release):
[ModsSite]     ./gradlew build -PlaunchserverDir=/path/to/LaunchServer""")
}

dependencies {
    // Все jar'ы из lib/ сборки LaunchServer (точное совпадение с рантаймом)
    compileOnly(fileTree(launchserverDir.resolve("lib")) { include("*.jar") })
}

tasks.jar {
    // Имя обязано заканчиваться на _module.jar — так ожидает модульная система
    archiveFileName.set("ModsSite_module.jar")
    manifest.attributes.put("Module-Main-Class", "pro.gravit.launchermodules.modssite.ModsSiteModule")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
