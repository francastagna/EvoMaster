package org.evomaster.core.logging

import com.google.inject.Inject
import org.evomaster.core.EMConfig
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.util.concurrent.atomic.AtomicLong
import javax.annotation.PostConstruct
import javax.ws.rs.client.ClientBuilder

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
    
    /**
     * Data class for Datadog log query results
     */
    data class DatadogLogQueryResult(
        val logs: List<Map<String, Any>>,
        val errorCount: Int,
        val averageResponseTime: Double,
        val timeoutCount: Int,
        val coverageGaps: List<String>
    )

    /**
     * Query Datadog Logs API for insights
     * @param timeRangeMinutes How many minutes back to query
     * @return Analyzed results from the logs
     */
    fun queryDatadogLogs(timeRangeMinutes: Int = 5): DatadogLogQueryResult? {
        if (!config.datadogEnabled || config.datadogApiKey.isEmpty() || config.datadogAppKey.isEmpty()) {
            return null
        }

        try {
            val client = ClientBuilder.newClient()
            val now = System.currentTimeMillis()
            val fromTime = now - (timeRangeMinutes * 60 * 1000)
            
            val query = "service:${config.datadogServiceName} @test_id:*"
            val target = client.target("https://api.datadoghq.com/api/v1/logs-queries/list")
                .queryParam("query", query)
                .queryParam("time.from", fromTime)
                .queryParam("time.to", now)
                .queryParam("limit", 1000)
            
            val response = target
                .request("application/json")
                .header("DD-API-KEY", config.datadogApiKey)
                .header("DD-APPLICATION-KEY", config.datadogAppKey)
                .get()

            if (response.status == 200) {
                val responseBody = response.readEntity(Map::class.java) as Map<String, Any>
                return analyzeLogData(responseBody)
            } else {
                log.warn("Failed to query Datadog API: ${response.status}")
                return null
            }
        } catch (e: Exception) {
            log.error("Error querying Datadog API: ${e.message}", e)
            return null
        }
    }

    /**
     * Analyze log data to extract insights for search algorithm
     */
    private fun analyzeLogData(logData: Map<String, Any>): DatadogLogQueryResult {
        val logs = (logData["logs"] as? List<Map<String, Any>>) ?: emptyList()
        
        var errorCount = 0
        var totalResponseTime = 0.0
        var responseTimeCount = 0
        var timeoutCount = 0
        val coverageGaps = mutableSetOf<String>()
        
        for (log in logs) {
            val message = log["message"] as? String ?: ""
            val attributes = log["attributes"] as? Map<String, Any> ?: emptyMap()
            
            // Count errors and exceptions
            if (message.contains("error", ignoreCase = true) || 
                message.contains("exception", ignoreCase = true) ||
                message.contains("failed", ignoreCase = true)) {
                errorCount++
            }
            
            // Count timeouts
            if (message.contains("timeout", ignoreCase = true) ||
                message.contains("timed out", ignoreCase = true)) {
                timeoutCount++
            }
            
            // Extract response times
            attributes["execution_time"]?.toString()?.toLongOrNull()?.let { time ->
                totalResponseTime += time
                responseTimeCount++
            }
            
            // Identify coverage gaps (targets with low fitness values)
            attributes["fitness_value"]?.toString()?.toDoubleOrNull()?.let { fitness ->
                if (fitness < 0.5) {
                    attributes["target_id"]?.toString()?.let { targetId ->
                        coverageGaps.add(targetId)
                    }
                }
            }
        }
        
        val averageResponseTime = if (responseTimeCount > 0) totalResponseTime / responseTimeCount else 0.0
        
        return DatadogLogQueryResult(
            logs = logs,
            errorCount = errorCount,
            averageResponseTime = averageResponseTime,
            timeoutCount = timeoutCount,
            coverageGaps = coverageGaps.toList()
        )
    }
    
    /**
     * Data class for search parameter adjustments
     */
    data class SearchParameterAdjustments(
        val adjustRandomSampling: Boolean,
        val newRandomProbability: Double?,
        val adjustMutationCount: Boolean,
        val newMutationCount: Int?,
        val adjustArchiveLimit: Boolean,
        val newArchiveLimit: Int?,
        val reason: String
    )

    /**
     * Analyze Datadog insights and suggest search parameter adjustments
     */
    fun suggestParameterAdjustments(queryResult: DatadogLogQueryResult): SearchParameterAdjustments {
        val reasons = mutableListOf<String>()
        var adjustRandomSampling = false
        var newRandomProbability: Double? = null
        var adjustMutationCount = false
        var newMutationCount: Int? = null
        var adjustArchiveLimit = false
        var newArchiveLimit: Int? = null
        
        // If many errors, increase random sampling to explore more
        if (queryResult.errorCount > 10) {
            adjustRandomSampling = true
            newRandomProbability = 0.4 // Increase random sampling
            reasons.add("High error count (${queryResult.errorCount}) - increasing exploration")
        }
        
        // If many timeouts, reduce mutation count to create simpler tests
        if (queryResult.timeoutCount > 5) {
            adjustMutationCount = true
            newMutationCount = 2 // Reduce mutations for simpler tests
            reasons.add("High timeout count (${queryResult.timeoutCount}) - simplifying tests")
        }
        
        // If high response times, focus search more (reduce random sampling)
        if (queryResult.averageResponseTime > 5000) {
            adjustRandomSampling = true
            newRandomProbability = 0.1 // Reduce random sampling
            reasons.add("High response times (${queryResult.averageResponseTime}ms) - focusing search")
        }
        
        // If many coverage gaps, increase archive limit to keep more solutions
        if (queryResult.coverageGaps.size > 20) {
            adjustArchiveLimit = true
            newArchiveLimit = 15 // Increase archive size
            reasons.add("Many coverage gaps (${queryResult.coverageGaps.size}) - expanding archive")
        }
        
        return SearchParameterAdjustments(
            adjustRandomSampling = adjustRandomSampling,
            newRandomProbability = newRandomProbability,
            adjustMutationCount = adjustMutationCount,
            newMutationCount = newMutationCount,
            adjustArchiveLimit = adjustArchiveLimit,
            newArchiveLimit = newArchiveLimit,
            reason = reasons.joinToString("; ")
        )
    }
}
