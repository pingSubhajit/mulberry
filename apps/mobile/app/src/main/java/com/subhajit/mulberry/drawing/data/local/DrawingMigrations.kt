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
}
