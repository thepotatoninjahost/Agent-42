package com.agent42.ui.chat

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agent42.core.ChatMessage
import com.agent42.memory.ReasoningMode

@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    isThinking: Boolean,
    currentTrace: ReasoningMode?,
    onSend: (String) -> Unit,
    onFeedback: (Long, Boolean, String?) -> Unit,
    voiceText: String,
    isListening: Boolean,
    onVoiceToggle: () -> Unit
) {
    val inputText = remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Auto-fill voice input when transcription arrives
    LaunchedEffect(voiceText) {
        if (voiceText.isNotBlank()) {
            inputText.value = voiceText
        }
    }

    // Auto-send when voice stops and text is present
    LaunchedEffect(isListening) {
        if (!isListening && inputText.value.isNotBlank()) {
            onSend(inputText.value)
            inputText.value = ""
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(messages) { message ->
                ChatBubble(message) { positive, correction -> onFeedback(message.id, positive, correction) }
            }
            if (isThinking) {
                item {
                    ThinkingIndicator(trace = currentTrace)
                }
            }
        }

        Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surface) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                IconButton(
                    onClick = onVoiceToggle,
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    Icon(
                        imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = if (isListening) "Stop listening" else "Voice input",
                        tint = if (isListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
                OutlinedTextField(
                    value = inputText.value,
                    onValueChange = { inputText.value = it },
                    placeholder = { Text(if (isListening) "Listening..." else "Ask anything...") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 4,
                    trailingIcon = {
                        if (inputText.value.isNotEmpty()) {
                            IconButton(onClick = {
                                onSend(inputText.value)
                                inputText.value = ""
                            }) {
                                Icon(Icons.Default.ArrowForward, "Send")
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ThinkingIndicator(trace: ReasoningMode?) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = trace?.let { "Thinking: ${it.name.replace("_", " ").lowercase().replaceFirstChar { c -> c.uppercase() } }" } ?: "Thinking...",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage, onFeedback: (Boolean, String?) -> Unit) {
    val isUser = message.role == "user"
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val bubbleColor = if (isUser)
        MaterialTheme.colorScheme.primary
    else
        MaterialTheme.colorScheme.surfaceVariant
    var showCorrectionDialog by remember { mutableStateOf(false) }
    var correctionText by remember { mutableStateOf("") }

    if (showCorrectionDialog) {
        AlertDialog(
            onDismissRequest = { showCorrectionDialog = false; correctionText = "" },
            title = { Text("What was wrong?") },
            text = {
                OutlinedTextField(
                    value = correctionText,
                    onValueChange = { correctionText = it },
                    placeholder = { Text("Explain what the AI got wrong...") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onFeedback(false, correctionText.ifBlank { null })
                    showCorrectionDialog = false
                    correctionText = ""
                }) { Text("Submit") }
            },
            dismissButton = {
                TextButton(onClick = {
                    onFeedback(false, null)
                    showCorrectionDialog = false
                    correctionText = ""
                }) { Text("Just thumbs down") }
            }
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        if (!isUser && message.reasoningMode != null) {
            Text(
                text = message.reasoningMode.replace("_", " "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
        }
        Surface(
            color = bubbleColor,
            shape = RoundedCornerShape(
                topStart = 16.dp, topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Text(
                text = message.content.ifBlank { if (message.isStreaming) "..." else "" },
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = if (isUser)
                    MaterialTheme.colorScheme.onPrimary
                else
                    MaterialTheme.colorScheme.onSurface
            )
        }
        if (!isUser && !message.isStreaming && !message.isError) {
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onFeedback(true, null) }) { Text("👍") }
                TextButton(onClick = { showCorrectionDialog = true }) { Text("👎") }
            }
        }
    }
}
