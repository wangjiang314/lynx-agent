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

package com.juwan.lynx.api

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

class LynxApiClientFunctionCallParsingTest : StringSpec({
    val settingsProvider = mockk<SettingsProvider>().apply {
        every { getApiBaseUrl() } returns "https://example.com/v1"
        every { getApiKey() } returns "test-key"
        every { getTextModel() } returns "test-model"
        every { getVisionModel() } returns "test-vision-model"
    }
    val client = LynxApiClient(settingsProvider)

    "parseFunctionCall should parse native tool_calls envelope" {
        val response = """
            {
              "choices": [
                {
                  "message": {
                    "tool_calls": [
                      {
                        "id": "call_1",
                        "type": "function",
                        "function": {
                          "name": "click",
                          "arguments": "{\"point\":\"<point>100 200</point>\"}"
                        }
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()

        val parsed = client.parseFunctionCall(response)
        parsed?.name shouldBe "click"
        parsed?.args?.get("point") shouldBe "<point>100 200</point>"
    }

    "parseFunctionCall should parse legacy function_call envelope" {
        val response = """
            {
              "choices": [
                {
                  "message": {
                    "function_call": {
                      "name": "type",
                      "arguments": "{\"text\":\"瑞幸咖啡\"}"
                    }
                  }
                }
              ]
            }
        """.trimIndent()

        val parsed = client.parseFunctionCall(response)
        parsed?.name shouldBe "type"
        parsed?.args?.get("text") shouldBe "瑞幸咖啡"
    }

    "parseFunctionCall should parse Reason plus trailing json tool call" {
        val response = """
            Reason: 当前在消息页面，应该找私信入口而非互动通知
            {"name":"click","args":{"point":"<point>200 300</point>"}}
        """.trimIndent()

        val parsed = client.parseFunctionCall(response)
        parsed?.name shouldBe "click"
        parsed?.args?.get("point") shouldBe "<point>200 300</point>"
    }

    "parseFunctionCall should prefer last valid json when reason contains example json" {
        val response = """
            Reason: 不应点击示例 {"name":"click","args":{"label":"互动通知"}}，应走私信入口
            {"name":"type","args":{"text":"私信"}}
        """.trimIndent()

        val parsed = client.parseFunctionCall(response)
        parsed?.name shouldBe "type"
        parsed?.args?.get("text") shouldBe "私信"
    }
})
