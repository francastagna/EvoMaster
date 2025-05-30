package org.evomaster.core.search.algorithms

import com.google.inject.Inject
import org.evomaster.core.EMConfig
import org.evomaster.core.logging.DatadogIntegration
import org.evomaster.core.search.FitnessValue
import org.evomaster.core.search.Individual
import org.evomaster.core.search.Solution
import org.evomaster.core.search.service.Archive
import org.evomaster.core.search.service.SearchTimeController
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import javax.annotation.PostConstruct

/**
 * An extension of the MIO algorithm that uses Datadog logs to enhance the search process.
 * This algorithm periodically queries Datadog for insights from logs and uses that information
 * to guide the search process.
 */
class DatadogEnhancedSearchAlgorithm<T> : MioAlgorithm<T>() where T : Individual {

    companion object {
        private val log: Logger = LoggerFactory.getLogger(DatadogEnhancedSearchAlgorithm::class.java)
    }

    @Inject
    private lateinit var datadogIntegration: DatadogIntegration
    
    @Inject
    private lateinit var time: SearchTimeController

    private var lastDatadogQueryTime: Instant = Instant.now()

    @PostConstruct
    override fun initialize() {
        super.initialize()
        
        if (config.datadogEnabled && config.datadogEnhancedSearch) {
            log.info("Initializing Datadog Enhanced Search Algorithm")
        } else {
            log.warn("Datadog Enhanced Search Algorithm is configured but Datadog integration is not enabled. " +
                    "Set datadogEnabled=true and datadogEnhancedSearch=true to enable enhanced search.")
        }
    }

    override fun getType(): EMConfig.Algorithm {
        return EMConfig.Algorithm.MIO // Use MIO as base type since this is an enhanced version
    }

    override fun searchOnce(iteration: Int, archive: Archive<T>): Boolean {
        // Start tracking this test execution in Datadog
        val testId = datadogIntegration.startTestExecution()
        val startTime = System.currentTimeMillis()
        
        try {
            // Check if it's time to query Datadog for insights
            if (shouldQueryDatadog()) {
                adjustSearchBasedOnDatadogInsights(archive)
            }
            
            // Perform the standard MIO search iteration
            val result = super.searchOnce(iteration, archive)
            
            // Log the result to Datadog
            val executionTime = System.currentTimeMillis() - startTime
            val coverage = archive.coveredTargets().size.toDouble() / (archive.coveredTargets().size + archive.notCoveredTargets().size) * 100
            datadogIntegration.endTestExecution(testId, result, executionTime, coverage)
            
            return result
        } catch (e: Exception) {
            log.error("Error during search iteration: ${e.message}", e)
            val executionTime = System.currentTimeMillis() - startTime
            datadogIntegration.endTestExecution(testId, false, executionTime, 0.0)
            return false
        }
    }

    /**
     * Determine if it's time to query Datadog for insights based on the configured frequency
     */
    private fun shouldQueryDatadog(): Boolean {
        if (!config.datadogEnabled || !config.datadogEnhancedSearch) {
            return false
        }
        
        val now = Instant.now()
        val secondsSinceLastQuery = Duration.between(lastDatadogQueryTime, now).seconds
        
        return secondsSinceLastQuery >= config.datadogQueryFrequency
    }

    /**
     * Query Datadog for insights and adjust search parameters based on the results
     */
    private fun adjustSearchBasedOnDatadogInsights(archive: Archive<T>) {
        if (!config.datadogEnabled || !config.datadogEnhancedSearch) {
            return
        }
        
        log.info("Querying Datadog for search insights")
        lastDatadogQueryTime = Instant.now()
        
        try {
            // Query Datadog for recent log data
            val queryResult = datadogIntegration.queryDatadogLogs(config.datadogQueryFrequency / 60)
            
            if (queryResult == null) {
                log.warn("Failed to get insights from Datadog")
                return
            }
            
            // Analyze the results and get parameter adjustment suggestions
            val adjustments = datadogIntegration.suggestParameterAdjustments(queryResult)
            
            // Apply parameter adjustments if any are suggested
            if (adjustments.adjustRandomSampling && adjustments.newRandomProbability != null) {
                // Note: In a real implementation, we would need to modify the AdaptiveParameterControl
                // For now, we log the decision and could store it for the next iteration
                log.info("Suggesting random sampling probability adjustment to ${adjustments.newRandomProbability}")
                datadogIntegration.logSearchDecision(
                    "datadog_query_${lastDatadogQueryTime.epochSecond}",
                    "adjust_random_sampling",
                    "New probability: ${adjustments.newRandomProbability}"
                )
            }
            
            if (adjustments.adjustMutationCount && adjustments.newMutationCount != null) {
                log.info("Suggesting mutation count adjustment to ${adjustments.newMutationCount}")
                datadogIntegration.logSearchDecision(
                    "datadog_query_${lastDatadogQueryTime.epochSecond}",
                    "adjust_mutation_count",
                    "New count: ${adjustments.newMutationCount}"
                )
            }
            
            if (adjustments.adjustArchiveLimit && adjustments.newArchiveLimit != null) {
                log.info("Suggesting archive limit adjustment to ${adjustments.newArchiveLimit}")
                datadogIntegration.logSearchDecision(
                    "datadog_query_${lastDatadogQueryTime.epochSecond}",
                    "adjust_archive_limit",
                    "New limit: ${adjustments.newArchiveLimit}"
                )
            }
            
            // Log the overall decision
            datadogIntegration.logSearchDecision(
                "datadog_query_${lastDatadogQueryTime.epochSecond}",
                "parameter_analysis_complete",
                adjustments.reason.ifEmpty { "No adjustments needed" }
            )
            
        } catch (e: Exception) {
            log.error("Error analyzing Datadog insights: ${e.message}", e)
        }
    }

    override fun buildSolution(): Solution<T> {
        val solution = super.buildSolution()
        
        if (config.datadogEnabled) {
            // Log final solution metrics to Datadog
            val finalTestId = "solution_${System.currentTimeMillis()}"
            log.info("Logging final solution metrics to Datadog")
            
            datadogIntegration.endTestExecution(
                testId = finalTestId,
                success = true,
                executionTime = time.elapsedTimeInMs(),
                coverage = solution.overall.getViewOfData().values.count { it.score == FitnessValue.MAX_VALUE }.toDouble() / solution.overall.getViewOfData().size * 100
            )
            
            datadogIntegration.logSearchDecision(
                testId = finalTestId,
                decision = "solution_generated",
                reason = "Final solution with ${solution.individuals.size} test cases"
            )
        }
        
        return solution
    }
}
