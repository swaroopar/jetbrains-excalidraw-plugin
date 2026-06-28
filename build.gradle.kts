plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.6.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

// javaVersion in gradle.properties is the authoritative compile target (sinceBuild=241 → 17).
kotlin {
    jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.swaroop.excalidraw.plugin"
        name = "Excalidraw"
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // Open-ended upper bound: the plugin uses stable platform APIs, so don't cap
            // the IDE build. Prevents "incompatible with build NNN" errors when the IDE is
            // upgraded (e.g. WS-261). Set a concrete pluginUntilBuild only if a future
            // platform change requires it.
            untilBuild = provider { null }
        }
    }

    // buildSearchableOptions launches a headless IDE to pre-index the Settings UI for
    // search. It is optional (the plugin is fully functional without it) and crashes on
    // recent JetBrains Runtimes (ClassNotFoundException: MultiRoutingFileSystemProvider),
    // which broke plain `./gradlew buildPlugin`. Disable it so the documented build works
    // out of the box, locally and in CI, without needing -x buildSearchableOptions.
    buildSearchableOptions = false
}

// localIdePath can be set in ~/.gradle/gradle.properties (never committed) for offline builds.
// Example: localIdePath=/Applications/WebStorm.app/Contents
// When absent, CI uses the remote WebStorm SDK declared in platformVersion.
val localIdePath: String? = providers.gradleProperty("localIdePath").orNull

dependencies {
    intellijPlatform {
        if (localIdePath != null) {
            // Offline / local IDE build. macOS: pass the Contents/ directory explicitly
            // because the plugin does not resolve the macOS app-bundle layout automatically.
            local(localIdePath)
        } else {
            webstorm(providers.gradleProperty("platformVersion").get())
        }
    }

    testImplementation("org.junit.jupiter:junit-jupiter:6.1.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// buildWebBundle: installs npm dependencies and produces the Excalidraw web bundle
// into src/main/resources/webview/ (via webpack output path in webpack.config.js).
// AD-2: the bundle is loaded by JCEF via a custom excalidraw:// scheme — no remote URL.
// Uses sh -c so that '&&' is interpreted by the shell on POSIX systems (macOS/Linux).
// npm ci requires package-lock.json; npm install bootstraps it on first run.
val buildWebBundle by tasks.registering(Exec::class) {
    group = "build"
    description = "Install npm deps and build Excalidraw web bundle into src/main/resources/webview/."
    workingDir = file("excalidraw-bundle")
    commandLine(
        "sh", "-c",
        "if [ -f package-lock.json ]; then npm ci; else npm install; fi && npm run build"
    )
    inputs.files(fileTree("excalidraw-bundle/src"))
    inputs.file("excalidraw-bundle/package.json")
    inputs.file("excalidraw-bundle/webpack.config.js")
    outputs.dir("src/main/resources/webview")
}

tasks {
    // processResources depends on buildWebBundle so that the web assets are bundled
    // before they are copied into the plugin jar.
    named("processResources") {
        dependsOn(buildWebBundle)
    }

    // Same local-IDE nio-fs.jar bootclasspath workaround as the test task, so
    // `./gradlew runIde` also works against a local macOS IDE bundle (localIdePath).
    // No effect on CI (localIdePath is null there).
    named<JavaExec>("runIde") {
        if (localIdePath != null) {
            jvmArgs("-Xbootclasspath/a:$localIdePath/lib/nio-fs.jar")
        }
    }

    test {
        useJUnitPlatform()
        systemProperty("user.dir", projectDir.absolutePath)

        // When using a local macOS IDE bundle, the IntelliJ Platform Gradle Plugin v2 computes
        // the nio-fs.jar bootclasspath without the Contents/ segment (plugin bug).
        // Apply the correct path only when localIdePath is set.
        if (localIdePath != null) {
            jvmArgs("-Xbootclasspath/a:$localIdePath/lib/nio-fs.jar")
        }
    }
}
