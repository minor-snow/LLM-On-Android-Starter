package dev.llmbridge.client

import org.json.JSONObject
import java.io.BufferedReader

/**
 * Type-safe SSE event parser.
 * 
 * Parses Server-Sent Events with typed event structure:
 * - type: "token" | "final" | "error" | "meta"
 * - content/message/data: Event payload
 * 
 * Example SSE line:
 * ```
 * data: {"type": "token", "content": "Hello"}
 * ```
 */
object SSEParser {
    
    private const val DATA_PREFIX = "data: "
    
    /**
     * Parse a single SSE line into [LLMResponse].
     * 
     * @param line Raw SSE line (e.g., "data: {...}")
     * @return Parsed [LLMResponse] or null if line is empty/comment
     */
    fun parseLine(line: String): LLMResponse? {
        // Skip empty lines and comments
        if (line.isBlank() || line.startsWith(":")) {
            return null
        }
        
        // Extract data payload
        if (!line.startsWith(DATA_PREFIX)) {
            return null
        }
        
        val jsonStr = line.removePrefix(DATA_PREFIX).trim()
        if (jsonStr.isEmpty()) {
            return null
        }
        
        return try {
            parseJson(jsonStr)
        } catch (e: Exception) {
            LLMResponse.Error("Parse error: ${e.message}", 400)
        }
    }
    
    /**
     * Parse SSE stream from BufferedReader.
     * 
     * @param reader Input stream reader
     * @param onEvent Callback for each parsed event
     */
    suspend fun parseStream(
        reader: BufferedReader,
        onEvent: suspend (LLMResponse) -> Unit
    ) {
        reader.useLines { lines ->
            lines.forEach { line ->
                parseLine(line)?.let { event ->
                    onEvent(event)
                }
            }
        }
    }
    
    private fun parseJson(jsonStr: String): LLMResponse {
        val json = JSONObject(jsonStr)
        val type = json.optString("type", "")
        
        return when (type) {
            "token" -> LLMResponse.Token(
                content = json.optString("content", "")
            )
            "final" -> LLMResponse.Final(
                content = json.optString("content", "")
            )
            "error" -> LLMResponse.Error(
                message = json.optString("message", "Unknown error"),
                code = json.optInt("code", 500)
            )
            "meta" -> {
                val data = mutableMapOf<String, Any>()
                json.keys().forEach { key ->
                    if (key != "type") {
                        data[key] = json.get(key)
                    }
                }
                LLMResponse.Meta(data)
            }
            else -> LLMResponse.Error("Unknown event type: $type", 400)
        }
    }
}
