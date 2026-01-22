package dev.llmbridge.reliability

/**
 * Safe generation result wrapper.
 * 
 * Provides a layer of safety for LLM outputs, including:
 * - Content validation
 * - Error recovery hints
 * - Confidence indicators
 * 
 * This is the core of "不会被 LLM 害死" philosophy.
 */
sealed class SafeGenerationResult<out T> {
    
    /**
     * Successfully generated and validated content.
     * @property content The validated output
     * @property confidence Confidence score (0.0-1.0) if available
     * @property warnings Any non-fatal issues detected
     */
    data class Success<T>(
        val content: T,
        val confidence: Float? = null,
        val warnings: List<String> = emptyList()
    ) : SafeGenerationResult<T>()
    
    /**
     * Generation failed but is recoverable.
     * @property message Error description
     * @property retryable Whether retry might help
     * @property fallback Optional fallback content
     */
    data class Failure(
        val message: String,
        val retryable: Boolean = true,
        val fallback: String? = null
    ) : SafeGenerationResult<Nothing>()
    
    /**
     * Content was generated but flagged as potentially problematic.
     * @property content The original content
     * @property reason Why it was flagged
     * @property severity How serious the issue is
     */
    data class Flagged<T>(
        val content: T,
        val reason: String,
        val severity: Severity = Severity.MEDIUM
    ) : SafeGenerationResult<T>()
    
    enum class Severity {
        LOW,    // Minor issue, can proceed
        MEDIUM, // Needs review
        HIGH    // Should not be shown to user
    }
    
    /**
     * Map the success content to a new type.
     */
    inline fun <R> map(transform: (T) -> R): SafeGenerationResult<R> {
        return when (this) {
            is Success -> Success(transform(content), confidence, warnings)
            is Failure -> this
            is Flagged -> Flagged(transform(content), reason, severity)
        }
    }
    
    /**
     * Get content or null.
     */
    fun getOrNull(): T? = when (this) {
        is Success -> content
        is Flagged -> content
        is Failure -> null
    }
    
    /**
     * Get content or default value.
     */
    fun getOrDefault(default: @UnsafeVariance T): T = getOrNull() ?: default
}
