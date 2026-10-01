package com.example.aviatorsignallab.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.aviatorsignallab.model.AlertFireLog
import com.example.aviatorsignallab.model.DiscoveredPattern
import com.example.aviatorsignallab.model.GameRound
import com.example.aviatorsignallab.model.LiveEvent
import com.example.aviatorsignallab.model.RoundFeature

@Database(
    entities = [
        GameRound::class,
        LiveEvent::class,
        RoundFeature::class,
        DiscoveredPattern::class,
        AlertFireLog::class
    ],
    version = 2,
    exportSchema = false
)
abstract class ResearchDatabase : RoomDatabase() {

    abstract fun roundDao(): RoundDao
    abstract fun liveEventDao(): LiveEventDao
    abstract fun featureDao(): FeatureDao
    abstract fun alertFireLogDao(): AlertFireLogDao

    companion object {
        @Volatile
        private var INSTANCE: ResearchDatabase? = null

        fun getDatabase(context: Context): ResearchDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ResearchDatabase::class.java,
                    "aviator_research.db"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
