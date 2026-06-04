/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.juwan.lynx.memory

import com.juwan.lynx.agent.Strategy
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking

/**
 * Property-based tests for AgentMemory.
 *
 * AgentMemory is now only responsible for task-result persistence.
 */
class AgentMemoryPropertyTest : StringSpec({

    "persistTaskResult should archive every task result" {
        checkAll(10, Arb.list(Arb.string(1..50), 1..5), Arb.boolean()) { tasks, success ->
            val dao = mockk<MemoryDao>()
            every { dao.insertExperience(any()) } returns Unit
            val memory = AgentMemory(dao)

            runBlocking {
                tasks.forEach { task ->
                    val strategy = Strategy(
                        goal = task,
                        app = "test app",
                        strategy = listOf("step1", "step2")
                    )
                    memory.persistTaskResult(strategy, success, "test result")
                }
            }

            verify(exactly = tasks.size) { dao.insertExperience(any()) }
        }
    }

    "persistTaskResult should preserve task fields" {
        checkAll(10, Arb.string(1..50), Arb.string(1..50), Arb.boolean(), Arb.string(1..100)) {
            goal, app, success, result ->
            val archived = mutableListOf<Experience>()
            val dao = mockk<MemoryDao>()
            every { dao.insertExperience(any()) } answers { archived.add(firstArg()) }
            val memory = AgentMemory(dao)
            val strategy = Strategy(
                goal = goal,
                app = app,
                strategy = listOf("step1", "step2")
            )

            runBlocking {
                memory.persistTaskResult(strategy, success, result)
            }

            archived shouldHaveSize 1
            archived.single().app shouldBe app
            archived.single().task shouldBe goal
            archived.single().success shouldBe success
            archived.single().summary shouldBe result
        }
    }
})
