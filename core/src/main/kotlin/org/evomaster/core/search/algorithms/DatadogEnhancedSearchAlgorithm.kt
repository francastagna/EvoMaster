package org.evomaster.core.search.algorithms

import com.google.inject.Inject
import org.evomaster.core.EMConfig
import org.evomaster.core.logging.DatadogIntegration
import org.evomaster.core.search.FitnessValue
import org.evomaster.core.search.Individual
import org.evomaster.core.search.Solution
import org.evomaster.core.search.service.AdaptiveParameterControl
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
 * to guide the search process by dynamically adjusting search parameters.
 */
class DatadogEnhancedSearchAlgorithm<T> : MioAlgorithm<T>() where T : Individual {

    companion object {
        private val log: Logger = LoggerFactory.getLogger(DatadogEnhancedSearchAlgorithm::class.java)
    }

    @Inject
    private lateinit var datadogIntegration: DatadogIntegration
    
    @Inject
    private lateinit var time: SearchTimeController
    
    // Custom parameter control that allows dynamic adjustments
    private val datadogParameterControl = DatadogAdaptiveParameterControl()

    private var lastDatadogQueryTime: Instant = Instant.now()

    @PostConstruct
    override fun initialize() {
        super.initialize()
        
        // Initialize the custom parameter control with the same dependencies
        datadogParameterControl.config = config
        datadogParameterControl.time = time
        
        if (config.datadogEnabled && config.datadogEnhancedSearch) {
            log.info("Initializing Datadog Enhanced Search Algorithm with dynamic parameter control")
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
            
            // Use the custom parameter control for this iteration
            val apc = datadogParameterControl
            
            // Perform MIO search iteration with dynamic parameters
            val individual = if (randomness.nextDouble() < apc.getProbRandomSampling()) {
                sampler.sample()
            } else {
                if (apc.useFocusedSearch()) {
                    sampler.sampleAtRandom()
                } else {
                    archive.sampleAtRandom()
                }
            }
            
            mutator.mutateAndSave(apc.getNumberOfMutations(), individual, archive)
            val done = evaluateFitness(individual)
            
            archive.addIfNeeded(individual, apc.getArchiveTargetLimit())
            
            // Log the result to Datadog
            val executionTime = System.currentTimeMillis() - startTime
            val coverage = archive.coveredTargets().size.toDouble() / 
                          (archive.coveredTargets().size + archive.notCoveredTargets().size) * 100
            datadogIntegration.endTestExecution(testId, done, executionTime, coverage)
            
            return done
            
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
            
            // Apply parameter adjustments directly to the search algorithm
            if (adjustments.adjustRandomSampling && adjustments.newRandomProbability != null) {
                datadogParameterControl.adjustRandomSamplingProbability(
                    adjustments.newRandomProbability,
                    "Datadog insight: ${adjustments.reason}"
                )
                
                datadogIntegration.logSearchDecision(
                    "datadog_query_${lastDatadogQueryTime.epochSecond}",
                    "adjust_random_sampling",
                    "Applied probability: ${adjustments.newRandomProbability}"
                )
            }
            
            if (adjustments.adjustMutationCount && adjustments.newMutationCount != null) {
                datadogParameterControl.adjustMutationCount(
                    adjustments.newMutationCount,
                    "Datadog insight: ${adjustments.reason}"
                )
                
                datadogIntegration.logSearchDecision(
                    "datadog_query_${lastDatadogQueryTime.epochSecond}",
                    "adjust_mutation_count",
                    "Applied count: ${adjustments.newMutationCount}"
                )
            }
            
            if (adjustments.adjustArchiveLimit && adjustments.newArchiveLimit != null) {
                datadogParameterControl.adjustArchiveLimit(
                    adjustments.newArchiveLimit,
                    "Datadog insight: ${adjustments.reason}"
                )
                
                datadogIntegration.logSearchDecision(
                    "datadog_query_${lastDatadogQueryTime.epochSecond}",
                    "adjust_archive_limit",
                    "Applied limit: ${adjustments.newArchiveLimit}"
                )
            }
            
            // Log the overall adjustment summary
            val summary = datadogParameterControl.getAdjustmentSummary()
            datadogIntegration.logSearchDecision(
                "datadog_query_${lastDatadogQueryTime.epochSecond}",
                "parameter_analysis_complete",
                "Current adjustments: $summary. Reason: ${adjustments.reason}"
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
    
    /**
     * Override to use the custom parameter control that allows dynamic adjustments
     */
    override fun getAdaptiveParameterControl(): AdaptiveParameterControl {
        return datadogParameterControl
    }
}
