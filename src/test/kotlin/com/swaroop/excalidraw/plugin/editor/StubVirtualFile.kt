package com.swaroop.excalidraw.plugin.editor

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileSystem

/**
 * Minimal VirtualFile stub for editor unit tests.
 *
 * Extends [VirtualFile] to provide in-memory byte content without
 * invoking IntelliJ VFS platform code (no ApplicationManager needed).
 *
 * Additionally tracks write attempts via [capturedWrites] so that tests can
 * assert that no writes occurred during error handling (AC-E1-02 / AD-03:
 * no VirtualFile mutation on parse failure).
 *
 * A03 compliance: [getOutputStream] records write attempts rather than
 * silently discarding them — testers can detect unexpected writes.
 *
 * Scope-extension note (task-02-007 review): [getOutputStream] was fixed to
 * capture the written bytes at [java.io.OutputStream.close] time (not eagerly
 * at construction time), so that [capturedWrites] reflects actual written data
 * rather than always-empty arrays.
 */
class StubVirtualFile(
    private val name: String,
    private val content: ByteArray
) : VirtualFile() {

    /**
     * Accumulates byte arrays written via [getOutputStream] and flushed at
     * [java.io.OutputStream.close] time.
     *
     * In tests that assert "no write on error", this list must remain empty.
     * Each entry is the full byte content written in one [getOutputStream] call.
     */
    val capturedWrites: MutableList<ByteArray> = mutableListOf()

    override fun getName(): String = name
    override fun getPath(): String = "/stub/$name"
    override fun isWritable(): Boolean = true
    override fun isDirectory(): Boolean = false
    override fun isValid(): Boolean = true
    override fun getParent(): VirtualFile? = null
    override fun getChildren(): Array<VirtualFile> = emptyArray()

    /**
     * Returns an [java.io.OutputStream] that captures all written bytes and appends
     * them to [capturedWrites] when [java.io.OutputStream.close] is called.
     *
     * The capture happens at [close] time — not at construction time — so that
     * [capturedWrites] reflects what was actually written.
     */
    override fun getOutputStream(
        requestor: Any?,
        newModificationStamp: Long,
        newTimeStamp: Long
    ): java.io.OutputStream {
        val buffer = java.io.ByteArrayOutputStream()
        return object : java.io.FilterOutputStream(buffer) {
            override fun close() {
                super.close()
                // Snapshot the bytes written so far and record the write.
                capturedWrites.add(buffer.toByteArray())
            }
        }
    }

    override fun contentsToByteArray(): ByteArray = content
    override fun getTimeStamp(): Long = 0L
    override fun getLength(): Long = content.size.toLong()
    override fun refresh(asynchronous: Boolean, recursive: Boolean, postRunnable: Runnable?) = Unit
    override fun getInputStream(): java.io.InputStream = content.inputStream()
    override fun getFileSystem(): VirtualFileSystem = throw UnsupportedOperationException("StubVirtualFile has no VFS")
    override fun getFileType(): FileType = throw UnsupportedOperationException("StubVirtualFile has no FileType")
}
