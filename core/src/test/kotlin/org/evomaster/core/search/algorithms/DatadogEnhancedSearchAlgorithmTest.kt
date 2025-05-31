package org.evomaster.core.search.algorithms

import com.google.inject.Provider
import org.evomaster.core.EMConfig
import org.evomaster.core.search.Individual
import org.evomaster.core.search.action.ActionComponent
import org.evomaster.core.search.gene.Gene
import org.evomaster.core.search.Individual.GeneFilter
import org.evomaster.core.search.service.DatadogIntegration
import org.evomaster.core.search.service.FitnessFunction
import org.evomaster.core.search.service.Randomness
import org.evomaster.core.search.service.SearchAlgorithm
import org.evomaster.core.search.service.DatadogMetrics
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.junit.jupiter.api.Assertions.assertEquals

class DatadogEnhancedSearchAlgorithmTest {

    private lateinit var algorithm: DatadogEnhancedSearchAlgorithm<TestIndividual>
    private lateinit var datadogIntegration: DatadogIntegration
    private lateinit var mioAlgorithmProvider: Provider<SearchAlgorithm<TestIndividual>>
    private lateinit var mioAlgorithm: FakeSearchAlgorithm
    private lateinit var config: EMConfig

    @BeforeEach
    fun setup() {
        algorithm = DatadogEnhancedSearchAlgorithm()
        datadogIntegration = DatadogIntegration()
        mioAlgorithm = FakeSearchAlgorithm()
        mioAlgorithmProvider = Provider { mioAlgorithm }
        config = EMConfig()

        // Use reflection to set private fields
        val datadogField = DatadogEnhancedSearchAlgorithm::class.java.getDeclaredField("datadogIntegration")
        datadogField.isAccessible = true
        datadogField.set(algorithm, datadogIntegration)

        val providerField = DatadogEnhancedSearchAlgorithm::class.java.getDeclaredField("mioAlgorithmProvider")
        providerField.isAccessible = true
        providerField.set(algorithm, mioAlgorithmProvider)

        val configField = DatadogEnhancedSearchAlgorithm::class.java.superclass.getDeclaredField("config")
        configField.isAccessible = true
        configField.set(algorithm, config)

        // Set config for datadogIntegration as well
        val datadogConfigField = DatadogIntegration::class.java.getDeclaredField("config")
        datadogConfigField.isAccessible = true
        datadogConfigField.set(datadogIntegration, config)
    }

    @Test
    fun testSetupBeforeSearch() {
        // When setupBeforeSearch is called
        algorithm.setupBeforeSearch()

        // Then it should delegate to the base algorithm
        assert(mioAlgorithm.setupBeforeSearchCalled) { "setupBeforeSearch was not called on the base algorithm" }
    }

    @Test
    fun testSearchOnce() {
        // When searchOnce is called
        algorithm.searchOnce()

        // Then it should delegate to the base algorithm
        assert(mioAlgorithm.searchOnceCalled) { "searchOnce was not called on the base algorithm" }
    }

    // Simple test individual class for testing
    class TestIndividual(
        children: MutableList<ActionComponent> = mutableListOf()
    ) : Individual(children = children) {
        override fun size(): Int = 0
        override fun seeGenes(filter: GeneFilter): List<Gene> = listOf()
        override fun repairInitializationActions(randomness: Randomness) { /* no-op for test */ }
        override fun verifyInitializationActions(): Boolean = true
    }

    // Minimal fake implementation of SearchAlgorithm
    class FakeSearchAlgorithm : SearchAlgorithm<TestIndividual>() {
        var setupBeforeSearchCalled = false
        var searchOnceCalled = false
        override fun setupBeforeSearch() { setupBeforeSearchCalled = true }
        override fun searchOnce() { searchOnceCalled = true }
        override fun getType(): EMConfig.Algorithm = EMConfig.Algorithm.MIO
    }

    // Helper to inject a fake DatadogIntegration that returns the given metrics
    class FakeDatadogIntegration(private val fakeMetrics: DatadogMetrics?) : DatadogIntegration() {
        override fun getSutMetrics(timeRangeMinutes: Int): DatadogMetrics? = fakeMetrics
    }
    fun injectFakeMetrics(algorithm: DatadogEnhancedSearchAlgorithm<TestIndividual>, metrics: DatadogMetrics?) {
        val fakeIntegration = FakeDatadogIntegration(metrics)
        val configField = DatadogIntegration::class.java.getDeclaredField("config")
        configField.isAccessible = true
        configField.set(fakeIntegration, config)
        val datadogField = DatadogEnhancedSearchAlgorithm::class.java.getDeclaredField("datadogIntegration")
        datadogField.isAccessible = true
        datadogField.set(algorithm, fakeIntegration)
    }

    @Test
    fun testAdjustParameters_HighErrorRateAndSecurity() {
        injectFakeMetrics(algorithm, DatadogMetrics(
            errorRate = 0.2,
            avgResponseTime = 0.0,
            p95ResponseTime = 1000.0,
            securitySignalsCount = 1,
            criticalFindingsCount = 1
        ))
        config.startNumberOfMutations = 2
        config.probOfRandomSampling = 0.1
        algorithm.searchOnce()
        assert(config.startNumberOfMutations == 3) { "Mutations should increase to 3" }
        assert(config.probOfRandomSampling == 0.2) { "Sampling should increase to 0.2" }
    }

    @Test
    fun testAdjustParameters_HighResponseTime() {
        injectFakeMetrics(algorithm, DatadogMetrics(
            errorRate = 0.0,
            avgResponseTime = 0.0,
            p95ResponseTime = 6000.0,
            securitySignalsCount = 0,
            criticalFindingsCount = 0
        ))
        config.maxTestSize = 5
        algorithm.searchOnce()
        assert(config.maxTestSize == 4) { "Max test size should decrease to 4" }
    }

    @Test
    fun testAdjustParameters_LowErrorRateAndFastResponse() {
        injectFakeMetrics(algorithm, DatadogMetrics(
            errorRate = 0.0,
            avgResponseTime = 0.0,
            p95ResponseTime = 500.0,
            securitySignalsCount = 0,
            criticalFindingsCount = 0
        ))
        config.probOfRandomSampling = 0.2
        algorithm.searchOnce()
        println("Actual probOfRandomSampling: ${config.probOfRandomSampling}")
        assertEquals(0.15, config.probOfRandomSampling, 1e-6, "Sampling should decrease to 0.15")
    }

    @Test
    fun testAdjustParameters_NoMetrics() {
        injectFakeMetrics(algorithm, null)
        config.startNumberOfMutations = 2
        algorithm.searchOnce()
        assert(config.startNumberOfMutations == 2) { "Config should not change if no metrics" }
    }

    @Test
    fun testAdjustParameters_PollingIntervalNotElapsed() {
        injectFakeMetrics(algorithm, DatadogMetrics(
            errorRate = 0.2,
            avgResponseTime = 0.0,
            p95ResponseTime = 1000.0,
            securitySignalsCount = 1,
            criticalFindingsCount = 1
        ))
        config.startNumberOfMutations = 2
        config.datadogPollingInterval = 1000 // seconds
        // Set lastMetricsCheck to now so interval hasn't elapsed
        val lastMetricsCheckField = DatadogEnhancedSearchAlgorithm::class.java.getDeclaredField("lastMetricsCheck")
        lastMetricsCheckField.isAccessible = true
        lastMetricsCheckField.setLong(algorithm, System.currentTimeMillis())
        algorithm.searchOnce()
        assert(config.startNumberOfMutations == 2) { "Config should not change if polling interval not elapsed" }
    }
}
