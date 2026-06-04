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

import com.juwan.lynx.agent.Strategy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for AgentMemory.
 *
 * AgentMemory is now a thin task-result archive wrapper.
 */
class AgentMemoryTest {

    @Test
    fun `persistTaskResult should insert experience into DAO`() = runBlocking {
        val dao = RecordingMemoryDao()
        val agentMemory = AgentMemory(dao)
        val strategy = Strategy(
            goal = "搜索天气",
            app = "浏览器",
            strategy = listOf("打开浏览器", "搜索天气")
        )

        agentMemory.persistTaskResult(strategy, success = true, summary = "任务完成")

        assertEquals(1, dao.experiences.size)
        val experience = dao.experiences.single()
        assertEquals("浏览器", experience.app)
        assertEquals("搜索天气", experience.task)
        assertEquals(true, experience.success)
        assertEquals("任务完成", experience.summary)
    }

    @Test
    fun `persistTaskResult should swallow DAO exceptions`() = runBlocking {
        val dao = object : MemoryDao {
            override fun insertExperience(experience: Experience) {
                error("boom")
            }
        }
        val agentMemory = AgentMemory(dao)
        val strategy = Strategy("goal", "app", listOf("step1"))

        agentMemory.persistTaskResult(strategy, success = false, summary = "failed")
    }
}

private class RecordingMemoryDao : MemoryDao {
    val experiences = mutableListOf<Experience>()

    override fun insertExperience(experience: Experience) {
        experiences += experience
    }
}
