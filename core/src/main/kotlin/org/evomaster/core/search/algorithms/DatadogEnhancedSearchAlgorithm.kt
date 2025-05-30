package org.evomaster.core.search.algorithms

import com.google.inject.Inject
import org.evomaster.core.EMConfig
import org.evomaster.core.logging.DatadogIntegration
import org.evomaster.core.search.Individual
import org.evomaster.core.search.Solution
import org.evomaster.core.search.service.SearchAlgorithm
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import javax.annotation.PostConstruct

/**
 * A search algorithm that uses Datadog logs to enhance the search process.
 * This algorithm implements the same behavior as MIO but periodically queries Datadog for insights
 * and uses that information to guide the search process by dynamically adjusting parameters.
 */
class DatadogEnhancedSearchAlgorithm<T> : SearchAlgorithm<T>() where T : Individual {

    companion object {
        private val log: Logger = LoggerFactory.getLogger(DatadogEnhancedSearchAlgorithm::class.java)
    }

    @Inject
    private lateinit var datadogIntegration: DatadogIntegration
    
    // Parameter tracker for dynamic adjustments
    private val parameterTracker = DatadogParameterTracker()
    
    private var lastDatadogQueryTime: Instant = Instant.now()

    @PostConstruct
    private fun init() {
        if (config.datadogEnabled && config.datadogEnhancedSearch) {
            log.info("Initializing Datadog Enhanced Search Algorithm with dynamic parameter control")
        } else {
            log.warn("Datadog Enhanced Search Algorithm is configured but Datadog integration is not enabled. " +
                    "Set datadogEnabled=true and datadogEnhancedSearch=true to enable enhanced search.")
        }
    }

    override fun getType(): EMConfig.Algorithm {
        return EMConfig.Algorithm.DATADOG_ENHANCED
    }
    
    override fun setupBeforeSearch() {
        // Nothing needs to be done before starting the search
    }

    override fun searchOnce() {
        // Start tracking this test execution in Datadog
        val testId = datadogIntegration.startTestExecution()
        val startTime = System.currentTimeMillis()
        
        try {
            // Check if it's time to query Datadog for insights
            if (shouldQueryDatadog()) {
                adjustSearchBasedOnDatadogInsights()
            }
            
            // Apply any parameter adjustments
            applyParameterAdjustments()
            
            // Implement MIO algorithm logic directly
            val randomP = getAdjustedRandomSamplingProbability()

            if (archive.isEmpty()
                    || sampler.hasSpecialInit()
                    || randomness.nextBoolean(randomP)) {

                val ind = sampler.sample()
                
                ff.calculateCoverage(ind, modifiedSpec = null)?.run {
                    archive.addIfNeeded(this)
                    sampler.feedback(this)
                    if (sampler.isLastSeededIndividual())
                        archive.archiveCoveredStatisticsBySeededTests()
                }
            } else {
                val ei = archive.sampleIndividual()
                val nMutations = getAdjustedMutationCount()
                getMutatator().mutateAndSave(nMutations, ei, archive)
            }
            
            // Log the result to Datadog
            val executionTime = System.currentTimeMillis() - startTime
            val coverage = calculateCoverage()
            datadogIntegration.endTestExecution(testId, true, executionTime, coverage)
            
        } catch (e: Exception) {
            log.error("Error during search iteration: ${e.message}", e)
            val executionTime = System.currentTimeMillis() - startTime
            datadogIntegration.endTestExecution(testId, false, executionTime, 0.0)
        }
    }
    
    /**
     * Get the adjusted random sampling probability or the default from APC
     */
    private fun getAdjustedRandomSamplingProbability(): Double {
        return parameterTracker.getRandomSamplingAdjustment() ?: apc.getProbRandomSampling()
    }
    
    /**
     * Get the adjusted mutation count or the default from APC
     */
    private fun getAdjustedMutationCount(): Int {
        return parameterTracker.getMutationCountAdjustment() ?: apc.getNumberOfMutations()
    }
    
    /**
     * Calculate current coverage percentage
     */
    private fun calculateCoverage(): Double {
        val covered = archive.coveredTargets().size
        val total = covered + archive.notCoveredTargets().size
        return if (total > 0) (covered.toDouble() / total) * 100 else 0.0
    }
    
    /**
     * Apply any parameter adjustments to the APC
     */
    private fun applyParameterAdjustments() {
        // We can't modify the APC directly, but we can use our adjusted values
        // when calling the algorithm methods
        val randomSampling = parameterTracker.getRandomSamplingAdjustment()
        val mutationCount = parameterTracker.getMutationCountAdjustment()
        val archiveLimit = parameterTracker.getArchiveLimitAdjustment()
        
        if (randomSampling != null || mutationCount != null || archiveLimit != null) {
            log.info("Parameter adjustments active: " +
                    "randomSampling=${randomSampling ?: "default"}, " +
                    "mutationCount=${mutationCount ?: "default"}, " +
                    "archiveLimit=${archiveLimit ?: "default"}")
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
    private fun adjustSearchBasedOnDatadogInsights() {
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
            
            // Store parameter adjustments for later use
            if (adjustments.adjustRandomSampling && adjustments.newRandomProbability != null) {
                parameterTracker.adjustRandomSamplingProbability(
                    adjustments.newRandomProbability,
                    "Datadog insight: ${adjustments.reason}"
                )
                
                datadogIntegration.logSearchDecision(
                    "datadog_query_${lastDatadogQueryTime.epochSecond}",
                    "adjust_random_sampling",
                    "Suggested probability: ${adjustments.newRandomProbability}"
                )
            }
            
            if (adjustments.adjustMutationCount && adjustments.newMutationCount != null) {
                parameterTracker.adjustMutationCount(
                    adjustments.newMutationCount,
                    "Datadog insight: ${adjustments.reason}"
                )
                
                datadogIntegration.logSearchDecision(
                    "datadog_query_${lastDatadogQueryTime.epochSecond}",
                    "adjust_mutation_count",
                    "Suggested count: ${adjustments.newMutationCount}"
                )
            }
            
            if (adjustments.adjustArchiveLimit && adjustments.newArchiveLimit != null) {
                parameterTracker.adjustArchiveLimit(
                    adjustments.newArchiveLimit,
                    "Datadog insight: ${adjustments.reason}"
                )
                
                datadogIntegration.logSearchDecision(
                    "datadog_query_${lastDatadogQueryTime.epochSecond}",
                    "adjust_archive_limit",
                    "Suggested limit: ${adjustments.newArchiveLimit}"
                )
            }
            
            // Log the overall adjustment summary
            val summary = parameterTracker.getAdjustmentSummary()
            datadogIntegration.logSearchDecision(
                "datadog_query_${lastDatadogQueryTime.epochSecond}",
                "parameter_analysis_complete",
                "Current adjustments: $summary. Reason: ${adjustments.reason}"
            )
            
        } catch (e: Exception) {
            log.error("Error analyzing Datadog insights: ${e.message}", e)
        }
    }
}
