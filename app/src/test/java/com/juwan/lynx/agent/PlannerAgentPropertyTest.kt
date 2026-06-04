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

package com.juwan.lynx.agent

import com.juwan.lynx.runtime.AgentResult
import com.juwan.lynx.runtime.AgentRuntime
import com.juwan.lynx.runtime.ModelConfig
import com.juwan.lynx.state.WorldState
import com.juwan.lynx.state.WorldStateManager
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking

class PlannerAgentPropertyTest : FunSpec({

    test("createStrategy should abstract tool and coordinate level details") {
        val runtime = mockRuntime(
            """
                {
                  "goal":"完成下单",
                  "app":"com.test.app",
                  "strategy":[
                    "click(<point>100 200</point>) 然后 type(text=瑞幸) 再 press_back",
                    "open_app(com.test.app)",
                    "x=120,y=300 点击确认"
                  ]
                }
            """.trimIndent()
        )
        val agent = testPlanner(runtime)

        val strategy = runBlocking { agent.createStrategy("帮我在测试应用里完成下单") }

        strategy.goal shouldBe "完成下单"
        strategy.app shouldBe "com.test.app"
        strategy.strategy shouldContainExactly listOf(
            "围绕“完成下单”推进到下一可验证状态"
        )
        strategy.strategy.forEach { step ->
            step shouldNotBe ""
            step.contains("click", ignoreCase = true) shouldBe false
            step.contains("type", ignoreCase = true) shouldBe false
            step.contains("open_app", ignoreCase = true) shouldBe false
            step.contains("<point>", ignoreCase = true) shouldBe false
            Regex("""\bx\s*=|\by\s*=|-?\d+\s*,\s*-?\d+""").containsMatchIn(step) shouldBe false
        }
    }

    test("createStrategy should fallback to default milestones when planner returns empty strategy") {
        val runtime = mockRuntime("""{"goal":"执行任务","app":"com.test.app","strategy":[]}""")
        val agent = testPlanner(runtime)

        val strategy = runBlocking { agent.createStrategy("执行任务") }

        strategy.goal shouldBe "执行任务"
        strategy.app shouldBe "com.test.app"
        strategy.strategy shouldContainExactly listOf(
            "进入目标应用并到达任务入口",
            "围绕“执行任务”推进核心流程",
            "确认目标已达成并完成任务"
        )
    }

    test("createStrategy should parse TaskSpec success criteria and risk hints") {
        val runtime = mockRuntime(
            """
                {
                  "goal":"给张三发送消息",
                  "target_app":"com.tencent.mm",
                  "strategy":["进入会话","完成发送"],
                  "success_criteria":["会话中可见已发送消息"],
                  "risk_hints":["如果出现验证码请请求用户协助"]
                }
            """.trimIndent()
        )
        val agent = testPlanner(runtime)

        val strategy = runBlocking { agent.createStrategy("打开微信给张三发消息") }

        strategy.goal shouldBe "给张三发送消息"
        strategy.app shouldBe "com.tencent.mm"
        strategy.successCriteria shouldContainExactly listOf("会话中可见已发送消息")
        strategy.riskHints shouldContainExactly listOf("如果出现验证码请请求用户协助")
    }

    test("createStrategy should not rewrite business app aliases in runtime") {
        val runtime = mockRuntime(
            """
                {
                  "goal":"给抖音极速版好友发送消息",
                  "app":"com.ss.iphone.ugc.AwemeLite",
                  "strategy":["进入抖音极速版","定位好友","发送消息"]
                }
            """.trimIndent()
        )
        val agent = testPlanner(runtime)

        val strategy = runBlocking { agent.createStrategy("给抖音极速版好友发一条你好的消息") }

        strategy.app shouldBe "com.ss.iphone.ugc.awemelite"
        strategy.originalInstruction shouldBe "给抖音极速版好友发一条你好的消息"
    }

    test("createStrategy fallback may extract generic launch target without business mapping") {
        val agent = testPlanner(mockRuntimeError("Empty response from LLM"))

        val strategy = runBlocking { agent.createStrategy("打开设置并进入 WLAN 页面") }

        strategy.app shouldBe "设置"
        strategy.originalInstruction shouldBe "打开设置并进入 WLAN 页面"
        strategy.successCriteria.isNotEmpty() shouldBe true
    }

    test("createStrategy should preserve literal multiline constraints as success criteria") {
        val runtime = mockRuntime(
            """
                {
                  "goal":"新建一条包含指定内容的草稿",
                  "target_app":"备忘录",
                  "strategy":["进入备忘录编辑页面","填写草稿内容","确认停留在编辑页面"],
                  "success_criteria":["当前屏幕显示草稿内容"]
                }
            """.trimIndent()
        )
        val agent = testPlanner(runtime)

        val strategy = runBlocking {
            agent.createStrategy("打开备忘录，新建一条两行草稿，第一行是“Lynx agent test”，第二行是“cross app challenge”，停留在编辑页面")
        }

        val criteriaText = strategy.successCriteria.joinToString("\n")
        criteriaText shouldContain "Lynx agent test"
        criteriaText shouldContain "cross app challenge"
        criteriaText shouldContain "分行结构"
        criteriaText shouldContain "顺序"
        criteriaText shouldContain "停留"
    }

    test("reconcileReplannedStrategy should preserve original instruction and fallback missing goal app") {
        val agent = testPlanner(mockRuntime("""{"decision":"abort","reason":"unused"}"""))
        val current = Strategy(
            goal = "给好友发送消息",
            app = "com.ss.android.ugc.aweme.lite",
            strategy = listOf("定位好友", "发送消息"),
            originalInstruction = "给晓哥发一条你好的消息"
        )
        val replanned = Strategy(
            goal = "   ",
            app = "",
            strategy = listOf("重新进入目标应用", "推进到下一可验证状态")
        )

        val reconciled = agent.reconcileReplannedStrategy(current, replanned)

        reconciled.goal shouldBe "给好友发送消息"
        reconciled.app shouldBe "com.ss.android.ugc.aweme.lite"
        reconciled.originalInstruction shouldBe "给晓哥发一条你好的消息"
        reconciled.strategy.size shouldBe 2
        reconciled.strategy.forEach { step ->
            step shouldNotBe ""
            step.contains("<point>", ignoreCase = true) shouldBe false
            step.contains("click", ignoreCase = true) shouldBe false
        }
    }

    test("reflect should decode replan strategy") {
        val runtime = mockRuntime(
            """
                {"decision":"replan","strategy":{"goal":"重新执行任务","app":"com.test.app","strategy":["重新进入应用","重新尝试关键提交"]}}
            """.trimIndent()
        )
        val agent = testPlanner(runtime)

        val decision = runBlocking { agent.reflect(testWorldState(), "页面无进展") }

        val replan = decision.shouldBeInstanceOf<PlannerDecision.Replan>()
        replan.newStrategy.goal shouldBe "重新执行任务"
        replan.newStrategy.app shouldBe "com.test.app"
        replan.newStrategy.strategy.size shouldBe 2
        replan.newStrategy.strategy.forEach { step ->
            step shouldNotBe ""
            step.contains("<point>", ignoreCase = true) shouldBe false
            step.contains("click", ignoreCase = true) shouldBe false
            step.contains("type", ignoreCase = true) shouldBe false
        }
    }

    test("reflect should coerce retry output to abort") {
        val runtime = mockRuntime("""{"decision":"retry","hint":"请重试"}""")
        val agent = testPlanner(runtime)

        val decision = runBlocking { agent.reflect(testWorldState(), "模型重复输出不可用工具") }

        val abort = decision.shouldBeInstanceOf<PlannerDecision.Abort>()
        abort.reason shouldBe "反思器返回已禁用的 Retry 决策"
    }
})

private fun testPlanner(runtime: AgentRuntime): PlannerAgent {
    val worldStateManager = mockk<WorldStateManager>(relaxed = true)
    every { worldStateManager.toPlannerContext() } returns "当前状态: 可用"
    every { worldStateManager.toPromptContext() } returns "当前状态: 可用"
    every { worldStateManager.getState() } returns testWorldState()
    return PlannerAgent(
        runtime = runtime,
        worldStateManager = worldStateManager,
        modelConfig = ModelConfig(
            baseUrl = "http://test",
            modelName = "test-model",
            temperature = 0.1f,
            timeoutSeconds = 30
        )
    )
}

private fun mockRuntime(output: String): AgentRuntime {
    val runtime = mockk<AgentRuntime>()
    coEvery { runtime.runAgent(any(), any()) } returns AgentResult.Finished(output)
    return runtime
}

private fun mockRuntimeError(message: String): AgentRuntime {
    val runtime = mockk<AgentRuntime>()
    coEvery { runtime.runAgent(any(), any()) } returns AgentResult.Error(message)
    return runtime
}

private fun testWorldState(): WorldState {
    return WorldState(
        currentApp = "com.test.app",
        goal = "执行测试任务",
        strategy = listOf("进入目标应用", "完成任务"),
        stuckCount = 1
    )
}
