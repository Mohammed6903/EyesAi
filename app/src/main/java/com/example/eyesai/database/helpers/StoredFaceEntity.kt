package com.example.eyesai.database.helpers

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

@Entity(tableName = "stored_faces")
data class StoredFace(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val trackingId: Int? = null,
    @TypeConverters(FloatArrayConverter::class) val embeddings: List<Float>,
    val lastSeenTimestamp: Long = System.currentTimeMillis()
)

class FloatArrayConverter {
    @TypeConverter
    fun fromFloatList(value: List<Float>?): String {
        return Gson().toJson(value)
    }

    @TypeConverter
    fun toFloatList(value: String): List<Float> {
        val listType = object : TypeToken<List<Float>>() {}.type
        return Gson().fromJson(value, listType) ?: emptyList()
    }
}