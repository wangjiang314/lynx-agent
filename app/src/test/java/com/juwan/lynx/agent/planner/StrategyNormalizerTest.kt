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

package com.juwan.lynx.agent.planner

import com.juwan.lynx.agent.Strategy
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

class StrategyNormalizerTest : FunSpec({
    val normalizer = StrategyNormalizer()

    test("normalize should use generic default milestones when strategy is empty") {
        val normalized = normalizer.normalize(
            Strategy(
                goal = "给好友发送消息",
                app = "com.test.app",
                strategy = emptyList()
            )
        )

        normalized.strategy shouldBe listOf(
            "进入目标应用并到达任务入口",
            "围绕“给好友发送消息”推进核心流程",
            "确认目标已达成并完成任务"
        )
    }

    test("normalize should abstract tool and coordinate detail out of scripted milestones") {
        val normalized = normalizer.normalize(
            Strategy(
                goal = "给好友张三发送消息",
                app = "com.test.app",
                strategy = listOf("click <point>100 200</point> 然后 type 张三 再 press_back")
            )
        )

        normalized.strategy shouldHaveSize 1
        normalized.strategy.first().shouldNotContain("click")
        normalized.strategy.first().shouldNotContain("type")
        normalized.strategy.first().shouldNotContain("<point>")
        normalized.strategy.first().shouldNotContain("100 200")
        normalized.strategy.first() shouldBe "围绕“给好友张三发送消息”推进到下一可验证状态"
    }
})
