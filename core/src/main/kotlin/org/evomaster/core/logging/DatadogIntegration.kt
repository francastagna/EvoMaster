package org.evomaster.core.logging

import com.google.inject.Inject
import org.evomaster.core.EMConfig
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.util.concurrent.atomic.AtomicLong
import javax.annotation.PostConstruct

/**
 * Utility class for Datadog integration with EvoMaster.
 * Handles logging, metrics, and trace correlation for Datadog.
 */
class DatadogIntegration {

    companion object {
        private val log: Logger = LoggerFactory.getLogger(DatadogIntegration::class.java)
        private val searchMetricsLogger: Logger = LoggerFactory.getLogger("search_metrics")
        private val testCounter = AtomicLong(0)

        // MDC keys for log enrichment
        const val TEST_ID = "test_id"
        const val ALGORITHM = "algorithm"
        const val SEARCH_SEED = "search_seed"
        const val TARGET_ID = "target_id"
        const val FITNESS_VALUE = "fitness_value"
        const val INDIVIDUAL_ID = "individual_id"
        const val ITERATION = "iteration"
        const val EXECUTION_TIME = "execution_time"
        const val COVERAGE = "coverage"
    }

    @Inject
    private lateinit var config: EMConfig

    @PostConstruct
    fun init() {
        if (config.datadogEnabled) {
            log.info("Initializing Datadog integration")
            
            // Set global MDC values that will be included in all logs
            MDC.put(ALGORITHM, config.algorithm.toString())
            MDC.put(SEARCH_SEED, config.seed.toString())
            
            // Log initialization event
            searchMetricsLogger.info("Datadog integration initialized")
        }
    }

    /**
     * Start tracking a new test execution
     * @return The test ID for correlation
     */
    fun startTestExecution(): String {
        if (!config.datadogEnabled) return ""
        
        val testId = "${config.algorithm}_${testCounter.incrementAndGet()}"
        MDC.put(TEST_ID, testId)
        searchMetricsLogger.info("Test execution started")
        return testId
    }

    /**
     * End tracking a test execution
     * @param testId The test ID to end tracking for
     * @param success Whether the test was successful
     * @param executionTime The execution time in milliseconds
     * @param coverage The achieved coverage percentage
     */
    fun endTestExecution(testId: String, success: Boolean, executionTime: Long, coverage: Double) {
        if (!config.datadogEnabled) return
        
        MDC.put(TEST_ID, testId)
        MDC.put(EXECUTION_TIME, executionTime.toString())
        MDC.put(COVERAGE, coverage.toString())
        searchMetricsLogger.info("Test execution completed: success=$success")
        MDC.remove(TEST_ID)
        MDC.remove(EXECUTION_TIME)
        MDC.remove(COVERAGE)
    }

    /**
     * Log fitness evaluation for a target
     * @param testId The test ID for correlation
     * @param targetId The target ID being evaluated
     * @param individualId The individual ID being evaluated
     * @param fitnessValue The fitness value achieved
     * @param iteration The current iteration
     */
    fun logFitnessEvaluation(testId: String, targetId: String, individualId: String, fitnessValue: Double, iteration: Long) {
        if (!config.datadogEnabled) return
        
        MDC.put(TEST_ID, testId)
        MDC.put(TARGET_ID, targetId)
        MDC.put(INDIVIDUAL_ID, individualId)
        MDC.put(FITNESS_VALUE, fitnessValue.toString())
        MDC.put(ITERATION, iteration.toString())
        
        searchMetricsLogger.info("Fitness evaluation: target=$targetId, value=$fitnessValue")
        
        MDC.remove(TARGET_ID)
        MDC.remove(INDIVIDUAL_ID)
        MDC.remove(FITNESS_VALUE)
        MDC.remove(ITERATION)
    }

    /**
     * Log search algorithm decision
     * @param testId The test ID for correlation
     * @param decision The decision made by the algorithm
     * @param reason The reason for the decision
     */
    fun logSearchDecision(testId: String, decision: String, reason: String) {
        if (!config.datadogEnabled) return
        
        MDC.put(TEST_ID, testId)
        searchMetricsLogger.info("Search decision: $decision, reason: $reason")
        MDC.remove(TEST_ID)
    }

    /**
     * Clean up MDC context
     */
    fun cleanup() {
        MDC.clear()
    }
}
