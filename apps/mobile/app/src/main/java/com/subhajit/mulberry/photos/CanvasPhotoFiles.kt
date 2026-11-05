package com.subhajit.mulberry.photos

import java.io.File
import java.util.UUID

/** Canvas photo files on disk, one folder per account under [root]. */
internal class CanvasPhotoFiles(private val root: File) {

    /**
     * Where [photoId] is kept for [userId]. Only real ids name files, so nothing from the server
     * can reach outside the account's folder.
     */
    fun file(userId: String?, photoId: String): File? {
        if (runCatching { UUID.fromString(photoId) }.isFailure) return null
        val folder = accountFolder(userId) ?: return null
        return File(folder, photoId.lowercase())
    }

    /** Deletes every photo kept for [userId]. Other accounts' photos stay. */
    fun removeAccount(userId: String) {
        accountFolder(userId)?.deleteRecursively()
    }

    private fun accountFolder(userId: String?): File? = userId
        ?.takeIf { it.isNotBlank() && it != "." && it != ".." && !it.contains('/') && !it.contains('\\') }
        ?.let { File(root, it) }
}
