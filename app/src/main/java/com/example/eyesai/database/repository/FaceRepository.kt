package com.example.eyesai.database.repository

import android.content.Context
import com.example.eyesai.database.dao.FaceDao
import com.example.eyesai.database.helpers.StoredFace
import com.example.eyesai.database.schema.FaceDatabase

class FaceRepository(private val faceDao: FaceDao) {

    suspend fun insertFace(face: StoredFace) {
        faceDao.insertFace(face)
    }

    suspend fun getFaceByName(name: String): StoredFace? {
        return faceDao.getFaceByName(name)
    }

    suspend fun getAllFaces(): List<StoredFace> {
        return faceDao.getAllFaces()
    }

    suspend fun deleteFaceByName(name: String) {
        faceDao.deleteFaceByName(name)
    }

    suspend fun deleteAllFaces() {
        faceDao.deleteAllFaces()
    }
}