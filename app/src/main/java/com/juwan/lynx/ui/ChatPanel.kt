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

package com.juwan.lynx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.juwan.lynx.agent.AgentPhase
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Main composable for the Chat Panel screen.
 */
@Composable
fun ChatPanel(
    viewModel: ChatViewModel,
    onOpenSettings: () -> Unit = {}
) {
    val messages by viewModel.messages.collectAsState()
    val userInput by viewModel.userInput.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val agentStatus by viewModel.agentStatus.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header 负责沉浸式状态栏
        ChatHeader(
            onOpenSettings = onOpenSettings
        )

        if (agentStatus != null && agentStatus!!.phase in setOf(AgentPhase.PLANNING, AgentPhase.EXECUTING, AgentPhase.REFLECTING)) {
            AgentStatusBar(
                phase = agentStatus!!.phase,
                message = agentStatus!!.message,
                observation = agentStatus!!.observation,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }

        // Message list
        ChatMessageList(
            messages = messages,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        )

        // Input bar: 只使用 imePadding，它会自动处理软键盘和导航栏
        Surface(
            tonalElevation = 2.dp,
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
        ) {
            ChatInputBar(
                userInput = userInput,
                onInputChange = { viewModel.updateUserInput(it) },
                onSend = { viewModel.sendMessage(userInput) },
                isLoading = isLoading,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun ChatHeader(
    onOpenSettings: () -> Unit = {}
) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        shadowElevation = 4.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Lynx Agent",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }

            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "设置",
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

@Composable
fun ChatMessageList(
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp)
    ) {
        items(messages) { message ->
            MessageBubble(message)
        }
    }
}

@Composable
fun MessageBubble(message: ChatMessage) {
    val isUser = message.isUserMessage
    val bubbleColor = when {
        isUser -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = when {
        isUser -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            modifier = Modifier.widthIn(max = 280.dp),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            colors = CardDefaults.cardColors(
                containerColor = bubbleColor
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Text(
                text = message.content,
                modifier = Modifier.padding(12.dp),
                color = contentColor,
                fontSize = 15.sp,
                lineHeight = 20.sp
            )
        }
    }
}

@Composable
fun ChatInputBar(
    userInput: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    isLoading: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextField(
            value = userInput,
            onValueChange = onInputChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("输入指令...", fontSize = 15.sp) },
            enabled = !isLoading,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent
            ),
            shape = RoundedCornerShape(24.dp),
            singleLine = false,
            maxLines = 4
        )

        IconButton(
            onClick = onSend,
            enabled = !isLoading && userInput.isNotBlank(),
            modifier = Modifier
                .background(
                    color = if (isLoading || userInput.isBlank()) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    shape = RoundedCornerShape(24.dp)
                )
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(8.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Send,
                    contentDescription = "Send",
                    tint = if (userInput.isBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onPrimary
                    }
                )
            }
        }
    }
}

@Composable
fun AgentStatusBar(
    phase: AgentPhase,
    message: String,
    observation: String? = null,
    modifier: Modifier = Modifier
) {
    val observationMap = parseObservationMap(observation)
    val app = observationMap["App"].orEmpty()
    val capabilities = observationMap["Capabilities"].orEmpty()
    val lastAction = observationMap["LastAction"].orEmpty()
    val lastResult = observationMap["LastResult"].orEmpty()
    val stuck = observationMap["Stuck"].orEmpty()
    val summaryLine = buildString {
        if (app.isNotBlank()) append("App ${compactField(app)}")
        if (capabilities.isNotBlank() && capabilities != "无") {
            if (isNotBlank()) append("  ·  ")
            append("能力 ${compactField(capabilities)}")
        }
        if ((phase == AgentPhase.EXECUTING || phase == AgentPhase.REFLECTING) && lastAction.isNotBlank() && lastAction != "无") {
            if (isNotBlank()) append("  ·  ")
            append("动作 ${compactField(lastAction)}")
        }
        if ((phase == AgentPhase.EXECUTING || phase == AgentPhase.REFLECTING) && lastResult.isNotBlank() && lastResult != "无") {
            if (isNotBlank()) append("  ·  ")
            append("结果 ${compactField(lastResult)}")
        }
        if ((phase == AgentPhase.EXECUTING || phase == AgentPhase.REFLECTING) && stuck.isNotBlank() && stuck != "0") {
            if (isNotBlank()) append("  ·  ")
            append("Stuck $stuck")
        }
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = phaseLabel(phase),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (message.isNotBlank()) {
                Text(
                    text = message,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (summaryLine.isNotBlank()) {
                Text(
                    text = summaryLine,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun phaseLabel(phase: AgentPhase): String {
    return when (phase) {
        AgentPhase.PLANNING -> "阶段: 规划中"
        AgentPhase.EXECUTING -> "阶段: 执行中"
        AgentPhase.REFLECTING -> "阶段: 反思中"
        AgentPhase.COMPLETED -> "阶段: 已完成"
        AgentPhase.FAILED -> "阶段: 失败"
    }
}

private fun parseObservationMap(observation: String?): Map<String, String> {
    if (observation.isNullOrBlank()) return emptyMap()
    return observation
        .split(", ")
        .mapNotNull { part ->
            val idx = part.indexOf("=")
            if (idx <= 0 || idx >= part.length - 1) return@mapNotNull null
            val key = part.substring(0, idx).trim()
            val value = part.substring(idx + 1).trim()
            if (key.isBlank()) null else key to value
        }
        .toMap()
}

private fun compactField(raw: String, max: Int = 16): String {
    val value = raw.trim()
    if (value.length <= max) return value
    return value.take(max) + "..."
}
