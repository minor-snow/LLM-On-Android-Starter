package dev.llmbridge.demo

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
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
import java.net.URI

/**
 * LLM Bridge Demo - Minimal Chat UI
 * 
 * Demonstrates:
 * - Streaming response display
 * - Connection status checking
 * - Graceful error handling
 * - Persistent Endpoint Selection
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
// Connection State Definition
// ============================================================

sealed class ConnectionState {
    object Checking : ConnectionState()
    data class Connected(val info: String) : ConnectionState()
    data class Degraded(val reason: String) : ConnectionState()
    data class Disconnected(val reason: String) : ConnectionState()
}

// ============================================================
// Endpoint Definitions
// ============================================================

enum class EndpointType(val label: String, val defaultUrl: String) {
    DEVICE("Device (adb reverse)", "http://127.0.0.1:8000"),
    EMULATOR("Emulator", "http://10.0.2.2:8000"),
    CUSTOM("Custom LAN", "")
}

data class EndpointConfig(
    val type: EndpointType,
    val url: String
)

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

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    
    // Persistence
    private val prefs = application.getSharedPreferences("llm_bridge_prefs", Context.MODE_PRIVATE)
    private val PREF_KEY_TYPE = "endpoint_type"
    private val PREF_KEY_URL = "endpoint_url"

    private var client: LLMClient
    
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    
    private val _state = MutableStateFlow<ResultState<Unit>>(ResultState.Idle)
    val state: StateFlow<ResultState<Unit>> = _state.asStateFlow()
    
    // Connection State
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Checking)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    // Endpoint State
    private val _currentEndpoint = MutableStateFlow(loadEndpoint())
    val currentEndpoint: StateFlow<EndpointConfig> = _currentEndpoint.asStateFlow()
    
    init {
        // Initialize client with loaded endpoint
        client = LLMClient(_currentEndpoint.value.url)
        checkConnection()
    }
    
    private fun loadEndpoint(): EndpointConfig {
        val typeStr = prefs.getString(PREF_KEY_TYPE, EndpointType.DEVICE.name)
        val url = prefs.getString(PREF_KEY_URL, EndpointType.DEVICE.defaultUrl) ?: EndpointType.DEVICE.defaultUrl
        
        val type = try {
            EndpointType.valueOf(typeStr ?: EndpointType.DEVICE.name)
        } catch (e: Exception) {
            EndpointType.DEVICE
        }
        
        return EndpointConfig(type, url)
    }
    
    fun updateEndpoint(type: EndpointType, customUrl: String?) {
        val newUrl = if (type == EndpointType.CUSTOM) {
            customUrl ?: ""
        } else {
            type.defaultUrl
        }
        
        // Basic validation for Custom
        if (type == EndpointType.CUSTOM) {
            if (newUrl.isBlank()) return
            // Ensure http/https
            val validUrl = if (!newUrl.startsWith("http")) "http://$newUrl" else newUrl
            
             saveEndpoint(EndpointConfig(type, validUrl))
        } else {
             saveEndpoint(EndpointConfig(type, newUrl))
        }
    }
    
    private fun saveEndpoint(config: EndpointConfig) {
        prefs.edit()
             .putString(PREF_KEY_TYPE, config.type.name)
             .putString(PREF_KEY_URL, config.url)
             .apply()
             
        _currentEndpoint.value = config
        
        // SWITCHING LOGIC
        _connectionState.value = ConnectionState.Checking
        client = LLMClient(config.url)
        checkConnection()
    }
    
    fun checkConnection() {
        viewModelScope.launch {
            if (_connectionState.value !is ConnectionState.Connected) {
                _connectionState.value = ConnectionState.Checking
            }
            
            when (val health = client.healthCheck()) {
                is LLMClient.HealthStatus.Ok -> {
                    _connectionState.value = ConnectionState.Connected("Mode: ${health.mode}")
                }
                is LLMClient.HealthStatus.Error -> {
                    // Policy: Health check failure -> Degraded (diagnosis), NEVER Disconnected.
                    // Disconnected status is reserved for "Zero Content Failure" in the main chat flow.
                    // Even if we were Connected, a health failure downgrades us to Degraded, not Disconnected.
                    _connectionState.value = ConnectionState.Degraded(
                        "${health.message}\n${health.suggestion}"
                    )
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
            var hasSeenChatEvidence = false 
            
            try {
                client.chat(text).collect { response ->
                    when (response) {
                        is LLMResponse.Token -> {
                            hasSeenChatEvidence = true
                            if (_connectionState.value !is ConnectionState.Connected) {
                                _connectionState.value = ConnectionState.Connected("Streaming...")
                            }
                            
                            streamingContent += response.content
                            _state.value = ResultState.Streaming(streamingContent)
                            updateAIMessage(aiMessageIndex, streamingContent)
                        }
                        is LLMResponse.Final -> {
                            hasSeenChatEvidence = true
                            if (_connectionState.value !is ConnectionState.Connected) {
                                _connectionState.value = ConnectionState.Connected("Completed")
                            }
                            
                            _state.value = ResultState.Completed(Unit)
                            updateAIMessage(aiMessageIndex, response.content)
                        }
                        is LLMResponse.Error -> {
                            if (hasSeenChatEvidence) {
                                _connectionState.value = ConnectionState.Degraded("Stream interrupted: ${response.message}")
                                _state.value = ResultState.Error("Interrupted", isRecoverable = true)
                                updateAIMessage(aiMessageIndex, streamingContent + " [Interrupted]") 
                            } else {
                                _connectionState.value = ConnectionState.Disconnected("${response.message}")
                                _state.value = ResultState.Error(response.message, isRecoverable = true)
                                updateAIMessage(aiMessageIndex, "Error: ${response.message}")
                            }
                        }
                        is LLMResponse.Meta -> { }
                    }
                }
            } catch (e: Exception) {
                val errorMsg = e.message ?: "Unknown error"
                 if (hasSeenChatEvidence) {
                    _connectionState.value = ConnectionState.Degraded("Stream interrupted: $errorMsg")
                    updateAIMessage(aiMessageIndex, streamingContent + " [Interrupted]")
                 } else {
                    _connectionState.value = ConnectionState.Disconnected(errorMsg)
                    updateAIMessage(aiMessageIndex, "Error: $errorMsg")
                 }
                _state.value = ResultState.Error(errorMsg)
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

class ChatMessage(
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
    val connectionState by viewModel.connectionState.collectAsState()
    val endpointConfig by viewModel.currentEndpoint.collectAsState()
    
    var inputText by remember { mutableStateOf("") }
    var showEndpointDialog by remember { mutableStateOf(false) }
    
    val listState = rememberLazyListState()
    
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }
    
    if (showEndpointDialog) {
        EndpointSelectionDialog(
            currentConfig = endpointConfig,
            onDismiss = { showEndpointDialog = false },
            onConfirm = { type, url -> 
                viewModel.updateEndpoint(type, url)
                showEndpointDialog = false
            }
        )
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // App Bar / Header
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 4.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "LLM Bridge Demo",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    
                    // Endpoint Switcher
                    IconButton(onClick = { showEndpointDialog = true }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Status Badge & URL Info
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ConnectionStatusBadge(state = connectionState)
                    Spacer(modifier = Modifier.weight(1f))
                     Text(
                        text = endpointConfig.type.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
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
fun EndpointSelectionDialog(
    currentConfig: EndpointConfig,
    onDismiss: () -> Unit,
    onConfirm: (EndpointType, String?) -> Unit
) {
    var selectedType by remember { mutableStateOf(currentConfig.type) }
    var customUrl by remember { mutableStateOf(if (currentConfig.type == EndpointType.CUSTOM) currentConfig.url else "") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Endpoint") },
        text = {
            Column {
                EndpointType.values().forEach { type ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedType = type }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (selectedType == type),
                            onClick = { selectedType = type }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(text = type.label, style = MaterialTheme.typography.bodyMedium)
                            if (type != EndpointType.CUSTOM) {
                                Text(
                                    text = type.defaultUrl, 
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }
                
                if (selectedType == EndpointType.CUSTOM) {
                    OutlinedTextField(
                        value = customUrl,
                        onValueChange = { customUrl = it },
                        label = { Text("Enter URL (e.g. 192.168.1.5:8000)") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selectedType, customUrl) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun ConnectionStatusBadge(state: ConnectionState) {
    val (color, text) = when (state) {
        is ConnectionState.Checking -> Color.Gray to "Checking..."
        is ConnectionState.Connected -> Color(0xFF4ADE80) to "Connected (${state.info})"
        is ConnectionState.Degraded -> Color(0xFFFBBF24) to "Degraded: ${state.reason}"
        is ConnectionState.Disconnected -> Color(0xFFEF4444) to "Disconnected: ${state.reason}"
    }
    
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color
    )
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
