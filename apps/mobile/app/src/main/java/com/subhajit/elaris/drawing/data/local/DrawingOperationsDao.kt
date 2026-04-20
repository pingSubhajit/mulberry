package com.subhajit.elaris.drawing.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DrawingOperationsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOperation(operation: DrawingOperationEntity)

    @Query("SELECT * FROM drawing_operation_entities ORDER BY revision ASC, id ASC")
    suspend fun getOperations(): List<DrawingOperationEntity>

    @Query("DELETE FROM drawing_operation_entities")
    suspend fun clearOperations()
}
