package com.subhajit.mulberry.drawing.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CanvasMetadataDao {
    @Query("SELECT * FROM canvas_metadata_entity WHERE id = :metadataId")
    fun observeMetadata(metadataId: Int = CanvasMetadataEntity.METADATA_ID): Flow<CanvasMetadataEntity?>

    @Query("SELECT * FROM canvas_metadata_entity WHERE id = :metadataId")
    suspend fun getMetadata(metadataId: Int = CanvasMetadataEntity.METADATA_ID): CanvasMetadataEntity?

    @Upsert
    suspend fun upsertMetadata(metadata: CanvasMetadataEntity)
}
