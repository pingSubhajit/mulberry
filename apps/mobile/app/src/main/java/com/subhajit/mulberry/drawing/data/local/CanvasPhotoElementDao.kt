package com.subhajit.mulberry.drawing.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CanvasPhotoElementDao {
    @Query("SELECT * FROM canvas_photo_element_entities ORDER BY zIndex ASC")
    fun observeElements(): Flow<List<CanvasPhotoElementEntity>>

    @Query("SELECT * FROM canvas_photo_element_entities ORDER BY zIndex ASC")
    suspend fun getElements(): List<CanvasPhotoElementEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(element: CanvasPhotoElementEntity)

    @Query("DELETE FROM canvas_photo_element_entities WHERE id = :elementId")
    suspend fun deleteById(elementId: String): Int

    @Query("DELETE FROM canvas_photo_element_entities")
    suspend fun clear()
}
