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
    @Query("SELECT * FROM stroke_entities ORDER BY createdAt ASC")
    fun observeStrokeGraphs(): Flow<List<StrokeWithPoints>>

    @Transaction
    @Query("SELECT * FROM stroke_entities ORDER BY createdAt ASC")
    suspend fun getStrokeGraphs(): List<StrokeWithPoints>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStroke(stroke: StrokeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStrokePoints(points: List<StrokePointEntity>)

    @Query("SELECT COALESCE(MAX(pointIndex), -1) FROM stroke_point_entities WHERE strokeId = :strokeId")
    suspend fun maxPointIndex(strokeId: String): Int

    @Query("DELETE FROM stroke_point_entities WHERE strokeId = :strokeId")
    suspend fun deleteStrokePoints(strokeId: String)

    @Query("DELETE FROM stroke_entities WHERE id = :strokeId")
    suspend fun deleteStrokeById(strokeId: String): Int

    @Query("DELETE FROM stroke_entities")
    suspend fun clearStrokes()
}
