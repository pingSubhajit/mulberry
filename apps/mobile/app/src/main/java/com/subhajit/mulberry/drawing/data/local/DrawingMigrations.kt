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
}

