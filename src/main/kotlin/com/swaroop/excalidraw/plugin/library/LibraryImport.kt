package com.swaroop.excalidraw.plugin.library

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.io.HttpRequests
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Owns the whole "Browse libraries" round-trip: detect the request, present the in-IDE
 * chooser, fetch the chosen `.excalidrawlib`, normalize it, and hand the result back to
 * the caller for injection.
 *
 * [com.swaroop.excalidraw.plugin.jcef.ExcalidrawJcefHost] only needs [isLibraryBrowseRequest]
 * to decide whether a pop-up is the "Browse libraries" link (vs. an external hyperlink), and
 * [com.swaroop.excalidraw.plugin.editor.ExcalidrawFileEditor] only needs [start] — both are
 * thin adapters at this module's edges; the URL-sniffing heuristics and fetch/normalize logic
 * live here, in one place.
 */
object LibraryImport {

    private val LOG = logger<LibraryImport>()

    /** Query/fragment key the library site appends the chosen `.excalidrawlib` URL under. */
    private const val ADD_LIBRARY_PARAM = "addLibrary="

    /**
     * True when [url] is Excalidraw's "Browse libraries" destination — used by
     * [com.swaroop.excalidraw.plugin.jcef.ExcalidrawJcefHost] to route that pop-up to
     * [start] instead of the external system browser.
     */
    fun isLibraryBrowseRequest(url: String): Boolean = url.contains("libraries.excalidraw.com")

    /**
     * Extracts the `.excalidrawlib` URL from a navigation that carries
     * `addLibrary=<encoded-url>` in either the query or the fragment. Returns the
     * decoded http(s) URL, or null if absent / not http(s). Pure + unit-testable.
     */
    fun extractAddLibraryUrl(url: String): String? {
        val idx = url.indexOf(ADD_LIBRARY_PARAM)
        if (idx < 0) return null
        val rest = url.substring(idx + ADD_LIBRARY_PARAM.length)
        val raw = rest.substringBefore('&')
        if (raw.isEmpty()) return null
        val decoded = try {
            URLDecoder.decode(raw, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            return null
        }
        return if (decoded.startsWith("http://") || decoded.startsWith("https://")) decoded else null
    }

    /**
     * Normalises the contents of a `.excalidrawlib` file into a JSON array of
     * Excalidraw library items (the shape excalidrawAPI.updateLibrary expects),
     * or null if it can't be parsed.
     *
     * Handles both formats:
     *  - v2: `{ "type":"excalidrawlib", "libraryItems":[ {id,status,elements,created}, … ] }`
     *  - v1: `{ "type":"excalidrawlib", "library":[ [elements], … ] }` (each entry wrapped).
     *
     * Pure + unit-testable; no IDE/JCEF dependency.
     */
    fun parseLibraryItems(fileText: String): String? {
        val root = try {
            JsonParser.parseString(fileText)?.takeIf { it.isJsonObject }?.asJsonObject
        } catch (_: Exception) {
            null
        } ?: return null

        if (root.has("libraryItems") && root.get("libraryItems").isJsonArray) {
            val arr = root.getAsJsonArray("libraryItems")
            if (arr.size() > 0) return arr.toString()
        }
        if (root.has("library") && root.get("library").isJsonArray) {
            val lib = root.getAsJsonArray("library")
            val items = JsonArray()
            for ((i, entry) in lib.withIndex()) {
                if (!entry.isJsonArray) continue
                val item = JsonObject()
                item.addProperty("id", "imported-$i")
                item.addProperty("status", "unpublished")
                item.addProperty("created", 1L)
                item.add("elements", entry)
                items.add(item)
            }
            if (items.size() > 0) return items.toString()
        }
        return null
    }

    /**
     * Presents the in-IDE library chooser for [libraryUrl] (the `libraries.excalidraw.com`
     * page Excalidraw's "Browse libraries" link pointed at), then fetches and normalizes the
     * library the user picks. [onItemsReady] is invoked on the EDT with the normalized
     * libraryItems JSON — only on success; a fetch/parse failure is logged and silently
     * dropped (mirrors the pre-existing behavior).
     */
    fun start(project: Project, libraryUrl: String, onItemsReady: (String) -> Unit) {
        LibraryBrowserDialog(project, libraryUrl) { addLibraryUrl ->
            ApplicationManager.getApplication().executeOnPooledThread {
                val itemsJson = fetchLibraryItems(addLibraryUrl)
                if (itemsJson != null) {
                    ApplicationManager.getApplication().invokeLater { onItemsReady(itemsJson) }
                }
            }
        }.show()
    }

    private fun fetchLibraryItems(libraryFileUrl: String): String? = try {
        val fileText = HttpRequests.request(libraryFileUrl)
            .accept("application/json, application/octet-stream, */*")
            .readString()
        parseLibraryItems(fileText)
    } catch (e: Exception) {
        LOG.warn("Excalidraw: failed to load library from '$libraryFileUrl'", e)
        null
    }
}
