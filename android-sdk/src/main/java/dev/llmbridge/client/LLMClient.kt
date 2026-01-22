package dev.llmbridge.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * LLM Client for Android.
 * Android 端的 LLM 客户端。
 * 
 * Features / 特性:
 * - Streaming SSE support with type-safe events / 支持类型安全的 SSE 流式事件
 * - Health check for connectivity validation / 连接性健康检查
 * - Graceful error handling / 优雅的错误处理
 * 
 * Usage / 用法:
 * ```kotlin
 * val client = LLMClient("http://10.0.2.2:8000")
 * 
 * // Check connectivity first / 先检查连接
 * when (val health = client.healthCheck()) {
 *     is HealthStatus.Ok -> println("Connected in ${health.latencyMs}ms")
 *     is HealthStatus.Error -> println("Error: ${health.message}")
 * }
 * 
 * // Stream chat response / 接收流式响应
 * client.chat("Hello").collect { response ->
 *     when (response) {
 *         is LLMResponse.Token -> print(response.content)
 *         is LLMResponse.Final -> println("\nDone!")
 *         is LLMResponse.Error -> println("Error: ${response.message}")
 *     }
 * }
 * ```
 */
class LLMClient private constructor(
    private val config: LLMClientConfig,
    private val client: OkHttpClient
) {
    /**
     * Create LLMClient with full configuration.
     */
    constructor(config: LLMClientConfig) : this(
        config = config,
        client = OkHttpClient.Builder()
            .connectTimeout(config.connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(config.readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(config.writeTimeoutMs, TimeUnit.MILLISECONDS)
            .build()
    )
    
    /**
     * Create LLMClient with simple URL (backward compatible).
     */
    constructor(baseUrl: String, timeoutSeconds: Long = 60) : this(
        LLMClientConfig(
            baseUrl = baseUrl,
            readTimeoutMs = timeoutSeconds * 1000
        )
    )
    
    private val baseUrl: String get() = config.baseUrl
    
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    
    /**
     * Health check result.
     * 健康检查结果。
     */
    sealed class HealthStatus {
        /**
         * Server is reachable and responding.
         * 服务端可达且正常响应。
         * @property mode Server's current mode (echo/mock/llm) / 服务端当前模式
         * @property latencyMs Round-trip time in milliseconds / 往返延迟（毫秒）
         */
        data class Ok(
            val mode: String,
            val latencyMs: Long
        ) : HealthStatus()
        
        /**
         * Server is not reachable.
         * 服务端不可达。
         * @property message Error description / 错误描述
         * @property suggestion Troubleshooting hint / 故障排查建议
         */
        data class Error(
            val message: String,
            val suggestion: String
        ) : HealthStatus()
    }
    
    /**
     * Check server connectivity and get status.
     * 检查服务端连接性并获取状态。
     * 
     * Call this before [chat] to validate the connection
     * and provide helpful error messages to users.
     * 在调用 [chat] 之前调用此方法，以验证连接并向用户提供有用的错误信息。
     * 
     * @return [HealthStatus.Ok] with latency, or [HealthStatus.Error] with suggestion
     * @return [HealthStatus.Ok] (含延迟信息)，或 [HealthStatus.Error] (含建议)
     */
    suspend fun healthCheck(): HealthStatus = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        
        try {
            val request = Request.Builder()
                .url("$baseUrl/health")
                .get()
                .build()
            
            client.newCall(request).execute().use { response ->
                val latency = System.currentTimeMillis() - startTime
                
                if (!response.isSuccessful) {
                    return@withContext HealthStatus.Error(
                        message = "Server returned ${response.code}",
                        suggestion = "Check if the server is running correctly"
                    )
                }
                
                val body = response.body?.string() ?: "{}"
                val json = JSONObject(body)
                
                return@withContext HealthStatus.Ok(
                    mode = json.optString("mode", "unknown"),
                    latencyMs = latency
                )
            }
        } catch (e: java.net.ConnectException) {
            return@withContext HealthStatus.Error(
                message = "Server not reachable",
                suggestion = "Did you run: adb reverse tcp:8000 tcp:8000 ?"
            )
        } catch (e: java.net.SocketTimeoutException) {
            return@withContext HealthStatus.Error(
                message = "Connection timed out",
                suggestion = "Check your network connection and server status"
            )
        } catch (e: Exception) {
            return@withContext HealthStatus.Error(
                message = e.message ?: "Unknown error",
                suggestion = "Check logcat for details"
            )
        }
    }
    
    /**
     * Send a chat message and receive streaming response.
     * 发送聊天消息并接收流式响应。
     * 
     * @param message User's message / 用户消息
     * @param threadId Optional conversation thread ID / 可选的会话 ID
     * @return Flow of [LLMResponse] events / [LLMResponse] 事件流
     */
    fun chat(
        message: String,
        threadId: String? = null
    ): Flow<LLMResponse> = flow {
        val requestBody = JSONObject().apply {
            put("message", message)
            threadId?.let { put("thread_id", it) }
        }.toString().toRequestBody(jsonMediaType)
        
        val request = Request.Builder()
            .url("$baseUrl/chat")
            .post(requestBody)
            .header("Accept", "text/event-stream")
            .build()
        
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    emit(LLMResponse.Error(
                        message = "Server error: ${response.code}",
                        code = response.code
                    ))
                    return@flow
                }
                
                val reader = BufferedReader(
                    InputStreamReader(response.body?.byteStream())
                )
                
                // Use while loop instead of forEachLine for proper suspend support
                var line: String? = reader.readLine()
                while (line != null) {
                    SSEParser.parseLine(line)?.let { event ->
                        emit(event)
                    }
                    line = reader.readLine()
                }
            }
        } catch (e: java.net.ConnectException) {
            emit(LLMResponse.Error(
                message = "Connection failed. Run 'adb reverse tcp:8000 tcp:8000' for device testing.",
                code = 0
            ))
        } catch (e: Exception) {
            emit(LLMResponse.Error(
                message = e.message ?: "Unknown error",
                code = 500
            ))
        }
    }.flowOn(Dispatchers.IO)
}
