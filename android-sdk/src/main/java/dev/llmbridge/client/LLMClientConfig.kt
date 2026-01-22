package dev.llmbridge.client

import java.util.concurrent.TimeUnit

/**
 * Configuration for LLMClient.
 * 
 * Makes the SDK "framework-grade" by providing a clear configuration pattern.
 * 
 * Usage:
 * ```kotlin
 * val config = LLMClientConfig(
 *     baseUrl = "http://10.0.2.2:8000",
 *     connectTimeoutMs = 10_000,
 *     readTimeoutMs = 60_000,
 *     retryPolicy = RetryPolicy(maxRetries = 3, backoffMs = 1000)
 * )
 * val client = LLMClient(config)
 * ```
 */
data class LLMClientConfig(
    /**
     * Base URL of the LLM server.
     * For emulator: "http://10.0.2.2:8000"
     * For device with adb reverse: "http://localhost:8000"
     */
    val baseUrl: String,
    
    /**
     * Connection timeout in milliseconds.
     */
    val connectTimeoutMs: Long = 10_000,
    
    /**
     * Read timeout in milliseconds (for streaming).
     */
    val readTimeoutMs: Long = 60_000,
    
    /**
     * Write timeout in milliseconds.
     */
    val writeTimeoutMs: Long = 30_000,
    
    /**
     * Retry policy for failed requests.
     */
    val retryPolicy: RetryPolicy = RetryPolicy.DEFAULT,
    
    /**
     * Enable debug logging.
     */
    val debugMode: Boolean = false
) {
    companion object {
        /**
         * Default config for Android emulator.
         */
        fun forEmulator() = LLMClientConfig(
            baseUrl = "http://10.0.2.2:8000"
        )
        
        /**
         * Default config for real device with adb reverse.
         */
        fun forDevice() = LLMClientConfig(
            baseUrl = "http://localhost:8000"
        )
    }
}

/**
 * Retry policy for network failures.
 */
data class RetryPolicy(
    /**
     * Maximum number of retry attempts.
     */
    val maxRetries: Int = 0,
    
    /**
     * Initial backoff delay in milliseconds.
     */
    val backoffMs: Long = 1000,
    
    /**
     * Backoff multiplier for exponential backoff.
     */
    val backoffMultiplier: Double = 2.0,
    
    /**
     * Maximum backoff delay in milliseconds.
     */
    val maxBackoffMs: Long = 30_000
) {
    companion object {
        /**
         * No retries (fail fast).
         */
        val NONE = RetryPolicy(maxRetries = 0)
        
        /**
         * Default retry policy (3 retries with exponential backoff).
         */
        val DEFAULT = RetryPolicy(maxRetries = 3, backoffMs = 1000)
        
        /**
         * Aggressive retry policy for unreliable networks.
         */
        val AGGRESSIVE = RetryPolicy(maxRetries = 5, backoffMs = 500, maxBackoffMs = 60_000)
    }
}
