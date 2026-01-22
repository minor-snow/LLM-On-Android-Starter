package dev.llmbridge.client

/**
 * Request model for LLM chat API.
 *
 * @property message The user's message to send to the LLM
 * @property threadId Optional conversation thread ID for multi-turn conversations
 * @property metadata Optional key-value pairs for additional context
 */
data class LLMRequest(
    val message: String,
    val threadId: String? = null,
    val metadata: Map<String, Any>? = null
)
