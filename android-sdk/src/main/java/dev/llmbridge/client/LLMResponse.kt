package dev.llmbridge.client

/**
 * Protocol-level events from SSE stream.
 * 
 * This represents the raw events received from the server.
 * For UI state management, use [ResultState] instead.
 * 
 * Event types match server-side SSE events:
 * - [Token]: Streaming chunk of text
 * - [Final]: Complete response when streaming finishes
 * - [Error]: Error occurred during generation
 * - [Meta]: Metadata about the request/response
 */
sealed class LLMResponse {
    
    /**
     * Streaming token chunk.
     * @property content The text chunk received
     */
    data class Token(val content: String) : LLMResponse()
    
    /**
     * Final complete response.
     * @property content The complete generated text
     */
    data class Final(val content: String) : LLMResponse()
    
    /**
     * Error event.
     * @property message Human-readable error description
     * @property code HTTP-like error code
     */
    data class Error(val message: String, val code: Int = 500) : LLMResponse()
    
    /**
     * Metadata event.
     * @property data Key-value pairs of metadata (mode, timing, etc.)
     */
    data class Meta(val data: Map<String, Any>) : LLMResponse()
}
