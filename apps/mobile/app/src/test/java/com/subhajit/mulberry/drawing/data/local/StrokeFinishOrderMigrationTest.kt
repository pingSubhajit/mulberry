package com.subhajit.mulberry.drawing.data.local

import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Runs the 10 → 11 migration and the stroke-order queries on a real SQLite. */
class StrokeFinishOrderMigrationTest {

    private lateinit var connection: Connection

    @Before
    fun openDatabase() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        // stroke_entities as version 10 created it.
        execute(
            "CREATE TABLE stroke_entities (" +
                "id TEXT NOT NULL PRIMARY KEY, " +
                "colorArgb INTEGER NOT NULL, " +
                "width REAL NOT NULL, " +
                "createdAt INTEGER NOT NULL)"
        )
    }

    @After
    fun closeDatabase() {
        connection.close()
    }

    @Test
    fun `migration keeps the strokes already there in the order they were drawn`() {
        insertVersion10Stroke("late", createdAt = 300)
        insertVersion10Stroke("early", createdAt = 100)
        // Two strokes started in the same millisecond keep the order they were stored in.
        insertVersion10Stroke("tie-first", createdAt = 200)
        insertVersion10Stroke("tie-second", createdAt = 200)

        migrate()

        assertEquals(listOf("early", "tie-first", "tie-second", "late"), strokesBottomToTop())
        assertEquals(listOf(1L, 2L, 3L, 4L), finishOrdersBottomToTop())
        assertEquals(5L, nextFinishOrder())
    }

    @Test
    fun `migration of an empty table starts the order at one`() {
        migrate()

        assertEquals(emptyList<String>(), strokesBottomToTop())
        assertEquals(1L, nextFinishOrder())
    }

    @Test
    fun `a stroke started earlier but finished later is drawn above`() {
        insertVersion10Stroke("old", createdAt = 100)
        migrate()

        // A partner's stroke arrives when it's started, at 150, and is finished after this
        // device's own stroke, started at 200 and finished at once.
        insertStroke("partner", createdAt = 150, finishOrder = nextFinishOrder())
        insertStroke("mine", createdAt = 200, finishOrder = nextFinishOrder())
        assertEquals(listOf("old", "partner", "mine"), strokesBottomToTop())

        // FINISH_STROKE lifts the partner's above every stroke finished before it.
        updateFinishOrder("partner", nextFinishOrder())

        assertEquals(listOf("old", "mine", "partner"), strokesBottomToTop())
    }

    @Test
    fun `migration 11 to 12 adds the photo table`() {
        migrate()
        DrawingMigrations.PHOTO_ELEMENT_STATEMENTS.forEach(::execute)
        execute(
            "INSERT INTO canvas_photo_element_entities " +
                "(id, createdAt, zIndex, centerX, centerY, rotationRad, scale, photoId, cutoutPhotoId, aspectRatio, style) " +
                "VALUES ('p1', 1, 2000, 0.5, 0.5, 0, 0.5, 'upload-1', NULL, 1.5, 'POLAROID')"
        )

        val styles = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT style, cutoutPhotoId FROM canvas_photo_element_entities").use { rows ->
                buildList { while (rows.next()) add(rows.getString(1) to rows.getString(2)) }
            }
        }
        assertEquals(listOf("POLAROID" to null), styles)
    }

    private fun migrate() {
        DrawingMigrations.STROKE_FINISH_ORDER_STATEMENTS.forEach(::execute)
    }

    private fun execute(sql: String) {
        connection.createStatement().use { statement -> statement.execute(sql) }
    }

    private fun insertVersion10Stroke(id: String, createdAt: Long) {
        connection.prepareStatement(
            "INSERT INTO stroke_entities (id, colorArgb, width, createdAt) VALUES (?, 0, 1.0, ?)"
        ).use { statement ->
            statement.setString(1, id)
            statement.setLong(2, createdAt)
            statement.executeUpdate()
        }
    }

    /** As Room's REPLACE insert stores a committed stroke. */
    private fun insertStroke(id: String, createdAt: Long, finishOrder: Long) {
        connection.prepareStatement(
            "INSERT OR REPLACE INTO stroke_entities (id, colorArgb, width, createdAt, finishOrder) " +
                "VALUES (?, 0, 1.0, ?, ?)"
        ).use { statement ->
            statement.setString(1, id)
            statement.setLong(2, createdAt)
            statement.setLong(3, finishOrder)
            statement.executeUpdate()
        }
    }

    private fun updateFinishOrder(id: String, finishOrder: Long) {
        val sql = UPDATE_FINISH_ORDER_QUERY
            .replace(":finishOrder", "?")
            .replace(":strokeId", "?")
        connection.prepareStatement(sql).use { statement ->
            statement.setLong(1, finishOrder)
            statement.setString(2, id)
            statement.executeUpdate()
        }
    }

    private fun nextFinishOrder(): Long =
        connection.createStatement().use { statement ->
            statement.executeQuery(NEXT_FINISH_ORDER_QUERY).use { rows ->
                rows.next()
                rows.getLong(1)
            }
        }

    private fun strokesBottomToTop(): List<String> = readStrokes { rows -> rows.getString("id") }

    private fun finishOrdersBottomToTop(): List<Long> = readStrokes { rows -> rows.getLong("finishOrder") }

    private fun <T> readStrokes(read: (java.sql.ResultSet) -> T): List<T> =
        connection.createStatement().use { statement ->
            statement.executeQuery(STROKES_IN_FINISH_ORDER_QUERY).use { rows ->
                buildList { while (rows.next()) add(read(rows)) }
            }
        }
}
