package com.subhajit.mulberry.drawing.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CanvasStickerElementDao {
    @Query("SELECT * FROM canvas_sticker_element_entities ORDER BY zIndex ASC")
    fun observeElements(): Flow<List<CanvasStickerElementEntity>>

    @Query("SELECT * FROM canvas_sticker_element_entities ORDER BY zIndex ASC")
    suspend fun getElements(): List<CanvasStickerElementEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(element: CanvasStickerElementEntity)

    @Query("DELETE FROM canvas_sticker_element_entities WHERE id = :elementId")
    suspend fun deleteById(elementId: String): Int

    @Query("DELETE FROM canvas_sticker_element_entities")
    suspend fun clear()
}
