package com.subhajit.mulberry.drawing.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object DrawingMigrations {
    val MIGRATION_7_8: Migration = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE canvas_metadata_entity " +
                    "ADD COLUMN selectedTextColorArgb INTEGER NOT NULL DEFAULT 0"
            )
            // Initialize text color from the existing stroke color so the initial UX stays consistent.
            db.execSQL(
                "UPDATE canvas_metadata_entity SET selectedTextColorArgb = selectedColorArgb"
            )
        }
    }

    val MIGRATION_8_9: Migration = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE canvas_text_element_entities " +
                    "ADD COLUMN zIndex INTEGER NOT NULL DEFAULT 0"
            )
            db.execSQL(
                "UPDATE canvas_text_element_entities SET zIndex = createdAt"
            )

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS canvas_sticker_element_entities (" +
                    "id TEXT NOT NULL PRIMARY KEY, " +
                    "createdAt INTEGER NOT NULL, " +
                    "zIndex INTEGER NOT NULL, " +
                    "centerX REAL NOT NULL, " +
                    "centerY REAL NOT NULL, " +
                    "rotationRad REAL NOT NULL, " +
                    "scale REAL NOT NULL, " +
                    "packKey TEXT NOT NULL, " +
                    "packVersion INTEGER NOT NULL, " +
                    "stickerId TEXT NOT NULL" +
                    ")"
            )
        }
    }

    val MIGRATION_9_10: Migration = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Track last non-"none" tool separately so we can default to no-tool on cold start
            // without losing the user's last chosen tool.
            db.execSQL(
                "ALTER TABLE canvas_metadata_entity " +
                    "ADD COLUMN lastNonNoneTool TEXT NOT NULL DEFAULT 'DRAW'"
            )
            // Initialize from the existing selectedTool if present; otherwise keep default.
            db.execSQL(
                "UPDATE canvas_metadata_entity " +
                    "SET lastNonNoneTool = CASE " +
                    "WHEN selectedTool IS NOT NULL AND selectedTool != 'NONE' THEN selectedTool " +
                    "ELSE lastNonNoneTool " +
                    "END"
            )
        }
    }

    /**
     * Strokes are drawn in the order they were finished, as in the server's snapshot. Strokes
     * already here keep the order they were drawn in until now: by start time, ties by insertion.
     */
    internal val STROKE_FINISH_ORDER_STATEMENTS: List<String> = listOf(
        "ALTER TABLE stroke_entities ADD COLUMN finishOrder INTEGER NOT NULL DEFAULT 0",
        "UPDATE stroke_entities SET finishOrder = (" +
            "SELECT COUNT(*) FROM stroke_entities AS earlier " +
            "WHERE earlier.createdAt < stroke_entities.createdAt " +
            "OR (earlier.createdAt = stroke_entities.createdAt AND earlier.rowid <= stroke_entities.rowid)" +
            ")"
    )

    val MIGRATION_10_11: Migration = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            STROKE_FINISH_ORDER_STATEMENTS.forEach { statement -> db.execSQL(statement) }
        }
    }

    /** Photos on the canvas, in the stickers' stack. */
    internal val PHOTO_ELEMENT_STATEMENTS: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS canvas_photo_element_entities (" +
            "id TEXT NOT NULL PRIMARY KEY, " +
            "createdAt INTEGER NOT NULL, " +
            "zIndex INTEGER NOT NULL, " +
            "centerX REAL NOT NULL, " +
            "centerY REAL NOT NULL, " +
            "rotationRad REAL NOT NULL, " +
            "scale REAL NOT NULL, " +
            "photoId TEXT NOT NULL, " +
            "cutoutPhotoId TEXT, " +
            "aspectRatio REAL NOT NULL, " +
            "style TEXT NOT NULL" +
            ")"
    )

    val MIGRATION_11_12: Migration = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            PHOTO_ELEMENT_STATEMENTS.forEach { statement -> db.execSQL(statement) }
        }
    }
}
