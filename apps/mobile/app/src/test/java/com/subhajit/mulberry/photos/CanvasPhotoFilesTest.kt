package com.subhajit.mulberry.photos

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CanvasPhotoFilesTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `removing an account deletes its photos and keeps other accounts'`() {
        val root = temp.newFolder("CanvasPhotos")
        val files = CanvasPhotoFiles(root)
        val mine = files.write("me", PHOTO_A)
        val mineToo = files.write("me", PHOTO_B)
        val theirs = files.write("someone-else", PHOTO_A)

        files.removeAccount("me")

        assertFalse(mine.exists())
        assertFalse(mineToo.exists())
        assertFalse(File(root, "me").exists())
        assertTrue(theirs.exists())
        assertTrue(root.exists())
    }

    @Test
    fun `removing an account with no photos does nothing`() {
        val root = temp.newFolder("CanvasPhotos")
        val files = CanvasPhotoFiles(root)
        val theirs = files.write("someone-else", PHOTO_A)

        files.removeAccount("me")

        assertTrue(theirs.exists())
    }

    @Test
    fun `an account id that isn't a plain folder name never removes the whole cache`() {
        val root = temp.newFolder("CanvasPhotos")
        val files = CanvasPhotoFiles(root)
        val theirs = files.write("someone-else", PHOTO_A)

        listOf("", " ", ".", "..", "../CanvasPhotos", "a/b", "a\\b").forEach(files::removeAccount)

        assertTrue(theirs.exists())
        assertTrue(root.exists())
    }

    @Test
    fun `photos are kept per account under their id`() {
        val root = temp.newFolder("CanvasPhotos")
        val files = CanvasPhotoFiles(root)

        assertEquals(File(File(root, "me"), PHOTO_A.lowercase()), files.file("me", PHOTO_A.uppercase()))
        assertNull(files.file(null, PHOTO_A))
        assertNull(files.file("..", PHOTO_A))
        assertNull(files.file("me", "../../secrets"))
    }

    private fun CanvasPhotoFiles.write(userId: String, photoId: String): File {
        val file = requireNotNull(file(userId, photoId))
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3))
        return file
    }

    private companion object {
        const val PHOTO_A = "6f1c2a52-34a8-4c1e-9d0b-0c7c1b2d3e4f"
        const val PHOTO_B = "0a4b7e33-2c0d-4f6a-8a17-5b9c3d2e1f00"
    }
}
