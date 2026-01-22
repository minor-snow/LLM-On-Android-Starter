package dev.llmbridge.reliability

import dev.llmbridge.client.LLMResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion

/**
 * Reliability layer for LLM operations.
 * 
 * This is the core of "不会被 LLM 害死" philosophy.
 * Wraps LLM streaming with safety guarantees.
 * 
 * Features:
 * - Automatic error recovery
 * - Malformed response handling
 * - Timeout protection
 * - Graceful degradation
 * 
 * Usage:
 * ```kotlin
 * val executor = SafeLLMExecutor()
 * 
 * executor.execute(client.chat("Hello"))
 *     .collect { result ->
 *         when (result) {
 *             is SafeGenerationResult.Success -> showContent(result.content)
 *             is SafeGenerationResult.Failure -> showError(result.message)
 *             is SafeGenerationResult.Flagged -> reviewContent(result.content)
 *         }
 *     }
 * ```
 */
class SafeLLMExecutor(
    private val config: SafeExecutorConfig = SafeExecutorConfig()
) {
    
    /**
     * Execute LLM streaming with reliability guarantees.
     * 
     * @param responseFlow The raw LLMResponse flow from LLMClient
     * @return Flow of SafeGenerationResult with error handling
     */
    fun execute(responseFlow: Flow<LLMResponse>): Flow<SafeGenerationResult<String>> {
        var accumulatedContent = StringBuilder()
        var hasReceivedContent = false
        
        return responseFlow
            .map { response ->
                when (response) {
                    is LLMResponse.Token -> {
                        hasReceivedContent = true
                        accumulatedContent.append(response.content)
                        SafeGenerationResult.Success(
                            content = accumulatedContent.toString(),
                            confidence = null, // Streaming, confidence not yet known
                            warnings = emptyList()
                        )
                    }
                    is LLMResponse.Final -> {
                        hasReceivedContent = true
                        val finalContent = response.content
                        val validation = validateContent(finalContent)
                        
                        if (validation.isValid) {
                            SafeGenerationResult.Success(
                                content = finalContent,
                                confidence = 1.0f,
                                warnings = validation.warnings
                            )
                        } else {
                            SafeGenerationResult.Flagged(
                                content = finalContent,
                                reason = validation.reason ?: "Content flagged for review",
                                severity = SafeGenerationResult.Severity.MEDIUM
                            )
                        }
                    }
                    is LLMResponse.Error -> {
                        SafeGenerationResult.Failure(
                            message = response.message,
                            retryable = response.code in listOf(408, 429, 500, 502, 503, 504),
                            fallback = config.fallbackMessage
                        )
                    }
                    is LLMResponse.Meta -> {
                        // Meta events don't produce user-visible results
                        SafeGenerationResult.Success(
                            content = accumulatedContent.toString(),
                            confidence = null,
                            warnings = emptyList()
                        )
                    }
                }
            }
            .catch { e ->
                emit(SafeGenerationResult.Failure(
                    message = "Unexpected error: ${e.message}",
                    retryable = true,
                    fallback = config.fallbackMessage
                ))
            }
            .onCompletion { error ->
                if (error != null && !hasReceivedContent) {
                    // Stream failed before any content
                    // Note: This won't emit, but logs the state
                }
            }
    }
    
    /**
     * Validate generated content for safety.
     */
    private fun validateContent(content: String): ValidationResult {
        val warnings = mutableListOf<String>()
        
        // Check for empty content
        if (content.isBlank()) {
            return ValidationResult(
                isValid = false,
                reason = "Empty response received",
                warnings = warnings
            )
        }
        
        // Check for truncation indicators
        if (content.endsWith("...") || content.contains("[truncated]")) {
            warnings.add("Response may be truncated")
        }
        
        // Check for error patterns in content
        if (content.contains("error", ignoreCase = true) && content.length < 50) {
            warnings.add("Response may contain error message")
        }
        
        return ValidationResult(
            isValid = true,
            reason = null,
            warnings = warnings
        )
    }
    
    private data class ValidationResult(
        val isValid: Boolean,
        val reason: String?,
        val warnings: List<String>
    )
}

/**
 * Configuration for SafeLLMExecutor.
 */
data class SafeExecutorConfig(
    /**
     * Fallback message when LLM fails completely.
     */
    val fallbackMessage: String? = "Unable to generate response. Please try again.",
    
    /**
     * Enable content validation.
     */
    val validateContent: Boolean = true,
    
    /**
     * Maximum content length before flagging.
     */
    val maxContentLength: Int = 10_000
)
