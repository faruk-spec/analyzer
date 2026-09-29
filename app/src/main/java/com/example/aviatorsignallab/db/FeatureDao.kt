package com.example.aviatorsignallab.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.aviatorsignallab.model.DiscoveredPattern
import com.example.aviatorsignallab.model.RoundFeature

@Dao
interface FeatureDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFeatures(features: List<RoundFeature>)

    @Query("SELECT * FROM round_features WHERE round_id = :roundId")
    suspend fun getFeaturesForRound(roundId: String): List<RoundFeature>

    @Query("SELECT * FROM round_features ORDER BY id ASC")
    suspend fun getAllFeatures(): List<RoundFeature>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPatterns(patterns: List<DiscoveredPattern>)

    @Query("SELECT * FROM discovered_patterns ORDER BY precision DESC")
    suspend fun getAllPatterns(): List<DiscoveredPattern>

    @Query("DELETE FROM round_features")
    suspend fun clearFeatures()

    @Query("DELETE FROM discovered_patterns")
    suspend fun clearPatterns()
}
