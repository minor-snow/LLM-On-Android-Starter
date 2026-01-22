package dev.llmbridge.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.llmbridge.client.LLMClient
import dev.llmbridge.client.LLMResponse
import dev.llmbridge.client.ResultState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * LLM Bridge Demo - Minimal Chat UI
 * 
 * Demonstrates:
 * - Streaming response display
 * - Connection status checking
 * - Graceful error handling
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LLMBridgeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ChatScreen()
                }
            }
        }
    }
}

// ============================================================
// Theme
// ============================================================

@Composable
fun LLMBridgeTheme(content: @Composable () -> Unit) {
    val darkColorScheme = darkColorScheme(
        primary = Color(0xFF6366F1),
        secondary = Color(0xFF818CF8),
        background = Color(0xFF1a1a2e),
        surface = Color(0xFF16213e),
        onPrimary = Color.White,
        onSecondary = Color.White,
        onBackground = Color.White,
        onSurface = Color.White
    )
    MaterialTheme(
        colorScheme = darkColorScheme,
        content = content
    )
}

// ============================================================
// ViewModel
// ============================================================

class ChatViewModel : ViewModel() {
    
    // Server URL - use 10.0.2.2 for emulator, localhost for device with adb reverse
    private val client = LLMClient("http://10.0.2.2:8000")
    
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    
    private val _state = MutableStateFlow<ResultState<Unit>>(ResultState.Idle)
    val state: StateFlow<ResultState<Unit>> = _state.asStateFlow()
    
    private val _connectionStatus = MutableStateFlow<String?>(null)
    val connectionStatus: StateFlow<String?> = _connectionStatus.asStateFlow()
    
    init {
        checkConnection()
    }
    
    fun checkConnection() {
        viewModelScope.launch {
            _connectionStatus.value = "Checking..."
            when (val health = client.healthCheck()) {
                is LLMClient.HealthStatus.Ok -> {
                    _connectionStatus.value = "Connected (${health.mode}, ${health.latencyMs}ms)"
                }
                is LLMClient.HealthStatus.Error -> {
                    _connectionStatus.value = "${health.message}\n${health.suggestion}"
                }
            }
        }
    }
    
    fun sendMessage(text: String) {
        if (text.isBlank()) return
        
        // Add user message
        _messages.value = _messages.value + ChatMessage(text, isUser = true)
        
        // Add placeholder for AI response
        val aiMessageIndex = _messages.value.size
        _messages.value = _messages.value + ChatMessage("", isUser = false)
        
        viewModelScope.launch {
            _state.value = ResultState.Connecting
            var streamingContent = ""
            
            try {
                client.chat(text).collect { response ->
                    when (response) {
                        is LLMResponse.Token -> {
                            streamingContent += response.content
                            _state.value = ResultState.Streaming(streamingContent)
                            updateAIMessage(aiMessageIndex, streamingContent)
                        }
                        is LLMResponse.Final -> {
                            _state.value = ResultState.Completed(Unit)
                            updateAIMessage(aiMessageIndex, response.content)
                        }
                        is LLMResponse.Error -> {
                            _state.value = ResultState.Error(response.message, isRecoverable = true)
                            updateAIMessage(aiMessageIndex, "Error: ${response.message}")
                        }
                        is LLMResponse.Meta -> {
                            // Metadata received, could log or display
                        }
                    }
                }
            } catch (e: Exception) {
                _state.value = ResultState.Error(e.message ?: "Unknown error")
                updateAIMessage(aiMessageIndex, "Error: ${e.message}")
            }
        }
    }
    
    private fun updateAIMessage(index: Int, content: String) {
        val current = _messages.value.toMutableList()
        if (index < current.size) {
            current[index] = ChatMessage(content, isUser = false)
            _messages.value = current
        }
    }
}

data class ChatMessage(
    val content: String,
    val isUser: Boolean
)

// ============================================================
// UI Components
// ============================================================

@Composable
fun ChatScreen(viewModel: ChatViewModel = viewModel()) {
    val messages by viewModel.messages.collectAsState()
    val state by viewModel.state.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    
    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header with connection status
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "LLM Bridge Demo",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                connectionStatus?.let { status ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (status.startsWith("Connected")) 
                            Color(0xFF4ADE80) 
                        else 
                            Color(0xFFFBBF24)
                    )
                }
            }
        }
        
        // Messages list
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            items(messages) { message ->
                MessageBubble(message)
            }
            
            // Streaming indicator
            if (state is ResultState.Streaming || state is ResultState.Connecting) {
                item {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (state is ResultState.Connecting) "Connecting..." else "Streaming...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
        
        // Input area
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type a message...") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (inputText.isNotBlank() && !state.isLoading) {
                                viewModel.sendMessage(inputText)
                                inputText = ""
                            }
                        }
                    ),
                    enabled = !state.isLoading,
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    )
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Button(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            viewModel.sendMessage(inputText)
                            inputText = ""
                        }
                    },
                    enabled = inputText.isNotBlank() && !state.isLoading,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("Send")
                }
            }
        }
    }
}

@Composable
fun MessageBubble(message: ChatMessage) {
    val bubbleColor = if (message.isUser) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surface
    }
    
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (message.isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (message.isUser) 16.dp else 4.dp,
                bottomEnd = if (message.isUser) 4.dp else 16.dp
            ),
            color = bubbleColor,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Text(
                text = message.content.ifEmpty { "..." },
                modifier = Modifier.padding(12.dp),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
