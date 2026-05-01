package com.subhajit.mulberry.drawing.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface DrawingDao {
    @Transaction
    @Query("SELECT * FROM stroke_entities WHERE canvasKey = :canvasKey ORDER BY createdAt ASC")
    fun observeStrokeGraphs(canvasKey: String): Flow<List<StrokeWithPoints>>

    @Transaction
    @Query("SELECT * FROM stroke_entities WHERE canvasKey = :canvasKey ORDER BY createdAt ASC")
    suspend fun getStrokeGraphs(canvasKey: String): List<StrokeWithPoints>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStroke(stroke: StrokeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStrokePoints(points: List<StrokePointEntity>)

    @Query("SELECT COALESCE(MAX(pointIndex), -1) FROM stroke_point_entities WHERE strokeKey = :strokeKey")
    suspend fun maxPointIndex(strokeKey: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM stroke_entities WHERE key = :strokeKey)")
    suspend fun strokeExists(strokeKey: String): Boolean

    @Query("DELETE FROM stroke_point_entities WHERE strokeKey = :strokeKey")
    suspend fun deleteStrokePoints(strokeKey: String)

    @Query("DELETE FROM stroke_entities WHERE key = :strokeKey")
    suspend fun deleteStrokeByKey(strokeKey: String): Int

    @Query("DELETE FROM stroke_entities WHERE canvasKey = :canvasKey")
    suspend fun clearStrokes(canvasKey: String)

    @Query("DELETE FROM stroke_entities")
    suspend fun clearAllStrokes()
}
