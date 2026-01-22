package dev.llmbridge.client

/**
 * UI-layer state for LLM interactions.
 * 
 * This is separate from [LLMResponse] (protocol layer) to provide
 * a cleaner abstraction for UI components.
 * 
 * Lifecycle:
 * [Idle] -> [Connecting] -> [Streaming] -> [Completed]
 *                       \-> [Error]
 */
sealed class ResultState<out T> {
    
    /**
     * Initial state, no request in progress.
     */
    object Idle : ResultState<Nothing>()
    
    /**
     * Connecting to server, waiting for first response.
     */
    object Connecting : ResultState<Nothing>()
    
    /**
     * Actively receiving streaming tokens.
     * @property partialContent Content received so far
     * @property tokenCount Number of tokens received
     */
    data class Streaming(
        val partialContent: String,
        val tokenCount: Int = 0
    ) : ResultState<Nothing>()
    
    /**
     * Successfully completed.
     * @property data The final result
     */
    data class Completed<T>(val data: T) : ResultState<T>()
    
    /**
     * Error occurred.
     * @property message Human-readable error message
     * @property isRecoverable Whether retry might succeed
     */
    data class Error(
        val message: String,
        val isRecoverable: Boolean = true
    ) : ResultState<Nothing>()
    
    /**
     * Convenience properties for UI binding.
     */
    val isLoading: Boolean
        get() = this is Connecting || this is Streaming
    
    val isError: Boolean
        get() = this is Error
    
    val isSuccess: Boolean
        get() = this is Completed
}
