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

package com.juwan.lynx.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.juwan.lynx.memory.Experience
import com.juwan.lynx.memory.MemoryDao
import com.juwan.lynx.state.WorldState
import com.juwan.lynx.state.WorldStateDao
import com.juwan.lynx.state.WorldStateConverters

/**
 * LynxDatabase：Lynx Agent 的主数据库
 */
@Database(
    entities = [
        Experience::class,
        WorldState::class
    ],
    version = 25,
    exportSchema = false
)
@TypeConverters(WorldStateConverters::class)
abstract class LynxDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao
    abstract fun worldStateDao(): WorldStateDao

    companion object {
        @Volatile
        private var INSTANCE: LynxDatabase? = null

        fun getInstance(context: Context): LynxDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    LynxDatabase::class.java,
                    "lynx_database"
                )
                    // Development-stage schema evolves quickly; prefer resetting local state
                    // over maintaining migration chains before the model stabilizes.
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
