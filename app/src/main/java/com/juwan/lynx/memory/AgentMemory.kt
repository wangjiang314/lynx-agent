/*
 * Copyright 2024 Lynx Agent
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.juwan.lynx.memory

import android.util.Log
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import com.juwan.lynx.agent.Strategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AgentMemory: 任务结果归档
 *
 * 仅负责将任务终态结果持久化到长期经验表。
 */
class AgentMemory(
    private val memoryDao: MemoryDao
) : TaskResultArchive {
    private val tag = "AgentMemory"

    /**
     * 持久化任务结果到长期记忆
     */
    override suspend fun persistTaskResult(strategy: Strategy, success: Boolean, summary: String) {
        withContext(Dispatchers.IO) {
            try {
                memoryDao.insertExperience(Experience(
                    app = strategy.app,
                    task = strategy.goal,
                    success = success,
                    summary = summary,
                    timestamp = System.currentTimeMillis()
                ))
            } catch (e: Exception) {
                Log.e(tag, "Failed to persist task result", e)
            }
            Unit
        }
    }
}

/**
 * Experience: 长期记忆经验记录
 */
@Entity(tableName = "experiences")
data class Experience(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val app: String,
    val task: String,
    val success: Boolean,
    val summary: String,
    val timestamp: Long
)

/**
 * MemoryDao: Room DAO for long-term memory persistence
 */
@Dao
interface MemoryDao {
    @Insert
    fun insertExperience(experience: Experience)
}
