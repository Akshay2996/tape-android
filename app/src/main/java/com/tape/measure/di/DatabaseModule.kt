package com.tape.measure.di

import android.content.Context
import androidx.room.Room
import com.tape.measure.data.db.MeasurementDao
import com.tape.measure.data.db.TapeDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * Note: [fallbackToDestructiveMigration] is deliberately NOT used. Saved
     * measurements can't be regenerated, so schema changes ship real migrations.
     */
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TapeDatabase =
        Room.databaseBuilder(context, TapeDatabase::class.java, "tape_db")
            .addMigrations(TapeDatabase.MIGRATION_1_2)
            .build()

    @Provides
    fun provideMeasurementDao(db: TapeDatabase): MeasurementDao = db.measurementDao()
}
