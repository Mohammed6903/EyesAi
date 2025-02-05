package com.example.eyesai.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.eyesai.database.helpers.StoredFace

@Dao
interface FaceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFace(face: StoredFace)

    @Query("SELECT * FROM stored_faces WHERE name = :name LIMIT 1")
    suspend fun getFaceByName(name: String): StoredFace?

    @Query("SELECT * FROM stored_faces")
    suspend fun getAllFaces(): List<StoredFace>

    @Query("DELETE FROM stored_faces WHERE name = :name")
    suspend fun deleteFaceByName(name: String)

    @Query("DELETE FROM stored_faces")
    suspend fun deleteAllFaces()
}