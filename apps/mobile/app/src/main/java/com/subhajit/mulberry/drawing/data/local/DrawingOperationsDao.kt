package com.subhajit.mulberry.drawing.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DrawingOperationsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOperation(operation: DrawingOperationEntity)

    @Query("SELECT * FROM drawing_operation_entities WHERE canvasKey = :canvasKey ORDER BY revision ASC, id ASC")
    suspend fun getOperations(canvasKey: String): List<DrawingOperationEntity>

    @Query("DELETE FROM drawing_operation_entities WHERE canvasKey = :canvasKey")
    suspend fun clearOperations(canvasKey: String)

    @Query("DELETE FROM drawing_operation_entities")
    suspend fun clearAllOperations()
}
