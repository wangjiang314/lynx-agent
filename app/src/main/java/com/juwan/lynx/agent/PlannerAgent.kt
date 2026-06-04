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

import android.util.Log
import com.juwan.lynx.agent.planner.PlanningContextBuilder
import com.juwan.lynx.agent.planner.ReflectionContextBuilder
import com.juwan.lynx.agent.planner.StrategyNormalizer
import com.juwan.lynx.runtime.AgentConfig
import com.juwan.lynx.runtime.AgentResult
import com.juwan.lynx.runtime.AgentRuntime
import com.juwan.lynx.runtime.ModelConfig
import com.juwan.lynx.state.WorldState
import com.juwan.lynx.state.WorldStateManager
import com.juwan.lynx.util.AppQueryNormalizer
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * PlannerAgent is responsible for understanding user instructions and producing high-level strategies.
 * It uses a text model (temperature 0.1) to generate plans and can reflect on failures to decide
 * whether to replan or abort.
 *
 * Key responsibilities:
 * - Parse user instructions into structured Strategy (goal, app, strategy)
 * - Incorporate minimal WorldState context
 * - Reflect on execution failures and decide next action (Replan/Abort)
 * - Limit reasoning to maximum 3 iterations per planning request
 *
 * @param runtime AgentRuntime implementation for LLM interaction
 * @param worldStateManager Provides current WorldState context
 * @param modelConfig Text model configuration (temperature 0.1)
 */
class PlannerAgent(
    private val runtime: AgentRuntime,
    private val worldStateManager: WorldStateManager,
    private val modelConfig: ModelConfig
) {
    private val TAG = "PlannerAgent"
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }
    private val planningContextBuilder = PlanningContextBuilder(
        worldStateManager = worldStateManager,
        logTag = TAG
    )
    private val reflectionContextBuilder = ReflectionContextBuilder()
    private val strategyNormalizer = StrategyNormalizer()

    companion object {
        private const val PLANNER_SYSTEM_PROMPT = """
你是一个端侧 AI Agent 规划器，负责理解用户的自然语言指令并生成高层次的执行策略。

## 你的职责
1. 分析用户指令，提取目标和目标应用
2. 生成清晰的高层策略步骤（3-7 步）
3. 仅参考当前最小必要状态事实
4. 输出结构化 TaskSpec，交给 Executor 自主执行，并交给 Verifier 判断完成

## 关键执行契约（必须遵守）
- 这是 **Goal-driven** 架构：Planner 只提供“目标 + 高层里程碑”，不提供坐标级或控件级细节
- Executor 会根据实时 WorldState 自主决定下一步动作
- 因此你的 strategy 是“导航提示（milestone hints）”，不是刚性脚本
- 避免把步骤写成“点击某坐标/连续固定操作序列”
- success_criteria 必须描述完成后可观察到的证据，不能描述点击脚本
- risk_hints 只描述可能阻塞或替代路径，不能包含 tool 名称、坐标或固定操作序列
- 原始指令中的字面文本、数字、顺序、多行/分行、否定条件（不要/不得/不能）和停留状态都是硬约束，不能在 goal 或 success_criteria 中被摘要丢失
- 如果用户要求“第一行/第二行/多行/分行”，success_criteria 必须要求可见文本保留对应行结构，不得合并为一行

## 输出格式
你必须返回 JSON 格式的 TaskSpec：
```json
{
  "goal": "用户目标的简洁描述",
  "target_app": "目标应用名称或包名",
  "strategy": [
    "里程碑1：高层目标描述",
    "里程碑2：高层目标描述",
    "里程碑3：高层目标描述"
  ],
  "success_criteria": [
    "当前屏幕或结果中能直接观察到的完成条件",
    "不存在与目标冲突的错误、待确认或未提交状态"
  ],
  "risk_hints": [
    "可能出现的弹窗、登录、验证码、权限或替代路径"
  ]
}
```

## 策略设计原则
- 步骤应保持高层次，强调阶段目标，不写死具体点击细节
- 每个步骤应是可感知的目标状态（如“到达可检索目标对象的状态”“进入目标会话状态”“完成提交并可见结果”）
- 每个步骤只表达一个可验证目标，避免把多个目标合并到同一条（避免“并且/并/然后”堆叠）
- 禁止把某个固定路径当作唯一成功条件（例如“必须走搜索入口”）；允许等价路径
- success_criteria 要描述“完成后应该能看到什么”，不要描述“应该点击什么”
- success_criteria 要保留用户要求的具体文本、数字、顺序、格式和禁止条件
- 考虑异常情况（弹窗、权限、网络慢）但不要过度展开成执行脚本
- 保持策略简洁，避免过度规划

## 示例
用户指令："在目标应用中找到示例条目并打开详情"
输出：
```json
{
  "goal": "找到示例条目并打开详情",
  "target_app": "目标应用",
  "strategy": [
    "进入目标应用并到达可查找目标对象的状态",
    "定位示例条目并进入可查看详情的状态",
    "确认详情内容已可见"
  ],
  "success_criteria": [
    "当前屏幕明确显示示例条目的详情内容",
    "没有加载失败、权限拦截、错误提示或确认弹窗"
  ],
  "risk_hints": [
    "如果默认入口不可用，可换用搜索或返回上一层重新定位目标对象",
    "如果出现登录、验证码或权限拦截，需要请求用户协助"
  ]
}
```
"""

        private const val REFLECTION_SYSTEM_PROMPT = """
你是一个端侧 AI Agent 反思器，负责基于最近的可观测事实决定是否 replan 或 abort。

## 你的输入
- 失败原因
- 当前 app / 目标 / 当前高层策略
- 最近动作和最近结果
- 最近可见文本与 stuck 信息

## 你的职责
1. 判断当前策略是否仍然适用
2. 如果不适用，输出新的高层策略
3. 如果任务当前无法继续，输出 abort

## 决策类型
1. **Replan**：当前策略与观察事实不匹配，需要换一种高层路径
2. **Abort**：当前任务在现有条件下无法继续

## 输出格式
你必须返回 JSON 格式的决策：

### Replan 格式
```json
{
  "decision": "replan",
  "strategy": {
    "goal": "调整后的目标",
    "target_app": "目标应用",
    "strategy": ["新的高层步骤1", "新的高层步骤2", ...],
    "success_criteria": ["新的可观察完成条件"],
    "risk_hints": ["新的风险提示"]
  }
}
```

### Abort 格式
```json
{
  "decision": "abort",
  "reason": "放弃的具体原因"
}
```

## 决策原则
- 只基于最近的可观测事实做决定
- 不要输出 Retry
- Replan 只能输出高层策略，不能输出 tool、坐标或连续微操脚本
- Replan 必须保留原任务中的字面文本、数字、顺序、多行/分行、否定条件和停留状态，不能把它们摘要丢失
- 如果最近事实已经显示任务无法继续，才选择 Abort
- 不要把失败分类模板当作真相，也不要发明隐藏页面阶段
"""
    }

    /**
     * Create a strategy from user instruction.
     *
     * Process:
     * 1. Build context from minimal WorldState facts
     * 2. Call LLM with planning prompt (temperature 0.1, max 3 iterations)
     * 3. Parse response into Strategy object
     *
     * @param userInstruction Natural language instruction from user
     * @return Strategy containing goal, target app, and high-level milestones
     */
    suspend fun createStrategy(userInstruction: String): Strategy {
        try {
            Log.i(TAG, "Creating strategy for instruction: $userInstruction")
            FlowTraceLogger.event(
                stage = "planner_create_start",
                kv = mapOf("instruction" to userInstruction)
            )

            // Build context with WorldState and experience
            val context = buildPlanningContext(userInstruction)

            // Create agent configuration for planning
            val config = AgentConfig(
                name = "PlannerAgent",
                model = modelConfig.copy(temperature = 0.1f),  // Low temperature for consistent planning
                systemPrompt = PLANNER_SYSTEM_PROMPT,
                tools = emptyList(),  // Planner doesn't use tools, just generates strategy
                maxIterations = 3  // Limit reasoning iterations
            )

            // Run the agent
            val result = runtime.runAgent(config, context)

            // Parse the strategy from result
            return when (result) {
                is AgentResult.Finished -> enrichStrategyWithUserInstruction(
                    parseStrategy(result.output),
                    userInstruction
                ).also { strategy ->
                    FlowTraceLogger.event(
                        stage = "planner_create_success",
                        kv = mapOf(
                            "goal" to strategy.goal,
                            "app" to strategy.app,
                            "strategy" to strategy.strategy.size
                        )
                    )
                }
                is AgentResult.MaxIterationsReached -> {
                    Log.w(TAG, "Max iterations reached, using fallback strategy")
                    FlowTraceLogger.warn(stage = "planner_create_fallback", kv = mapOf("reason" to "max_iterations"))
                    enrichStrategyWithUserInstruction(createFallbackStrategy(userInstruction), userInstruction)
                }
                is AgentResult.Error -> {
                    Log.e(TAG, "Planning error: ${result.message}")
                    FlowTraceLogger.warn(stage = "planner_create_fallback", kv = mapOf("reason" to result.message))
                    enrichStrategyWithUserInstruction(createFallbackStrategy(userInstruction), userInstruction)
                }
                else -> {
                    Log.e(TAG, "Unexpected result type: $result")
                    FlowTraceLogger.warn(stage = "planner_create_fallback", kv = mapOf("reason" to "unexpected_result"))
                    enrichStrategyWithUserInstruction(createFallbackStrategy(userInstruction), userInstruction)
                }
            }
        } catch (e: CancellationException) {
            FlowTraceLogger.warn(stage = "planner_create_cancelled", kv = mapOf("reason" to (e.message ?: "cancelled")))
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error creating strategy", e)
            FlowTraceLogger.warn(stage = "planner_create_exception", kv = mapOf("error" to (e.message ?: "unknown")))
            return enrichStrategyWithUserInstruction(createFallbackStrategy(userInstruction), userInstruction)
        }
    }

    /**
     * Reflect on execution failure and decide next action.
     *
     * Analyzes the failure reason and current WorldState to determine whether to:
     * - Replan with a new strategy
     * - Abort the task
     *
     * @param worldState Current WorldState with execution history
     * @param failureReason Description of why execution failed
     * @return PlannerDecision indicating the next action
     */
    suspend fun reflect(worldState: WorldState, failureReason: String): PlannerDecision {
        try {
            Log.i(TAG, "Reflecting on failure: $failureReason")
            FlowTraceLogger.event(
                stage = "planner_reflect_start",
                kv = mapOf(
                    "failure" to failureReason,
                    "stuck" to worldState.stuckCount,
                    "recent_actions" to worldState.recentActions.size
                )
            )

            // Build reflection context
            val context = buildReflectionContext(worldState, failureReason)

            // Create agent configuration for reflection
            val config = AgentConfig(
                name = "PlannerReflection",
                model = modelConfig.copy(temperature = 0.1f),
                systemPrompt = REFLECTION_SYSTEM_PROMPT,
                tools = emptyList(),
                maxIterations = 3
            )

            // Run the agent
            val result = runtime.runAgent(config, context)

            // Parse the decision from result
            return when (result) {
                is AgentResult.Finished -> parseDecision(result.output).also { decision ->
                    FlowTraceLogger.event(
                        stage = "planner_reflect_decision",
                        kv = mapOf("decision" to decision::class.simpleName.orEmpty())
                    )
                }
                is AgentResult.MaxIterationsReached -> {
                    Log.w(TAG, "Reflection max iterations reached, defaulting to Abort")
                    FlowTraceLogger.warn(stage = "planner_reflect_abort", kv = mapOf("reason" to "max_iterations"))
                    PlannerDecision.Abort("反思超时，无法确定下一步行动")
                }
                is AgentResult.Error -> {
                    Log.e(TAG, "Reflection error: ${result.message}")
                    FlowTraceLogger.warn(stage = "planner_reflect_abort", kv = mapOf("reason" to result.message))
                    PlannerDecision.Abort("反思失败: ${result.message}")
                }
                else -> {
                    Log.e(TAG, "Unexpected reflection result: $result")
                    FlowTraceLogger.warn(stage = "planner_reflect_abort", kv = mapOf("reason" to "unexpected_result"))
                    PlannerDecision.Abort("未知错误")
                }
            }
        } catch (e: CancellationException) {
            FlowTraceLogger.warn(stage = "planner_reflect_cancelled", kv = mapOf("reason" to (e.message ?: "cancelled")))
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error during reflection", e)
            FlowTraceLogger.warn(stage = "planner_reflect_exception", kv = mapOf("error" to (e.message ?: "unknown")))
            return PlannerDecision.Abort("反思异常: ${e.message}")
        }
    }

    fun reconcileReplannedStrategy(current: Strategy, replanned: Strategy): Strategy {
        val originalInstruction = replanned.originalInstruction
            .takeIf { it.isNotBlank() }
            ?: current.originalInstruction.takeIf { it.isNotBlank() }
            ?: current.goal.take(300)

        val merged = replanned.copy(
            goal = replanned.goal.trim().ifBlank { current.goal },
            app = replanned.app.trim().ifBlank { current.app },
            originalInstruction = originalInstruction
        )
        return normalizeStrategy(merged)
    }

    /**
     * Build planning context using only minimal current facts.
     */
    private suspend fun buildPlanningContext(userInstruction: String): String {
        return planningContextBuilder.buildPlanningContext(userInstruction)
    }

    /**
     * Build reflection context including failure reason and recent actions.
     */
    private fun buildReflectionContext(worldState: WorldState, failureReason: String): String {
        return reflectionContextBuilder.build(worldState, failureReason)
    }

    /**
     * Parse strategy from LLM response.
     * Extracts JSON from response and deserializes into Strategy object.
     */
    private fun parseStrategy(response: String): Strategy {
        val candidates = buildJsonCandidates(response)
        for (candidate in candidates) {
            try {
                return normalizeStrategy(parseStrategyObject(json.parseToJsonElement(candidate)))
            } catch (_: Exception) {
                // Try next candidate
            }
        }
        Log.e(TAG, "Failed to parse strategy from response (Fix with AI)")
        return normalizeStrategy(extractStrategyManually(unescapeJsonLikeText(response)))
    }

    /**
     * Parse decision from LLM response.
     * Extracts JSON and deserializes into appropriate PlannerDecision subclass.
     */
    private fun parseDecision(response: String): PlannerDecision {
        val candidates = buildJsonCandidates(response)
        for (candidate in candidates) {
            try {
                val obj = json.parseToJsonElement(candidate).jsonObject
                val decisionType = obj["decision"]?.jsonPrimitive?.contentOrNull?.lowercase()
                when (decisionType) {
                    "retry" -> {
                        Log.w(TAG, "Reflection produced deprecated retry decision, coercing to Abort")
                        return PlannerDecision.Abort("反思器返回已禁用的 Retry 决策")
                    }
                    "replan" -> {
                        val strategyElement = obj["strategy"]
                            ?: return PlannerDecision.Abort("重规划缺少 strategy 字段")
                        val strategy = parseStrategy(strategyElement.toString())
                        return PlannerDecision.Replan(strategy)
                    }
                    "abort" -> {
                        val reason = obj["reason"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                        return PlannerDecision.Abort(reason.ifBlank { "任务无法完成" })
                    }
                }
            } catch (_: Exception) {
                // Try next candidate
            }
        }

        // Regex fallback for non-standard responses
        return try {
            val normalized = unescapeJsonLikeText(response)
            val decisionType = Regex(""""decision"\s*:\s*"(\w+)"""")
                .find(normalized)?.groupValues?.get(1)
            when (decisionType?.lowercase()) {
                "retry" -> {
                    Log.w(TAG, "Reflection regex fallback produced deprecated retry decision")
                    PlannerDecision.Abort("反思器返回已禁用的 Retry 决策")
                }
                "abort" -> {
                    val reason = Regex("""\"reason\"\s*:\s*\"([^\"]+)\"""")
                        .find(normalized)?.groupValues?.get(1) ?: "任务无法完成"
                    PlannerDecision.Abort(reason)
                }
                else -> {
                    Log.w(TAG, "Unknown decision type: $decisionType, defaulting to Abort")
                    PlannerDecision.Abort("无法解析决策类型")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse decision from response", e)
            PlannerDecision.Abort("解析决策失败: ${e.message}")
        }
    }

    private fun buildJsonCandidates(response: String): List<String> {
        val seeds = mutableListOf<String>()
        val stripped = stripCodeFence(response).trim()
        if (stripped.isNotBlank()) seeds.add(stripped)
        val unescaped = unescapeJsonLikeText(stripped)
        if (unescaped.isNotBlank()) seeds.add(unescaped)

        val candidates = mutableListOf<String>()
        for (seed in seeds) {
            if (seed.isNotBlank()) candidates.add(seed.trim())
            extractFirstJsonObject(seed)?.let { candidates.add(it.trim()) }
            val seedUnescaped = unescapeJsonLikeText(seed)
            if (seedUnescaped.isNotBlank()) candidates.add(seedUnescaped.trim())
            extractFirstJsonObject(seedUnescaped)?.let { candidates.add(it.trim()) }
        }

        return candidates
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun stripCodeFence(text: String): String {
        val codeBlock = Regex(
            """```(?:json)?\s*(.*?)\s*```""",
            setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL)
        ).find(text)
        return codeBlock?.groupValues?.get(1) ?: text
    }

    private fun unescapeJsonLikeText(input: String): String {
        var text = input.trim()
        if (text.isBlank()) return text

        // If model wrapped JSON as a JSON-string, decode once first.
        if ((text.startsWith("\"") && text.endsWith("\"")) ||
            (text.startsWith("'") && text.endsWith("'"))
        ) {
            runCatching { json.decodeFromString<String>(text) }
                .onSuccess { decoded -> text = decoded.trim() }
        }

        if (text.contains("\\\"") || text.contains("\\n") || text.contains("\\t") || text.contains("\\r")) {
            text = text
                .replace("\\r", "\r")
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .trim()
        }
        return text
    }

    private fun extractFirstJsonObject(text: String): String? {
        var start = -1
        var depth = 0
        var inString = false
        var escaped = false

        for (i in text.indices) {
            val c = text[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (c == '\\') {
                escaped = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (inString) continue

            if (c == '{') {
                if (depth == 0) start = i
                depth++
            } else if (c == '}') {
                if (depth > 0) {
                    depth--
                    if (depth == 0 && start >= 0) {
                        return text.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }

    /**
     * Manually extract strategy from response as fallback.
     * Uses regex patterns to find goal, app, and strategy milestones.
     */
    private fun extractStrategyManually(response: String): Strategy {
        val goalMatch = Regex(""""goal"\s*:\s*"([^"]+)"""").find(response)
        val appMatch = Regex(""""(?:target_app|app)"\s*:\s*"([^"]+)"""").find(response)
        val strategyMatch = Regex(""""strategy"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(response)
        val criteriaMatch = Regex(""""success_criteria"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(response)
        val riskMatch = Regex(""""risk_hints"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(response)

        val goal = goalMatch?.groupValues?.get(1) ?: "未知目标"
        val app = appMatch?.groupValues?.get(1) ?: "unknown"
        val strategyStr = strategyMatch?.groupValues?.get(1) ?: ""
        val milestones = Regex(""""([^"]+)"""").findAll(strategyStr)
            .map { it.groupValues[1] }
            .toList()
            .ifEmpty { listOf("执行任务") }
        val criteria = criteriaMatch?.groupValues?.get(1)
            ?.let { extractJsonStringArray(it) }
            .orEmpty()
        val risks = riskMatch?.groupValues?.get(1)
            ?.let { extractJsonStringArray(it) }
            .orEmpty()

        return normalizeStrategy(
            Strategy(
                goal = goal,
                app = app,
                strategy = milestones,
                successCriteria = criteria,
                riskHints = risks
            )
        )
    }

    /**
     * Create a fallback strategy when planning fails.
     * Generates a simple strategy based on the user instruction.
     */
    private suspend fun createFallbackStrategy(userInstruction: String): Strategy {
        Log.w(TAG, "Creating fallback strategy for: $userInstruction")

        // Fallback app query: package literal -> model inference -> current foreground app
        val app = inferFallbackAppQuery(userInstruction)

        return normalizeStrategy(Strategy(
            goal = userInstruction,
            app = app,
            strategy = listOf(
                "启动目标应用",
                "执行用户指令",
                "完成任务"
            ),
            successCriteria = defaultSuccessCriteria(userInstruction),
            riskHints = defaultRiskHints()
        ))
    }

    private fun normalizeStrategy(strategy: Strategy): Strategy {
        val normalized = strategyNormalizer.normalize(strategy)
        return normalized.copy(
            successCriteria = sanitizePlanningList(normalized.successCriteria)
                .ifEmpty { defaultSuccessCriteria(normalized.goal) }
                .take(6),
            riskHints = sanitizePlanningList(normalized.riskHints)
                .ifEmpty { defaultRiskHints() }
                .take(6)
        )
    }

    private fun enrichStrategyWithUserInstruction(
        strategy: Strategy,
        userInstruction: String
    ): Strategy {
        val originalInstruction = userInstruction.trim().take(300)
        val fidelityCriteria = instructionFidelityCriteria(originalInstruction)
        val successCriteria = (fidelityCriteria + strategy.successCriteria)
            .distinct()
            .take(8)
        return strategy.copy(
            originalInstruction = originalInstruction,
            successCriteria = successCriteria
        ).copy(
            app = normalizeAppQueryForInstruction(
                plannedApp = strategy.app
            )
        )
    }

    private fun parseStrategyObject(element: JsonElement): Strategy {
        val obj = element.jsonObject
        val goal = obj["goal"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val app = obj["target_app"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: obj["app"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: ""
        return Strategy(
            goal = goal,
            app = app,
            strategy = obj.stringArray("strategy"),
            successCriteria = obj.stringArray("success_criteria").ifEmpty {
                obj.stringArray("successCriteria")
            },
            riskHints = obj.stringArray("risk_hints").ifEmpty {
                obj.stringArray("riskHints")
            }
        )
    }

    private fun Map<String, JsonElement>.stringArray(key: String): List<String> {
        val array = this[key] as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            item.jsonPrimitive.contentOrNull
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }
    }

    private fun extractJsonStringArray(body: String): List<String> {
        return Regex(""""([^"]+)"""").findAll(body)
            .map { it.groupValues[1] }
            .toList()
    }

    private fun sanitizePlanningList(values: List<String>): List<String> {
        return values
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .map { text ->
                text
                    .replace(
                        Regex(
                            """\b(click|type|scroll|drag|long_press|open_app|press_back|press_home|resolve_installed_app|type_and_enter|finished)\b""",
                            RegexOption.IGNORE_CASE
                        ),
                        " "
                    )
                    .replace(Regex("""<point>.*?</point>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
                    .replace(Regex("""-?\d+\s*,\s*-?\d+"""), " ")
                    .replace(Regex("""\(\s*-?\d+\s*,\s*-?\d+\s*\)"""), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
            }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun instructionFidelityCriteria(instruction: String): List<String> {
        if (instruction.isBlank()) return emptyList()

        val criteria = mutableListOf<String>()
        val quoted = extractQuotedLiterals(instruction)
        if (quoted.isNotEmpty()) {
            criteria += "最终可见结果必须包含原始指令中的字面内容：${quoted.joinToString(" / ") { "“$it”" }}"
        }
        if (quoted.size >= 2) {
            criteria += "这些字面内容必须按原始指令中的顺序出现，不得遗漏、替换或合并为不符合要求的内容"
        }
        if (hasLineStructureRequirement(instruction)) {
            criteria += "如果原始指令要求第一行、第二行、多行或分行，最终文本必须保留对应分行结构，不能合并成一行"
        }
        if (hasNegativeRequirement(instruction)) {
            criteria += "必须遵守原始指令中的禁止条件（如不要、不得、不能、不启动、不提交），可见状态不能与其冲突"
        }
        if (hasStayRequirement(instruction)) {
            criteria += "如果原始指令要求停留在某页面或结果状态，完成时必须仍停留在该可见状态"
        }
        return criteria.take(5)
    }

    private fun extractQuotedLiterals(instruction: String): List<String> {
        val patterns = listOf(
            Regex("“([^”]{1,80})”"),
            Regex("\"([^\"]{1,80})\""),
            Regex("'([^']{1,80})'"),
            Regex("`([^`]{1,80})`")
        )
        return patterns
            .flatMap { pattern ->
                pattern.findAll(instruction).mapNotNull { match ->
                    match.groupValues.getOrNull(1)
                        ?.replace(Regex("\\s+"), " ")
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                }.toList()
            }
            .distinct()
            .take(4)
    }

    private fun hasLineStructureRequirement(instruction: String): Boolean {
        return Regex("""(第一行|第二行|第[一二三四五六七八九十0-9]+行|多行|分行|换行|每行|line)""", RegexOption.IGNORE_CASE)
            .containsMatchIn(instruction)
    }

    private fun hasNegativeRequirement(instruction: String): Boolean {
        return Regex("""(不要|不得|不能|不启动|不开始|不提交|不发送|不发布|不付款|no\s+|not\s+|don't|do not)""", RegexOption.IGNORE_CASE)
            .containsMatchIn(instruction)
    }

    private fun hasStayRequirement(instruction: String): Boolean {
        return Regex("""(停留|停在|留在|保持|结果页|编辑页面|搜索结果页|页面)""")
            .containsMatchIn(instruction)
    }

    private fun defaultSuccessCriteria(goal: String): List<String> {
        val summary = goal.trim().ifBlank { "用户目标" }.take(80)
        return listOf(
            "当前屏幕或可见结果能直接证明“$summary”已经完成",
            "没有待确认弹窗、错误提示、未提交输入态或明显失败状态"
        )
    }

    private fun defaultRiskHints(): List<String> {
        return listOf(
            "如果出现登录、验证码、权限、风控或账号选择拦截，请请求用户协助",
            "如果当前入口不可用，换用搜索、返回上一层或重新打开目标应用等高层替代路径"
        )
    }

    private fun normalizeAppQueryForInstruction(
        plannedApp: String
    ): String {
        val normalizedPlanned = plannedApp.trim().ifBlank { "unknown" }
        return if (looksLikePackageName(normalizedPlanned)) {
            normalizedPlanned.lowercase()
        } else {
            normalizedPlanned
        }
    }

    private fun looksLikePackageName(value: String): Boolean {
        return Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+").matches(value)
    }

    private suspend fun inferFallbackAppQuery(instruction: String): String {
        val packagePattern = Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+")
        val packageMatch = packagePattern.find(instruction.trim())
        if (packageMatch != null) return packageMatch.value

        AppQueryNormalizer.extractedLaunchTargetOrNull(instruction)?.let { return it }

        val modelInferred = inferAppQueryWithModel(instruction)
        if (!modelInferred.isNullOrBlank() && !modelInferred.equals("unknown", ignoreCase = true)) {
            return AppQueryNormalizer.normalizeLaunchQuery(modelInferred)
        }

        return instruction
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(80)
            .ifBlank { "unknown" }
    }

    private suspend fun inferAppQueryWithModel(instruction: String): String? {
        return try {
            val config = AgentConfig(
                name = "PlannerAppInfer",
                model = modelConfig.copy(temperature = 0.0f),
                systemPrompt = """
你是一个应用目标提取器。请从用户指令中提取最可能的目标应用（应用名或包名）。
输出 JSON：
{"app_query":"应用名或包名，若无法判断填unknown"}
仅输出 JSON。
                """.trimIndent(),
                tools = emptyList(),
                maxIterations = 1
            )
            val result = runtime.runAgent(config, "用户指令: $instruction")
            val output = (result as? AgentResult.Finished)?.output?.trim().orEmpty()
            if (output.isBlank()) return null
            parseAppQueryFromJson(output)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "inferAppQueryWithModel failed: ${e.message}")
            null
        }
    }

    private fun parseAppQueryFromJson(raw: String): String? {
        val cleaned = raw
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val jsonText = cleaned.substringAfter("{", "").let { body ->
            if (body.isBlank()) return@let ""
            "{${body.substringBeforeLast("}", "")}}"
        }
        if (jsonText.isBlank()) return null

        return try {
            val obj = json.parseToJsonElement(jsonText).jsonObject
            obj["app_query"]?.jsonPrimitive?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Strategy data class representing a high-level execution plan.
 *
 * @property goal User's task goal (concise description)
 * @property app Target application name or package name
 * @property strategy List of high-level strategy milestones (3-7 recommended)
 */
@Serializable
data class Strategy(
    val goal: String,
    val app: String,
    val strategy: List<String>,
    val successCriteria: List<String> = emptyList(),
    val riskHints: List<String> = emptyList(),
    val originalInstruction: String = ""
)

@Serializable
data class TaskSpec(
    val goal: String,
    val targetApp: String,
    val strategy: List<String>,
    val successCriteria: List<String>,
    val riskHints: List<String>
)

fun Strategy.toTaskSpec(): TaskSpec {
    return TaskSpec(
        goal = goal,
        targetApp = app,
        strategy = strategy,
        successCriteria = successCriteria,
        riskHints = riskHints
    )
}

/**
 * PlannerDecision sealed class representing the outcome of reflection.
 * Indicates whether to replan or abort the task.
 */
sealed class PlannerDecision {
    /**
     * Replan with a new strategy.
     * Used when the current strategy has issues and needs adjustment.
     *
     * @property newStrategy The adjusted strategy to execute
     */
    data class Replan(val newStrategy: Strategy) : PlannerDecision()

    /**
     * Abort the task.
     * Used when the task cannot be completed.
     *
     * @property reason Specific reason for aborting
     */
    data class Abort(val reason: String) : PlannerDecision()
}
