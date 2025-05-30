package org.evomaster.core.search.algorithms

import com.google.inject.Inject
import org.evomaster.core.EMConfig
import org.evomaster.core.search.service.AdaptiveParameterControl
import org.evomaster.core.search.service.SearchTimeController
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Adaptive parameter control that can be dynamically adjusted based on Datadog insights
 */
class DatadogAdaptiveParameterControl : AdaptiveParameterControl() {
    
    companion object {
        private val log: Logger = LoggerFactory.getLogger(DatadogAdaptiveParameterControl::class.java)
    }
    
    // Override values that can be dynamically adjusted
    private var dynamicRandomProbability: Double? = null
    private var dynamicMutationCount: Int? = null
    private var dynamicArchiveLimit: Int? = null
    
    override fun getProbRandomSampling(): Double {
        return dynamicRandomProbability ?: super.getProbRandomSampling()
    }
    
    override fun getNumberOfMutations(): Int {
        return dynamicMutationCount ?: super.getNumberOfMutations()
    }
    
    override fun getArchiveTargetLimit(): Int {
        return dynamicArchiveLimit ?: super.getArchiveTargetLimit()
    }
    
    /**
     * Adjust random sampling probability based on insights
     */
    fun adjustRandomSamplingProbability(newProbability: Double, reason: String) {
        log.info("Adjusting random sampling probability from ${getProbRandomSampling()} to $newProbability. Reason: $reason")
        dynamicRandomProbability = newProbability.coerceIn(0.0, 1.0)
    }
    
    /**
     * Adjust mutation count based on insights
     */
    fun adjustMutationCount(newCount: Int, reason: String) {
        log.info("Adjusting mutation count from ${getNumberOfMutations()} to $newCount. Reason: $reason")
        dynamicMutationCount = newCount.coerceAtLeast(1)
    }
    
    /**
     * Adjust archive limit based on insights
     */
    fun adjustArchiveLimit(newLimit: Int, reason: String) {
        log.info("Adjusting archive limit from ${getArchiveTargetLimit()} to $newLimit. Reason: $reason")
        dynamicArchiveLimit = newLimit.coerceAtLeast(1)
    }
    
    /**
     * Reset all dynamic adjustments to use default values
     */
    fun resetToDefaults() {
        log.info("Resetting all parameter adjustments to default values")
        dynamicRandomProbability = null
        dynamicMutationCount = null
        dynamicArchiveLimit = null
    }
    
    /**
     * Get summary of current parameter adjustments
     */
    fun getAdjustmentSummary(): String {
        val adjustments = mutableListOf<String>()
        if (dynamicRandomProbability != null) {
            adjustments.add("Random sampling: ${dynamicRandomProbability}")
        }
        if (dynamicMutationCount != null) {
            adjustments.add("Mutations: ${dynamicMutationCount}")
        }
        if (dynamicArchiveLimit != null) {
            adjustments.add("Archive limit: ${dynamicArchiveLimit}")
        }
        return if (adjustments.isEmpty()) "No active adjustments" else adjustments.joinToString(", ")
    }
}
