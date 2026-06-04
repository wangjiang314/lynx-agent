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
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class OtherApiClientFunctionCallParsingTest : StringSpec({
    val settingsProvider = mockk<OtherSettingsProvider>().apply {
        every { getApiType() } returns "other"
        every { getApiBaseUrl() } returns "https://example.com/v1"
        every { getApiKey() } returns "unused"
        every { getTextModel() } returns "unused"
        every { getVisionModel() } returns "unused"
        every { getOtherApiUrl() } returns "https://example.com/other"
        every { getOtherId() } returns "test-id"
        every { getOtherApiKey() } returns "test-key"
        every { getOtherCompany() } returns "test-company"
        every { getOtherTextModel() } returns "test-text-model"
        every { getOtherVisionModel() } returns "test-vision-model"
    }
    val client = OtherApiClient(settingsProvider)

    "parseFunctionCall should parse raw pseudo function text" {
        val parsed = client.parseFunctionCall(
            "click(point='<point>921 69</point>')"
        )

        parsed?.name shouldBe "click"
        parsed?.args?.get("point") shouldBe "<point>921 69</point>"
    }

    "parseFunctionCall should parse Other envelope content with pseudo function text" {
        val response = """
            {
              "choices": [
                {
                  "message": {
                    "content": "Reason: 先点击顶部入口\nclick(point='<point>921 69</point>')"
                  }
                }
              ]
            }
        """.trimIndent()

        val parsed = client.parseFunctionCall(response)

        parsed?.name shouldBe "click"
        parsed?.args?.get("point") shouldBe "<point>921 69</point>"
    }

    "parseFunctionCall should parse native tool_calls envelope" {
        val response = """
            {
              "choices": [
                {
                  "message": {
                    "tool_calls": [
                      {
                          "type": "function",
                          "function": {
                          "name": "click",
                          "arguments": "{\"point\":\"700 300\"}"
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
        parsed?.args?.get("point") shouldBe "700 300"
    }

    "parseFunctionCall should parse envelope content array" {
        val response = """
            {
              "choices": [
                {
                  "message": {
                    "content": [
                      {"type": "text", "text": "Reason: 点击可见坐标"},
                      {"type": "text", "text": "{\"name\":\"click\",\"args\":{\"point\":\"710 310\"}}"}
                    ]
                  }
                }
              ]
            }
        """.trimIndent()

        val parsed = client.parseFunctionCall(response)

        parsed?.name shouldBe "click"
        parsed?.args?.get("point") shouldBe "710 310"
    }

    "chat should preserve native tool_calls envelope when content is empty" {
        val responseBody = """
            {
              "choices": [
                {
                  "message": {
                    "content": null,
                    "tool_calls": [
                      {
                          "type": "function",
                          "function": {
                          "name": "click",
                          "arguments": "{\"point\":\"720 320\"}"
                        }
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()
        val httpClient = fakeHttpClient(responseBody)
        val apiClient = OtherApiClient(settingsProvider, httpClient)

        val output = runBlocking {
            apiClient.chat(
                systemPrompt = "system",
                messages = listOf(ChatMessage(role = "user", content = "input")),
                temperature = 0.0f,
                tools = listOf(ApiToolDefinition(name = "click", description = "Click target")),
                timeoutSeconds = 10
            )
        }

        output shouldContain "tool_calls"
        val parsed = apiClient.parseFunctionCall(output)
        parsed?.name shouldBe "click"
        parsed?.args?.get("point") shouldBe "720 320"
    }
})

private fun fakeHttpClient(responseBody: String): OkHttpClient {
    return OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(responseBody.toResponseBody("application/json".toMediaType()))
                .build()
        })
        .build()
}
