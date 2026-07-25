package com.example.aquiethours.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.time.DayOfWeek

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedules ORDER BY dayOfWeek, startHour, startMinute")
    fun getAllSchedules(): Flow<List<Schedule>>
    
    @Query("SELECT * FROM schedules WHERE dayOfWeek = :day")
    fun getSchedulesForDay(day: DayOfWeek): Flow<List<Schedule>>

    @Query("SELECT * FROM schedules WHERE isActive = 1")
    suspend fun getActiveSchedules(): List<Schedule>

    @Query("SELECT * FROM schedules WHERE id = :id")
    suspend fun getScheduleById(id: Int): Schedule?

    @Query("UPDATE schedules SET skipNext = :skip WHERE id = :id")
    suspend fun updateSkipNext(id: Int, skip: Boolean)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(schedule: Schedule): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(schedules: List<Schedule>): List<Long>

    @Update
    suspend fun update(schedule: Schedule)

    @Delete
    suspend fun delete(schedule: Schedule)
}
