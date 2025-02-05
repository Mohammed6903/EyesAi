package com.example.eyesai.database.schema

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.eyesai.database.dao.FaceDao
import com.example.eyesai.database.helpers.FloatArrayConverter
import com.example.eyesai.database.helpers.StoredFace

@Database(entities = [StoredFace::class], version = 1, exportSchema = false)
@TypeConverters(FloatArrayConverter::class)
abstract class FaceDatabase : RoomDatabase() {
    abstract fun faceDao(): FaceDao

    companion object {
        @Volatile
        private var INSTANCE: FaceDatabase? = null

        fun getDatabase(context: Context): FaceDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    FaceDatabase::class.java,
                    "face_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}