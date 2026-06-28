package com.swaroop.excalidraw.plugin

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNotNull
import java.io.File
import java.util.Properties

/**
 * Gradle Smoke Test for task-01-001.
 *
 * Verifies the Gradle project skeleton satisfies the acceptance criteria:
 *   - gradle.properties contains pluginSinceBuild=241
 *   - gradle.properties contains javaVersion=17
 *   - build.gradle.kts references org.jetbrains.intellij.platform
 *   - settings.gradle.kts declares the project name
 */
class GradleSmokeTest {

    private val projectRoot: File = File(System.getProperty("user.dir"))

    @Test
    fun `gradle properties contains pluginSinceBuild 241`() {
        val props = loadGradleProperties()
        val sinceBuild = props.getProperty("pluginSinceBuild")
        assertNotNull(sinceBuild, "gradle.properties must contain pluginSinceBuild")
        assertTrue(sinceBuild == "241", "pluginSinceBuild must be 241, got: $sinceBuild")
    }

    @Test
    fun `gradle properties contains javaVersion 17`() {
        val props = loadGradleProperties()
        val javaVersion = props.getProperty("javaVersion")
        assertNotNull(javaVersion, "gradle.properties must contain javaVersion")
        assertTrue(javaVersion == "17", "javaVersion must be 17, got: $javaVersion")
    }

    @Test
    fun `build gradle kts references intellij platform plugin`() {
        val buildFile = File(projectRoot, "build.gradle.kts")
        assertTrue(buildFile.exists(), "build.gradle.kts must exist")
        val content = buildFile.readText()
        assertTrue(
            content.contains("org.jetbrains.intellij.platform"),
            "build.gradle.kts must reference org.jetbrains.intellij.platform"
        )
    }

    @Test
    fun `build gradle kts configures buildPlugin task via intellij platform plugin`() {
        val buildFile = File(projectRoot, "build.gradle.kts")
        assertTrue(buildFile.exists(), "build.gradle.kts must exist")
        val content = buildFile.readText()
        // The intellij platform plugin v2 provides buildPlugin task when configured correctly
        assertTrue(
            content.contains("intellijPlatform"),
            "build.gradle.kts must configure intellijPlatform block"
        )
    }

    @Test
    fun `settings gradle kts declares project name`() {
        val settingsFile = File(projectRoot, "settings.gradle.kts")
        assertTrue(settingsFile.exists(), "settings.gradle.kts must exist")
        val content = settingsFile.readText()
        assertTrue(
            content.contains("rootProject.name"),
            "settings.gradle.kts must declare rootProject.name"
        )
    }

    // task-01-002: npm-Workspace and Excalidraw-Bundle verification
    @Test
    fun `excalidraw bundle package json exists with excalidraw dependency`() {
        val packageJson = File(projectRoot, "excalidraw-bundle/package.json")
        assertTrue(packageJson.exists(), "excalidraw-bundle/package.json must exist")
        val content = packageJson.readText()
        assertTrue(
            content.contains("@excalidraw/excalidraw"),
            "package.json must declare @excalidraw/excalidraw dependency"
        )
    }

    @Test
    fun `excalidraw bundle webpack config exists`() {
        val webpackConfig = File(projectRoot, "excalidraw-bundle/webpack.config.js")
        assertTrue(webpackConfig.exists(), "excalidraw-bundle/webpack.config.js must exist")
    }

    @Test
    fun `build gradle kts defines buildWebBundle task`() {
        val buildFile = File(projectRoot, "build.gradle.kts")
        assertTrue(buildFile.exists(), "build.gradle.kts must exist")
        val content = buildFile.readText()
        assertTrue(
            content.contains("buildWebBundle"),
            "build.gradle.kts must define buildWebBundle task"
        )
    }

    @Test
    fun `webview index html exists after bundle build`() {
        val indexHtml = File(projectRoot, "src/main/resources/webview/index.html")
        assertTrue(
            indexHtml.exists(),
            "src/main/resources/webview/index.html must exist after buildWebBundle — run ./gradlew buildWebBundle first"
        )
        assertTrue(indexHtml.length() > 0, "src/main/resources/webview/index.html must be non-empty")
    }

    @Test
    fun `gradle wrapper properties references gradle 8`() {
        val wrapperProps = File(projectRoot, "gradle/wrapper/gradle-wrapper.properties")
        assertTrue(wrapperProps.exists(), "gradle/wrapper/gradle-wrapper.properties must exist")
        val content = wrapperProps.readText()
        assertTrue(
            content.contains("gradle-8"),
            "Gradle wrapper must reference Gradle 8.x, content: $content"
        )
    }

    private fun loadGradleProperties(): Properties {
        val propsFile = File(projectRoot, "gradle.properties")
        assertTrue(propsFile.exists(), "gradle.properties must exist at project root")
        return Properties().apply { propsFile.inputStream().use { load(it) } }
    }
}
