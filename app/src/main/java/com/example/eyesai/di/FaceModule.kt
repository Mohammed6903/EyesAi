package com.example.eyesai.di

import android.content.Context
import com.example.eyesai.database.dao.FaceDao
import com.example.eyesai.database.repository.FaceRepository
import com.example.eyesai.database.schema.FaceDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object FaceModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): FaceDatabase {
        return FaceDatabase.getDatabase(context)
    }

    @Provides
    fun provideFaceDao(database: FaceDatabase): FaceDao {
        return database.faceDao()
    }

    @Provides
    @Singleton
    fun provideFaceRepository(faceDao: FaceDao): FaceRepository {
        return FaceRepository(faceDao)
    }
}