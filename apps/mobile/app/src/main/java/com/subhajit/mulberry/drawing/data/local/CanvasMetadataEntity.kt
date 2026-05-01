package com.subhajit.mulberry.drawing.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.subhajit.mulberry.drawing.geometry.NORMALIZED_COORDINATE_SPACE_VERSION
import com.subhajit.mulberry.drawing.model.DrawingDefaults
import com.subhajit.mulberry.drawing.model.DrawingTool

@Entity(tableName = "canvas_metadata_entity")
data class CanvasMetadataEntity(
    @PrimaryKey val id: Int = METADATA_ID,
    val revision: Long = 0L,
    val lastModifiedAt: Long = 0L,
    val canvasWidthPx: Int = 0,
    val canvasHeightPx: Int = 0,
    val selectedColorArgb: Long = DrawingDefaults.DEFAULT_COLOR_ARGB,
    val selectedTextColorArgb: Long = DrawingDefaults.DEFAULT_COLOR_ARGB,
    val selectedWidth: Float = DrawingDefaults.DEFAULT_WIDTH,
    val selectedTool: DrawingTool = DrawingTool.DRAW,
    val coordinateSpaceVersion: Int = NORMALIZED_COORDINATE_SPACE_VERSION,
    val isSnapshotDirty: Boolean = true,
    val lastSnapshotRevision: Long = 0L,
    val cachedImagePath: String? = null
) {
    companion object {
        const val METADATA_ID = 1

        fun default() = CanvasMetadataEntity()
    }
}
