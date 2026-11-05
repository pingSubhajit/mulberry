package com.subhajit.mulberry.sync

import java.io.File
import java.nio.file.Files

/** A lineage file in a fresh temporary directory, as `noBackupFilesDir` is on a new install. */
internal fun newLineageFile(): File {
    val directory = Files.createTempDirectory("canvas-lineage").toFile()
    directory.deleteOnExit()
    return File(directory, FileLocalCanvasOperationIds.FILE_NAME)
}

/** A lineage past its first canvas reload, so it doesn't ask for one. */
internal fun establishedOperationIds(file: File = newLineageFile()): FileLocalCanvasOperationIds =
    FileLocalCanvasOperationIds(file).apply { startNewLineage() }
