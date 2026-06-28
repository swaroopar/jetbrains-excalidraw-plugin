package com.swaroop.excalidraw.plugin.jcef

import com.intellij.openapi.diagnostic.logger
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefCallback
import org.cef.callback.CefSchemeHandlerFactory
import org.cef.handler.CefResourceHandler
import org.cef.handler.CefResourceHandlerAdapter
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.nio.file.Paths

/**
 * ExcalidrawSchemeHandler: serves `excalidraw://` requests from classpath resources
 * bundled under `/webview/`.
 *
 * Architecture Decisions enforced here:
 *   AD-2: Custom-Scheme instead of file:// — all resources are resolved from the
 *         plugin classpath, never from a file-system path or a remote URL.
 *   AD-6: Content-Security-Policy header is set on every response to enforce that
 *         the embedded browser cannot make external connections.
 *
 * NFR1 compliance:
 *   - No network requests are issued.
 *   - No file:// paths are constructed.
 *   - All resources come from /webview/ on the classpath (bundled in the plugin jar).
 *
 * The inner [ResourceResolver] object contains all pure resolution logic and can be
 * exercised in unit tests without a JCEF runtime. The JCEF-specific [Factory] and
 * [Handler] classes delegate to [ResourceResolver].
 */
class ExcalidrawSchemeHandler {

    /**
     * Describes the outcome of a resource lookup.
     *
     * @param statusCode HTTP status code (200 or 404).
     * @param contentType MIME type string (e.g. "text/html; charset=utf-8").
     * @param bytes Raw resource bytes; null when [statusCode] is 404.
     */
    data class ResolvedResource(
        val statusCode: Int,
        val contentType: String,
        val bytes: ByteArray?
    )

    /**
     * Factory that JCEF calls to create a [CefResourceHandler] for each request
     * on the `excalidraw://` scheme.
     *
     * Register with:
     *   `CefApp.getInstance().registerSchemeHandlerFactory("excalidraw", "", Factory())`
     */
    class Factory : CefSchemeHandlerFactory {
        override fun create(
            browser: CefBrowser?,
            frame: CefFrame?,
            schemeName: String?,
            request: CefRequest?
        ): CefResourceHandler = Handler()
    }

    /**
     * Handler that resolves each `excalidraw://` request to a classpath resource and
     * writes the HTTP response including the CSP header.
     *
     * JCEF calls [processRequest], then alternately [readResponse] until the response
     * is fully consumed, and finally [cancel] or lets the handler be GC'd.
     */
    class Handler : CefResourceHandlerAdapter() {

        private var resource: ResolvedResource? = null
        private var offset: Int = 0

        override fun processRequest(request: CefRequest?, callback: CefCallback?): Boolean {
            val url = request?.url ?: run {
                callback?.cancel()
                return false
            }
            // Extract the path component from excalidraw://host/path
            val path = extractPath(url)
            resource = ResourceResolver.resolve(path)
            LOG.info("excalidraw scheme request: url=$url -> path='$path' status=${resource?.statusCode} bytes=${resource?.bytes?.size ?: 0}")
            callback?.Continue()
            return true
        }

        private companion object {
            private val LOG = logger<Handler>()
        }

        override fun getResponseHeaders(
            response: CefResponse?,
            responseLength: org.cef.misc.IntRef?,
            redirectUrl: org.cef.misc.StringRef?
        ) {
            val res = resource ?: return
            response?.status = res.statusCode
            // CEF wants the BARE mime type (e.g. "text/html"). Passing
            // "text/html; charset=utf-8" makes CEF fail to recognize it as HTML and the
            // page is shown as plain text. Convey the charset via a Content-Type header.
            response?.mimeType = res.contentType.substringBefore(';').trim()
            response?.setHeaderByName("Content-Type", res.contentType, true)
            ResourceResolver.responseHeaders().forEach { (name, value) ->
                response?.setHeaderByName(name, value, true)
            }
            responseLength?.set(res.bytes?.size ?: 0)
        }

        override fun readResponse(
            dataOut: ByteArray?,
            bytesToRead: Int,
            bytesRead: org.cef.misc.IntRef?,
            callback: CefCallback?
        ): Boolean {
            val bytes = resource?.bytes ?: run {
                bytesRead?.set(0)
                return false
            }
            if (offset >= bytes.size) {
                bytesRead?.set(0)
                return false
            }
            val remaining = bytes.size - offset
            val count = minOf(remaining, bytesToRead)
            System.arraycopy(bytes, offset, dataOut, 0, count)
            offset += count
            bytesRead?.set(count)
            return true
        }

        override fun cancel() {
            resource = null
        }

        /** Strips the scheme and host from a URL, returning only the path segment. */
        private fun extractPath(url: String): String {
            // excalidraw://host/some/path -> "some/path"
            // excalidraw://app/index.html -> "index.html"
            val withoutScheme = url.removePrefix("excalidraw://")
            val slashIdx = withoutScheme.indexOf('/')
            return if (slashIdx >= 0) withoutScheme.substring(slashIdx + 1) else ""
        }
    }

    /**
     * Pure resource resolution logic — no JCEF dependency.
     *
     * Resolves a relative path to a classpath resource under `/webview/`.
     * Returns a [ResolvedResource] with status 200 and the resource bytes for known
     * paths, or status 404 and null bytes for unknown paths.
     *
     * Also provides the fixed response headers (including Content-Security-Policy)
     * that must accompany every response.
     */
    object ResourceResolver {

        /**
         * AD-6: Content-Security-Policy enforced on every response (NFR1).
         *
         * Must match (not be stricter than) the meta CSP in webview/index.html — the
         * browser enforces the intersection of header + meta CSP, so a stricter header
         * would block resources the app needs. In particular Excalidraw relies on inline
         * styles (the `#root { height:100% }` block — without `style-src 'unsafe-inline'`
         * the canvas mounts at 0×0 and looks blank), data:/blob: images, and data: fonts.
         * `connect-src 'none'` still blocks all network egress (NFR1 / privacy).
         */
        private const val CSP_VALUE =
            "default-src 'self' excalidraw:; " +
                "script-src 'self' 'unsafe-inline' 'unsafe-eval' excalidraw:; " +
                "style-src 'self' 'unsafe-inline' excalidraw:; " +
                "img-src 'self' data: blob: excalidraw:; " +
                "font-src 'self' data: excalidraw:; " +
                // Local only (the excalidraw: scheme is served from the bundle); no http(s)
                // host is ever allowed, so no network egress (NFR1) — but the app may
                // fetch its own chunks/fonts, which 'none' wrongly blocked.
                "connect-src 'self' data: blob: excalidraw:; " +
                "worker-src 'self' blob: excalidraw:"

        /**
         * Maps file extensions to MIME types for resources served under `/webview/`.
         * Only extensions expected in the Excalidraw bundle are listed; all others
         * produce a 404 rather than a content-type guess, for security.
         */
        private val MIME_TYPES: Map<String, String> = mapOf(
            "html" to "text/html; charset=utf-8",
            "css"  to "text/css; charset=utf-8",
            "js"   to "application/javascript",
            "json" to "application/json",
            "png"  to "image/png",
            "svg"  to "image/svg+xml",
            "ico"  to "image/x-icon",
            "woff" to "font/woff",
            "woff2" to "font/woff2",
            "txt"  to "text/plain; charset=utf-8"
        )

        /**
         * Resolves [path] to a classpath resource under `/webview/`.
         *
         * Path-traversal protection (Secure-Coding A10 / NFR1):
         *   1. URL-decode the input to catch percent-encoded variants (%2e%2e, %2f, %00).
         *   2. Reject immediately on null bytes, or any `..` segment (before and after
         *      decoding), or an absolute path starting with `/`.
         *   3. Normalise the decoded path with [java.nio.file.Paths] and verify that the
         *      canonical result stays strictly under the `/webview/` prefix.
         *
         * @param path Relative path such as "index.html" or "bundle.js".
         * @return [ResolvedResource] with status 200 + bytes for known resources,
         *         or status 404 for unknown/missing or traversal-blocked ones.
         */
        fun resolve(path: String): ResolvedResource {
            // Step 1: URL-decode to catch %2e%2e, %2f, %00 etc.
            val decoded = try {
                URI("excalidraw://host/$path").path  // yields /decoded/path
                    .removePrefix("/")               // strip leading /
            } catch (_: Exception) {
                return notFound()
            }

            // Step 2a: Reject null bytes (poison-byte attack).
            if (decoded.contains('\u0000')) return notFound()

            // Step 2b: Reject any path component equal to ".." (both raw and decoded).
            val rawSegments   = path.split('/', '\\')
            val decodedSegments = decoded.split('/', '\\')
            if (rawSegments.any { it == ".." } || decodedSegments.any { it == ".." }) {
                return notFound()
            }

            // Step 2c: Reject absolute paths (decoded started with / before removePrefix,
            // but can still arrive as "\path" on Windows-style inputs).
            if (decoded.startsWith("/") || decoded.startsWith("\\")) return notFound()

            // Step 3: Canonical normalisation — resolve against the virtual /webview/ root
            // and verify the result stays strictly inside it.
            val webviewBase = Paths.get("/webview")
            val candidate   = webviewBase.resolve(decoded).normalize()
            if (!candidate.startsWith(webviewBase) || candidate == webviewBase) {
                return notFound()
            }

            val sanitizedPath = candidate.subpath(1, candidate.nameCount).toString()
                .replace('\\', '/')   // normalise to forward slashes on all platforms

            val resourcePath = "/webview/$sanitizedPath"
            val stream: InputStream? = ResourceResolver::class.java.getResourceAsStream(resourcePath)
            if (stream == null) {
                return notFound()
            }
            val bytes = stream.use { readFully(it) }
            val ext = sanitizedPath.substringAfterLast('.', "").lowercase()
            val contentType = MIME_TYPES[ext] ?: "application/octet-stream"
            return ResolvedResource(statusCode = 200, contentType = contentType, bytes = bytes)
        }

        /**
         * Returns the HTTP response headers that must be applied to every response.
         * Includes the Content-Security-Policy header for NFR1 enforcement (AD-6).
         */
        fun responseHeaders(): Map<String, String> = mapOf(
            "Content-Security-Policy" to CSP_VALUE
        )

        private fun notFound(): ResolvedResource =
            ResolvedResource(statusCode = 404, contentType = "text/plain", bytes = null)

        private fun readFully(stream: InputStream): ByteArray {
            val buffer = ByteArrayOutputStream(4096)
            val chunk = ByteArray(4096)
            var n: Int
            while (stream.read(chunk).also { n = it } != -1) {
                buffer.write(chunk, 0, n)
            }
            return buffer.toByteArray()
        }
    }
}
