package com.subhajit.mulberry.drawing.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CanvasMetadataDao {
    @Query("SELECT * FROM canvas_metadata_entity WHERE canvasKey = :canvasKey")
    fun observeMetadata(canvasKey: String): Flow<CanvasMetadataEntity?>

    @Query("SELECT * FROM canvas_metadata_entity WHERE canvasKey = :canvasKey")
    suspend fun getMetadata(canvasKey: String): CanvasMetadataEntity?

    @Upsert
    suspend fun upsertMetadata(metadata: CanvasMetadataEntity)

    @Query("DELETE FROM canvas_metadata_entity WHERE canvasKey = :canvasKey")
    suspend fun deleteMetadata(canvasKey: String)

    @Query("DELETE FROM canvas_metadata_entity")
    suspend fun clearAllMetadata()
}
