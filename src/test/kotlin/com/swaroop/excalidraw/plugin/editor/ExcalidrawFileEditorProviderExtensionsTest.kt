package com.swaroop.excalidraw.plugin.editor

import com.intellij.serviceContainer.NonInjectable
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for the configurable extension injection in [ExcalidrawFileEditorProvider].
 *
 * All tests use [ExcalidrawFileEditorProvider.Companion.createForTest] to inject
 * a fixed extension list, bypassing [com.intellij.openapi.application.ApplicationManager]
 * entirely — no IDE platform bootstrap required (AC-E7-03, ADR-E7-05).
 *
 * Tests call [ExcalidrawFileEditorProvider.acceptsFile] (internal) rather than
 * [ExcalidrawFileEditorProvider.accept] to avoid requiring a live [com.intellij.openapi.project.Project]
 * stub. Both methods share the same extension-matching logic.
 *
 * VirtualFiles are replaced by [StubVirtualFile] carrying only a filename, consistent
 * with the established pattern in this package.
 *
 * No MockK / Mockito imports.
 */
class ExcalidrawFileEditorProviderExtensionsTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Creates a [StubVirtualFile] whose [StubVirtualFile.getName] returns [name]. */
    private fun stubFile(name: String): StubVirtualFile = StubVirtualFile(name, ByteArray(0))

    // -------------------------------------------------------------------------
    // AC-E7-03 — injected extension controls accept()
    // -------------------------------------------------------------------------

    /**
     * createForTest with only ".excalidraw" accepts "foo.excalidraw" but rejects
     * "foo.excalidraw.png" (that extension is not in the injected list).
     */
    @Test
    fun `createForTest with dot-excalidraw accepts only that extension`() {
        val provider = ExcalidrawFileEditorProvider.createForTest(listOf(".excalidraw"))

        assertTrue(
            provider.acceptsFile(stubFile("foo.excalidraw")),
            "acceptsFile() must return true for foo.excalidraw when .excalidraw is injected"
        )
        assertFalse(
            provider.acceptsFile(stubFile("foo.excalidraw.png")),
            "acceptsFile() must return false for foo.excalidraw.png when only .excalidraw is injected"
        )
        assertFalse(
            provider.acceptsFile(stubFile("foo.txt")),
            "acceptsFile() must return false for foo.txt regardless of injected list"
        )
    }

    /**
     * createForTest with ".drawio" accepts "foo.drawio" (AC-E7-03: added extension -> true)
     * and rejects "foo.excalidraw" (AC-E7-03: removed extension -> false).
     */
    @Test
    fun `createForTest with dot-drawio accepts drawio and rejects excalidraw`() {
        val provider = ExcalidrawFileEditorProvider.createForTest(listOf(".drawio"))

        assertTrue(
            provider.acceptsFile(stubFile("diagram.drawio")),
            "acceptsFile() must return true for diagram.drawio when .drawio is injected (AC-E7-03: added)"
        )
        assertFalse(
            provider.acceptsFile(stubFile("scene.excalidraw")),
            "acceptsFile() must return false for scene.excalidraw when .excalidraw is not injected (AC-E7-03: removed)"
        )
    }

    /**
     * createForTest with both default extensions preserves backward-compatible
     * behaviour (regression guard for AC-E7-02 + AC-E7-03).
     */
    @Test
    fun `createForTest with default extensions accepts both dot-excalidraw and dot-excalidraw-png`() {
        val provider = ExcalidrawFileEditorProvider.createForTest(
            listOf(".excalidraw", ".excalidraw.png")
        )

        assertTrue(
            provider.acceptsFile(stubFile("scene.excalidraw")),
            "acceptsFile() must return true for scene.excalidraw with default-injected extensions"
        )
        assertTrue(
            provider.acceptsFile(stubFile("scene.excalidraw.png")),
            "acceptsFile() must return true for scene.excalidraw.png with default-injected extensions"
        )
        assertFalse(
            provider.acceptsFile(stubFile("photo.png")),
            "acceptsFile() must return false for plain photo.png even with default-injected extensions"
        )
    }

    // -------------------------------------------------------------------------
    // Platform constructor-injection contract
    //
    // The IDE instantiates the provider from the <fileEditorProvider> registration
    // via constructor injection. It MUST find a no-arg constructor, and the
    // extensions-injecting constructor MUST be @NonInjectable — otherwise plugin load
    // fails at runtime with "getComponentAdapterOfType is used to get
    // kotlin.jvm.functions.Function0". These tests guard that contract; the failure is
    // invisible to the behavioural tests above (they use createForTest directly).
    // -------------------------------------------------------------------------

    /** The platform requires a public no-arg constructor to instantiate the provider. */
    @Test
    fun `has a no-arg constructor for platform instantiation`() {
        val ctor = ExcalidrawFileEditorProvider::class.java.getDeclaredConstructor()
        assertNotNull(ctor, "ExcalidrawFileEditorProvider must declare a no-arg constructor")
        // Must actually construct without throwing (headless-safe fallback to defaults).
        assertNotNull(ctor.newInstance(), "no-arg constructor must instantiate without an Application")
    }

    /** The (Function0) constructor must be @NonInjectable so the platform skips it. */
    @Test
    fun `extensions constructor is annotated NonInjectable`() {
        val ctor = ExcalidrawFileEditorProvider::class.java
            .getDeclaredConstructor(Function0::class.java)
        assertNotNull(
            ctor.getAnnotation(NonInjectable::class.java),
            "the (() -> List<String>) constructor must be @NonInjectable to avoid Function0 injection at plugin load"
        )
    }
}
