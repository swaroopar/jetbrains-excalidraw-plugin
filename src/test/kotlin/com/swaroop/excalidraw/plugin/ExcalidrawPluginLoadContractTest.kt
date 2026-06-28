package com.swaroop.excalidraw.plugin

import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Load-time contract tests that mirror the checks the IntelliJ platform runs when it
 * loads the plugin, so registration mistakes fail in CI instead of only in a running IDE.
 *
 * Two real plugin-load crashes motivated this:
 *  1. `getComponentAdapterOfType is used to get Function0 ...` — a `<fileEditorProvider>`
 *     with no platform-resolvable constructor (constructor-injection failure).
 *  2. `HIDE_DEFAULT_EDITOR is supported only for DumbAware providers` —
 *     [com.intellij.openapi.fileEditor.impl.FileEditorProviderManagerImplKt.checkPolicy].
 *
 * Rather than hard-code the provider class, this reads the real META-INF/plugin.xml from
 * the classpath and checks every registered `<fileEditorProvider>`, so a future provider
 * is covered automatically. No IDE bootstrap / no MockK.
 */
class ExcalidrawPluginLoadContractTest {

    /** Policies that the platform's checkPolicy() requires to also be DumbAware. */
    private val dumbAwareRequiredPolicies = setOf(
        FileEditorPolicy.HIDE_DEFAULT_EDITOR,
        FileEditorPolicy.HIDE_OTHER_EDITORS,
    )

    private fun registeredFileEditorProviderClasses(): List<String> {
        val xml = javaClass.getResourceAsStream("/META-INF/plugin.xml")
            ?: error("META-INF/plugin.xml not found on the test classpath")
        val doc = xml.use {
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it)
        }
        val nodes = doc.getElementsByTagName("fileEditorProvider")
        return (0 until nodes.length).mapNotNull { i ->
            (nodes.item(i) as? org.w3c.dom.Element)?.getAttribute("implementation")
                ?.takeIf { it.isNotBlank() }
        }
    }

    /**
     * For every registered fileEditorProvider: it must be instantiable via the no-arg
     * constructor the platform uses, and — if it declares a hiding policy — it must be
     * DumbAware. Emits one dynamic test per provider so failures name the offending class.
     */
    @TestFactory
    fun `every registered fileEditorProvider satisfies the platform load contract`(): List<DynamicTest> {
        val classes = registeredFileEditorProviderClasses()
        assertTrue(classes.isNotEmpty(), "expected at least one <fileEditorProvider> in plugin.xml")

        return classes.map { fqn ->
            DynamicTest.dynamicTest(fqn) {
                val clazz = Class.forName(fqn)

                // 1) Constructor injection: the platform needs a no-arg constructor.
                val noArg = clazz.getDeclaredConstructor()
                val instance = noArg.newInstance()
                assertNotNull(instance, "$fqn must instantiate via its no-arg constructor")
                assertTrue(
                    instance is FileEditorProvider,
                    "$fqn must implement FileEditorProvider",
                )

                // 2) checkPolicy(): a hiding policy is only allowed for DumbAware providers.
                val policy = (instance as FileEditorProvider).policy
                if (policy in dumbAwareRequiredPolicies) {
                    assertTrue(
                        instance is DumbAware,
                        "$fqn declares policy $policy, which the platform allows only for " +
                            "DumbAware providers — it must implement com.intellij.openapi.project.DumbAware",
                    )
                }
            }
        }
    }
}
