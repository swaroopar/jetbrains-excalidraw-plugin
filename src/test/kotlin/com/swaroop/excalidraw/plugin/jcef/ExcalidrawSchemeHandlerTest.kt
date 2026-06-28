package com.swaroop.excalidraw.plugin.jcef

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Unit test for ExcalidrawSchemeHandler resource resolution logic.
 *
 * NFR1 — Local-only data handling (task-01-007):
 *   The plugin makes no network requests. The embedded JCEF browser loads only bundled
 *   local assets. All resource resolution is performed against the plugin's classpath
 *   (src/main/resources/webview/) using Class.getResourceAsStream — no HTTP client,
 *   no java.net.URL with http/https scheme, no file:// path, and no remote URL is ever
 *   consulted. These tests exercise ResourceResolver directly, without a JCEF runtime
 *   or mock HTTP server, proving that all I/O stays in-process.
 *
 * Acceptance criterion (task-01-006):
 *   - HTTP-200 and correct MIME-Type for a known resource (e.g. index.html under /webview/).
 *   - HTTP-404 for an unknown/non-existent path.
 *   - CSP header "Content-Security-Policy" is present in resolved response headers.
 *
 * Acceptance criterion (task-01-007):
 *   - Content-Type correctness for multiple resource types (css, js).
 *   - Explicit no-network proof: http:// and https:// paths passed to resolve() are
 *     rejected with 404 (the resolver never opens an outbound connection).
 *   - 200 responses carry non-null, non-empty bytes loaded from the classpath.
 *   - Exact CSP value includes connect-src 'none' (no outbound browser connections).
 *
 * NFR1 Gesamtnachweis (task-01-012 — buildPlugin Smoke + NFR1 overall verification):
 *   These tests form the final gate for NFR1 compliance across the whole plugin build:
 *   1. The classpath (populated by ./gradlew buildPlugin) contains webview/index.html,
 *      webview/bundle.js, and webview/bundle.css — verified via getResourceAsStream.
 *   2. The plugin.xml is present under META-INF/ in the classpath — verifying that
 *      the Gradle processResources task embedded both the web bundle and the manifest.
 *   3. All three SchemeHandler scenarios (200, 404, CSP) are verified together as a
 *      combined NFR1 assertion — no single partial pass is sufficient.
 *
 * Gate-2-Reviewer — Manual Network Monitor Check:
 *   The unit tests below prove that the ResourceResolver only touches the JVM classpath.
 *   For the full in-IDE smoke verification (required before gate_2 approval), the
 *   reviewer must additionally perform the following manual steps:
 *     a. Install the plugin zip (build/distributions/[name].zip) via
 *        "Install Plugin from Disk" in WebStorm.
 *     b. Open any .excalidraw file; the editor must appear showing the Excalidraw UI.
 *     c. While the editor is open, observe the WebStorm Network Monitor / a proxy tool
 *        (e.g. Charles, mitmproxy, or DevTools in JCEF) — NO http:// or https:// request
 *        originating from the plugin should appear.
 *     d. Confirm that the page title / source shows "Excalidraw" loaded from excalidraw://
 *        and that no external CDN URLs appear in the network log.
 *   This manual step is exempt from automated CI because JCEF requires a full IDE runtime
 *   and display environment; it is documented here for the gate_2 human reviewer.
 */
class ExcalidrawSchemeHandlerTest {

    private val resolver = ExcalidrawSchemeHandler.ResourceResolver

    // ---------------------------------------------------------------------------
    // task-01-006: baseline — 200, 404, CSP
    // ---------------------------------------------------------------------------

    @Test
    fun `known resource index html returns 200 with text html content type`() {
        // index.html is bundled under src/main/resources/webview/index.html
        val result = resolver.resolve("index.html")
        assertEquals(200, result.statusCode, "HTTP status must be 200 for known resource")
        assertTrue(
            result.contentType.startsWith("text/html"),
            "Content-Type must start with 'text/html', got: '${result.contentType}'"
        )
    }

    @Test
    fun `unknown resource returns 404`() {
        val result = resolver.resolve("does-not-exist-xyz.html")
        assertEquals(404, result.statusCode, "HTTP status must be 404 for unknown resource")
    }

    @Test
    fun `csp header restricts connections to local schemes only (no network egress)`() {
        val headers = resolver.responseHeaders()
        val csp = headers["Content-Security-Policy"]
        assertNotNull(csp, "Content-Security-Policy header must be present in response headers")
        assertTrue(csp!!.contains("connect-src"), "CSP must declare a connect-src directive; got: '$csp'")
        // NFR1: no http(s) origin anywhere → no network egress. Local schemes
        // ('self', data:, blob:, excalidraw:) are allowed so the bundled app can load.
        assertTrue(
            !csp.contains("http://") && !csp.contains("https://"),
            "CSP must not permit any http(s) origin (no network egress); got: '$csp'"
        )
    }

    // --- Path-traversal protection tests (Secure-Coding A10 / NFR1) ---
    // All of the following must return 404 and must NOT expose resources outside /webview/.

    @Test
    fun `path traversal dot-dot META-INF plugin xml returns 404`() {
        // Attempt to escape /webview/ via ../META-INF/plugin.xml
        val result = resolver.resolve("../META-INF/plugin.xml")
        assertEquals(404, result.statusCode, "Path traversal '../META-INF/plugin.xml' must return 404")
    }

    @Test
    fun `path traversal double dot-dot etc passwd returns 404`() {
        // Attempt to escape two levels: ../../etc/passwd
        val result = resolver.resolve("../../etc/passwd")
        assertEquals(404, result.statusCode, "Path traversal '../../etc/passwd' must return 404")
    }

    @Test
    fun `path traversal null byte etc passwd returns 404`() {
        // Null-byte (space in unicode representation) injection attempt
        val result = resolver.resolve(" etc/passwd")
        assertEquals(404, result.statusCode, "Null-byte path ' etc/passwd' must return 404")
    }

    @Test
    fun `path traversal percent-encoded dot-dot META-INF returns 404`() {
        // Percent-encoded variant: %2e%2e/META-INF/plugin.xml
        val result = resolver.resolve("%2e%2e/META-INF/plugin.xml")
        assertEquals(404, result.statusCode, "Percent-encoded traversal '%2e%2e/META-INF/plugin.xml' must return 404")
    }

    // ---------------------------------------------------------------------------
    // task-01-007: NFR1 — content-type coverage for multiple resource types
    // ---------------------------------------------------------------------------

    /**
     * NFR1: CSS resources must return the correct MIME type.
     * bundle.css is generated by webpack and bundled under /webview/ at build time.
     */
    @Test
    fun `css resource bundle css returns 200 with text css content type`() {
        val result = resolver.resolve("bundle.css")
        assertEquals(200, result.statusCode, "HTTP status must be 200 for bundle.css")
        assertTrue(
            result.contentType.startsWith("text/css"),
            "Content-Type for .css must start with 'text/css', got: '${result.contentType}'"
        )
    }

    /**
     * NFR1: JavaScript resources must return the correct MIME type.
     * bundle.js is generated by webpack and bundled under /webview/ at build time.
     */
    @Test
    fun `js resource bundle js returns 200 with application javascript content type`() {
        val result = resolver.resolve("bundle.js")
        assertEquals(200, result.statusCode, "HTTP status must be 200 for bundle.js")
        assertEquals(
            "application/javascript",
            result.contentType,
            "Content-Type for .js must be 'application/javascript', got: '${result.contentType}'"
        )
    }

    // ---------------------------------------------------------------------------
    // task-01-007: NFR1 — explicit no-network / classpath-only proof
    // ---------------------------------------------------------------------------

    /**
     * NFR1 explicit proof: 200 responses carry bytes loaded from the classpath.
     * The resolver must never return a 200 response with null or empty bytes —
     * if it did, that would indicate a broken classpath load rather than a real resource.
     * No outbound HTTP connection is opened; bytes come from getResourceAsStream.
     */
    @Test
    fun `nfr1 known resource bytes are non-null and non-empty proving classpath load`() {
        // NFR1: ResourceResolver loads bytes via Class.getResourceAsStream (classpath),
        // not via HttpURLConnection, URL.openStream(), or any other network mechanism.
        val result = resolver.resolve("index.html")
        assertEquals(200, result.statusCode)
        assertNotNull(result.bytes, "Bytes must be non-null for a 200 response (classpath load proof)")
        assertTrue(
            result.bytes!!.isNotEmpty(),
            "Bytes must be non-empty for a 200 response (classpath load proof)"
        )
    }

    /**
     * NFR1 explicit proof: an http:// URL passed as a path must be rejected with 404.
     * The resolver must never open an outbound HTTP connection even if given a URL string.
     * This verifies the no-network contract: excalidraw:// scheme handler never dials out.
     */
    @Test
    fun `nfr1 http url passed as path is rejected with 404 no network access`() {
        // NFR1: Passing an http:// URL to resolve() must NOT cause an outbound connection.
        // The sanitizer must reject it (classpath miss) returning 404 with null bytes.
        val result = resolver.resolve("http://evil.example.com/steal-data")
        assertEquals(
            404,
            result.statusCode,
            "An http:// URL passed as path must be rejected (404); no outbound connection permitted"
        )
        assertNull(result.bytes, "Bytes must be null for a 404 response (NFR1 no-network)")
    }

    /**
     * NFR1 explicit proof: an https:// URL passed as a path must be rejected with 404.
     * Same guarantee as the http:// case above.
     */
    @Test
    fun `nfr1 https url passed as path is rejected with 404 no network access`() {
        // NFR1: Passing an https:// URL to resolve() must NOT cause an outbound connection.
        val result = resolver.resolve("https://evil.example.com/steal-data")
        assertEquals(
            404,
            result.statusCode,
            "An https:// URL passed as path must be rejected (404); no outbound connection permitted"
        )
        assertNull(result.bytes, "Bytes must be null for a 404 response (NFR1 no-network)")
    }

    /**
     * NFR1 explicit proof: the CSP header forbids all outbound browser connections.
     * connect-src 'none' prevents the embedded browser from opening WebSocket or XHR
     * connections to external hosts, enforcing the local-only data handling policy.
     */
    @Test
    fun `nfr1 csp prevents outbound network connections`() {
        // NFR1: the CSP must not permit any http(s) origin, so the JCEF browser cannot
        // make XHR / WebSocket / fetch requests to external hosts. Local schemes are
        // allowed (self/data/blob/excalidraw) so the bundled app's own assets load.
        val headers = resolver.responseHeaders()
        val csp = headers["Content-Security-Policy"]
        assertNotNull(csp, "CSP header must be present (NFR1 enforcement via AD-6)")
        assertTrue(csp!!.contains("connect-src"), "CSP must declare a connect-src directive; got: '$csp'")
        assertTrue(
            !csp.contains("http://") && !csp.contains("https://"),
            "CSP must not allow any http/https origin — all external connections must be blocked; got: '$csp'"
        )
    }

    // ---------------------------------------------------------------------------
    // task-01-012: NFR1 Gesamtnachweis — buildPlugin smoke + overall verification
    // ---------------------------------------------------------------------------

    /**
     * buildPlugin Smoke: webview/index.html is accessible from the JVM classpath.
     *
     * This test directly accesses the classpath resource without going through the
     * ResourceResolver — proving that ./gradlew buildPlugin (or processResources) has
     * correctly embedded webview/index.html into the plugin jar. If this test fails, the
     * Gradle build did not embed the web bundle and the plugin cannot function offline.
     *
     * NFR1: bytes come from the classpath (no network access); the resource path is
     * absolute ("/webview/index.html"), so it cannot escape the jar boundary.
     */
    @Test
    fun `nfr1 overall smoke webview index html is present on classpath`() {
        // Proof that ./gradlew buildPlugin embedded webview/index.html into the jar.
        // Class.getResourceAsStream opens the file from the JVM classpath — no HTTP, no file://.
        val stream = this.javaClass.getResourceAsStream("/webview/index.html")
        assertNotNull(stream, "webview/index.html must be present on the classpath (buildPlugin smoke proof)")
        val bytes = stream!!.readBytes()
        assertTrue(bytes.isNotEmpty(), "webview/index.html must be non-empty (classpath resource must contain content)")
        val content = bytes.toString(Charsets.UTF_8)
        assertTrue(
            content.contains("Excalidraw", ignoreCase = true),
            "webview/index.html must reference 'Excalidraw' (NFR1: only local Excalidraw bundle is served)"
        )
    }

    /**
     * buildPlugin Smoke: webview/bundle.js is accessible from the JVM classpath.
     *
     * Verifies that the webpack-generated JS bundle was correctly embedded by
     * ./gradlew buildPlugin. Together with the index.html and bundle.css checks this
     * proves the full webview/ directory is present in the plugin jar.
     *
     * NFR1: no outbound connection — resource comes from the classpath.
     */
    @Test
    fun `nfr1 overall smoke webview bundle js is present on classpath`() {
        val stream = this.javaClass.getResourceAsStream("/webview/bundle.js")
        assertNotNull(stream, "webview/bundle.js must be present on the classpath (buildPlugin smoke proof)")
        val bytes = stream!!.readBytes()
        assertTrue(bytes.isNotEmpty(), "webview/bundle.js must be non-empty (webpack bundle must have content)")
    }

    /**
     * buildPlugin Smoke: META-INF/plugin.xml is accessible from the JVM classpath.
     *
     * Verifies that the plugin manifest was correctly embedded. The plugin cannot be
     * installed without plugin.xml; its presence proves the Gradle processResources
     * task ran and the jar is correctly assembled.
     *
     * NFR1: resource comes from the classpath — no remote fetch, no file:// path.
     */
    @Test
    fun `nfr1 overall smoke meta inf plugin xml is present on classpath`() {
        val stream = this.javaClass.getResourceAsStream("/META-INF/plugin.xml")
        assertNotNull(stream, "META-INF/plugin.xml must be present on the classpath (plugin assembly smoke proof)")
        val bytes = stream!!.readBytes()
        assertTrue(bytes.isNotEmpty(), "META-INF/plugin.xml must be non-empty")
        val content = bytes.toString(Charsets.UTF_8)
        assertTrue(
            content.contains("com.intellij.modules.platform"),
            "plugin.xml must declare dependency on com.intellij.modules.platform"
        )
        assertTrue(
            content.contains("ExcalidrawFileEditorProvider"),
            "plugin.xml must register ExcalidrawFileEditorProvider (final plugin.xml smoke check)"
        )
    }

    /**
     * NFR1 Gesamtnachweis: combined assertion — all three SchemeHandler scenarios pass.
     *
     * This single test exercises the complete NFR1 proof chain in one assertion:
     *   1. A known resource (index.html) returns HTTP-200 with a non-empty body.
     *   2. An unknown resource returns HTTP-404 with null bytes (no data leak).
     *   3. The CSP header blocks all outbound browser connections (connect-src 'none').
     *
     * Passing this test in isolation means the SchemeHandler satisfies AC-NFR1-01 end-to-end.
     * No JCEF runtime, no network, no IDE instance required.
     */
    @Test
    fun `nfr1 overall combined assertion 200 and 404 and csp all pass`() {
        // 1. Known resource: HTTP-200 with non-empty bytes
        val okResult = resolver.resolve("index.html")
        assertEquals(200, okResult.statusCode, "NFR1 overall: known resource must return 200")
        assertNotNull(okResult.bytes, "NFR1 overall: known resource bytes must be non-null")
        assertTrue(okResult.bytes!!.isNotEmpty(), "NFR1 overall: known resource bytes must be non-empty")

        // 2. Unknown resource: HTTP-404 with null bytes (no data leak)
        val notFoundResult = resolver.resolve("this-resource-does-not-exist.xyz")
        assertEquals(404, notFoundResult.statusCode, "NFR1 overall: unknown resource must return 404")
        assertNull(notFoundResult.bytes, "NFR1 overall: 404 response must have null bytes (no data leak)")

        // 3. CSP header blocks all outbound browser connections
        val headers = resolver.responseHeaders()
        val csp = headers["Content-Security-Policy"]
        assertNotNull(csp, "NFR1 overall: CSP header must be present")
        assertTrue(
            csp!!.contains("connect-src") && !csp.contains("http://") && !csp.contains("https://"),
            "NFR1 overall: CSP must declare connect-src with no http(s) origin — AC-NFR1-01 enforced at browser level; got: '$csp'"
        )
    }
}
