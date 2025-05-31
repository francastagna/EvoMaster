package org.evomaster.core.search.algorithms

import com.google.inject.Inject
import com.google.inject.Provider
import org.evomaster.core.EMConfig
import org.evomaster.core.search.Individual
import org.evomaster.core.search.service.SearchAlgorithm
import org.evomaster.core.search.service.DatadogIntegration
import org.slf4j.LoggerFactory

class DatadogEnhancedSearchAlgorithm<T> : SearchAlgorithm<T>() where T : Individual {

    @Inject
    private lateinit var datadogIntegration: DatadogIntegration

    @Inject
    private lateinit var mioAlgorithmProvider: Provider<SearchAlgorithm<T>>

    private var lastMetricsCheck = 0L
    private val log = LoggerFactory.getLogger(DatadogEnhancedSearchAlgorithm::class.java)

    override fun getType(): EMConfig.Algorithm {
        return EMConfig.Algorithm.DATADOG_ENHANCED
    }

    override fun setupBeforeSearch() {
        // The base algorithm will be initialized by the dependency injection system
        // so we don't need to manually set its dependencies
        mioAlgorithmProvider.get().setupBeforeSearch()

        log.info("DatadogEnhancedSearchAlgorithm initialized with MIO algorithm")
    }

    override fun searchOnce() {
        // Check if we should adjust parameters based on Datadog metrics
        val now = System.currentTimeMillis()
        if (now - lastMetricsCheck > config.datadogPollingInterval * 1000) {
            adjustParametersBasedOnMetrics()
            lastMetricsCheck = now
        }

        // Delegate to MIO algorithm
        mioAlgorithmProvider.get().searchOnce()
    }

    private fun adjustParametersBasedOnMetrics() {
        val metrics = datadogIntegration.getSutMetrics() ?: return

        log.debug("Adjusting parameters based on Datadog metrics: $metrics")

        // Adjust parameters based on SUT behavior patterns
        when {
            // High error rate or security issues detected - increase exploration
            metrics.errorRate > 0.1 || metrics.securitySignalsCount > 0 || metrics.criticalFindingsCount > 0 -> {
                // Increase mutation probability to explore more paths
                val currentMutations = config.startNumberOfMutations
                config.startNumberOfMutations = minOf(10, currentMutations + 1)

                // Increase random sampling to find new vulnerabilities
                val currentSampling = config.probOfRandomSampling
                config.probOfRandomSampling = minOf(0.5, currentSampling + 0.1)

                log.info("High error rate (${metrics.errorRate}) or security issues detected. Increased mutations to ${config.startNumberOfMutations}, sampling to ${config.probOfRandomSampling}")
            }

            // High response times - reduce test complexity
            metrics.p95ResponseTime > 5000 -> {
                val currentTestSize = config.maxTestSize
                config.maxTestSize = maxOf(1, currentTestSize - 1)

                log.info("High response time (${metrics.p95ResponseTime}ms). Reduced max test size to ${config.maxTestSize}")
            }

            // Low error rate and fast responses - optimize for coverage
            metrics.errorRate < 0.01 && metrics.p95ResponseTime < 1000 -> {
                // Focus more on systematic exploration
                val currentSampling = config.probOfRandomSampling
                config.probOfRandomSampling = maxOf(0.0, currentSampling - 0.05)

                log.debug("Low error rate (${metrics.errorRate}) and fast responses (${metrics.p95ResponseTime}ms). Reduced random sampling to ${config.probOfRandomSampling}")
            }
        }
    }
}
