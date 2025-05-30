package org.evomaster.core.search.algorithms

import com.google.inject.Inject
import org.evomaster.core.EMConfig
import org.evomaster.core.search.service.AdaptiveParameterControl
import org.evomaster.core.search.service.SearchTimeController
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Parameter adjustment tracker for Datadog-enhanced search algorithm.
 * Uses composition instead of inheritance since AdaptiveParameterControl is final.
 */
class DatadogParameterTracker {
    
    companion object {
        private val log: Logger = LoggerFactory.getLogger(DatadogParameterTracker::class.java)
    }
    
    // Values that can be dynamically adjusted
    private var randomSamplingAdjustment: Double? = null
    private var mutationCountAdjustment: Int? = null
    private var archiveLimitAdjustment: Int? = null
    
    /**
     * Get the adjusted random sampling probability or null if no adjustment
     */
    fun getRandomSamplingAdjustment(): Double? {
        return randomSamplingAdjustment
    }
    
    /**
     * Get the adjusted mutation count or null if no adjustment
     */
    fun getMutationCountAdjustment(): Int? {
        return mutationCountAdjustment
    }
    
    /**
     * Get the adjusted archive limit or null if no adjustment
     */
    fun getArchiveLimitAdjustment(): Int? {
        return archiveLimitAdjustment
    }
    
    /**
     * Adjust random sampling probability based on insights
     */
    fun adjustRandomSamplingProbability(newProbability: Double, reason: String) {
        log.info("Adjusting random sampling probability to $newProbability. Reason: $reason")
        randomSamplingAdjustment = newProbability.coerceIn(0.0, 1.0)
    }
    
    /**
     * Adjust mutation count based on insights
     */
    fun adjustMutationCount(newCount: Int, reason: String) {
        log.info("Adjusting mutation count to $newCount. Reason: $reason")
        mutationCountAdjustment = newCount.coerceAtLeast(1)
    }
    
    /**
     * Adjust archive limit based on insights
     */
    fun adjustArchiveLimit(newLimit: Int, reason: String) {
        log.info("Adjusting archive limit to $newLimit. Reason: $reason")
        archiveLimitAdjustment = newLimit.coerceAtLeast(1)
    }
    
    /**
     * Reset all dynamic adjustments to use default values
     */
    fun resetToDefaults() {
        log.info("Resetting all parameter adjustments to default values")
        randomSamplingAdjustment = null
        mutationCountAdjustment = null
        archiveLimitAdjustment = null
    }
    
    /**
     * Get summary of current parameter adjustments
     */
    fun getAdjustmentSummary(): String {
        val adjustments = mutableListOf<String>()
        if (randomSamplingAdjustment != null) {
            adjustments.add("Random sampling: ${randomSamplingAdjustment}")
        }
        if (mutationCountAdjustment != null) {
            adjustments.add("Mutations: ${mutationCountAdjustment}")
        }
        if (archiveLimitAdjustment != null) {
            adjustments.add("Archive limit: ${archiveLimitAdjustment}")
        }
        return if (adjustments.isEmpty()) "No active adjustments" else adjustments.joinToString(", ")
    }
}
