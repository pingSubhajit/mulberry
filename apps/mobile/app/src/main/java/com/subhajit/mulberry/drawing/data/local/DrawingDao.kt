package com.subhajit.mulberry.drawing.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Strokes bottom to top: in the order they were finished. */
internal const val STROKES_IN_FINISH_ORDER_QUERY =
    "SELECT * FROM stroke_entities ORDER BY finishOrder ASC, createdAt ASC"

/** The next stroke finished goes above every stroke there now. */
internal const val NEXT_FINISH_ORDER_QUERY =
    "SELECT COALESCE(MAX(finishOrder), 0) + 1 FROM stroke_entities"

internal const val UPDATE_FINISH_ORDER_QUERY =
    "UPDATE stroke_entities SET finishOrder = :finishOrder WHERE id = :strokeId"

@Dao
interface DrawingDao {
    @Transaction
    @Query(STROKES_IN_FINISH_ORDER_QUERY)
    fun observeStrokeGraphs(): Flow<List<StrokeWithPoints>>

    @Transaction
    @Query(STROKES_IN_FINISH_ORDER_QUERY)
    suspend fun getStrokeGraphs(): List<StrokeWithPoints>

    @Query(NEXT_FINISH_ORDER_QUERY)
    suspend fun nextFinishOrder(): Long

    @Query(UPDATE_FINISH_ORDER_QUERY)
    suspend fun updateFinishOrder(strokeId: String, finishOrder: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStroke(stroke: StrokeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStrokePoints(points: List<StrokePointEntity>)

    @Query("SELECT COALESCE(MAX(pointIndex), -1) FROM stroke_point_entities WHERE strokeId = :strokeId")
    suspend fun maxPointIndex(strokeId: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM stroke_entities WHERE id = :strokeId)")
    suspend fun strokeExists(strokeId: String): Boolean

    @Query("DELETE FROM stroke_point_entities WHERE strokeId = :strokeId")
    suspend fun deleteStrokePoints(strokeId: String)

    @Query("DELETE FROM stroke_entities WHERE id = :strokeId")
    suspend fun deleteStrokeById(strokeId: String): Int

    @Query("DELETE FROM stroke_entities")
    suspend fun clearStrokes()
}
