package com.subhajit.mulberry.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCanvasOperationIdsTest {
    @Test
    fun recognisesOnlyIdsFromThisLineage() {
        val operationIds = establishedOperationIds()
        val own = operationIds.newClientOperationId()

        assertTrue(operationIds.isLocal(own))
        assertNotEquals(own, operationIds.newClientOperationId())
        // iOS, the Mac and other Android installs of the same account.
        assertFalse(operationIds.isLocal("0B7F6A52-3C1E-4D2A-9E55-6A1B2C3D4E5F"))
        assertFalse(operationIds.isLocal(establishedOperationIds().newClientOperationId()))
    }

    @Test
    fun keepsTheLineageAcrossRestarts() {
        val file = newLineageFile()
        val own = establishedOperationIds(file).newClientOperationId()

        val restarted = FileLocalCanvasOperationIds(file)

        assertTrue(restarted.isLocal(own))
        assertFalse(restarted.needsCanvasReload)
    }

    @Test
    fun aNewLineageNoLongerClaimsEarlierOperations() {
        val file = newLineageFile()
        val operationIds = establishedOperationIds(file)
        val before = operationIds.newClientOperationId()

        operationIds.startNewLineage()
        val after = operationIds.newClientOperationId()

        assertFalse(operationIds.isLocal(before))
        assertTrue(operationIds.isLocal(after))
        val restarted = FileLocalCanvasOperationIds(file)
        assertFalse(restarted.isLocal(before))
        assertTrue(restarted.isLocal(after))
    }

    @Test
    fun asksForOneReloadUntilTheFirstNewLineage() {
        val file = newLineageFile()
        val firstLaunch = FileLocalCanvasOperationIds(file)
        val own = firstLaunch.newClientOperationId()

        assertTrue(firstLaunch.needsCanvasReload)
        // Killed before the reload: the next launch still asks for it, and still knows its ids.
        val secondLaunch = FileLocalCanvasOperationIds(file)
        assertTrue(secondLaunch.needsCanvasReload)
        assertTrue(secondLaunch.isLocal(own))

        secondLaunch.startNewLineage()

        assertFalse(secondLaunch.needsCanvasReload)
        assertFalse(FileLocalCanvasOperationIds(file).needsCanvasReload)
    }

    @Test
    fun reloadsOnceForContentAnEarlierBuildSkipped() {
        // Written by a build that skipped photos: a token, with no reload owed and no content version.
        val file = newLineageFile().apply { writeText("0123456789ab") }
        val upgraded = FileLocalCanvasOperationIds(file)

        assertTrue(upgraded.needsCanvasReload)
        assertTrue(upgraded.isLocal("0123456789ab.op-1"))

        upgraded.startNewLineage()

        assertFalse(FileLocalCanvasOperationIds(file).needsCanvasReload)
    }
}
