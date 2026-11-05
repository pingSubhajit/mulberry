package com.subhajit.mulberry.sync

import java.io.File
import java.util.UUID

/**
 * Tells the canvas operations this install made apart from everyone else's, the same account on
 * another device included.
 *
 * Every operation id this install makes starts with the current lineage token. A server operation
 * is this device's own, and so already on its canvas, only if it carries that token. The token
 * changes each time the canvas is replaced from the server: what this device drew before then is
 * no longer on the canvas the way it drew it, so those operations are applied like anyone else's.
 */
interface LocalCanvasOperationIds {
    /**
     * True until the canvas is first replaced from the server under this scheme, or by a build that
     * draws more kinds of content. Earlier builds skipped every operation from the same account,
     * and skip content they can't draw, such as photos, so the canvas may be missing some of it.
     */
    val needsCanvasReload: Boolean

    fun newClientOperationId(): String

    fun isLocal(clientOperationId: String): Boolean

    /** Call as the canvas is replaced, before anything is drawn on the new one. */
    fun startNewLineage()
}

/**
 * Keeps the lineage in [file], which should live in `noBackupFilesDir`: a token restored onto
 * another device would make each device skip the other's operations.
 */
class FileLocalCanvasOperationIds(private val file: File) : LocalCanvasOperationIds {
    private data class Lineage(val token: String, val needsCanvasReload: Boolean)
    // The file holds the token, then "reload" while the first reload is owed, then the content
    // version the canvas was last loaded with.

    private val lock = Any()

    @Volatile
    private var loaded: Lineage? = null

    override val needsCanvasReload: Boolean
        get() = current().needsCanvasReload

    override fun newClientOperationId(): String =
        "${current().token}$SEPARATOR${UUID.randomUUID()}"

    override fun isLocal(clientOperationId: String): Boolean =
        clientOperationId.startsWith("${current().token}$SEPARATOR")

    override fun startNewLineage() {
        synchronized(lock) {
            val next = Lineage(token = newToken(), needsCanvasReload = false)
            // Replaced from the server by this build, so it holds everything this build can draw.
            write(next)
            loaded = next
        }
    }

    private fun current(): Lineage =
        loaded ?: synchronized(lock) {
            loaded ?: load().also { loaded = it }
        }

    private fun load(): Lineage {
        val lines = runCatching { file.readLines() }.getOrNull().orEmpty()
        val token = lines.firstOrNull()?.trim()?.takeIf { it.matches(TOKEN_PATTERN) }
        if (token != null) {
            val owed = lines.any { it.trim() == RELOAD_MARKER }
            val contentVersion = lines.firstNotNullOfOrNull { line ->
                line.trim().removePrefix(CONTENT_PREFIX).takeIf { line.trim().startsWith(CONTENT_PREFIX) }?.toIntOrNull()
            } ?: 0
            return Lineage(token = token, needsCanvasReload = owed || contentVersion < CONTENT_VERSION)
        }
        // A new install, or the first launch of a build with lineages.
        return Lineage(token = newToken(), needsCanvasReload = true).also(::write)
    }

    /** Writes a temporary file and renames it, so a crash never leaves half a token behind. */
    private fun write(lineage: Lineage) {
        runCatching {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.tmp")
            val content = buildString {
                append(lineage.token)
                if (lineage.needsCanvasReload) {
                    append('\n').append(RELOAD_MARKER)
                } else {
                    append('\n').append(CONTENT_PREFIX).append(CONTENT_VERSION)
                }
            }
            temporary.writeText(content)
            if (!temporary.renameTo(file)) {
                file.writeText(content)
                temporary.delete()
            }
        }
    }

    private fun newToken(): String = UUID.randomUUID().toString().replace("-", "").take(TOKEN_LENGTH)

    companion object {
        const val FILE_NAME = "canvas_operation_lineage"
        private const val SEPARATOR = "."
        private const val RELOAD_MARKER = "reload"
        private const val CONTENT_PREFIX = "content:"

        /**
         * The kinds of canvas content this build draws. Raise it when a build learns a new kind, so
         * each device reloads its canvas once to bring back what it skipped. 1: photos.
         */
        const val CONTENT_VERSION = 1
        private const val TOKEN_LENGTH = 12
        private val TOKEN_PATTERN = Regex("[0-9a-f]{$TOKEN_LENGTH}")
    }
}
