package org.evomaster.core.search.service

import org.evomaster.core.EMConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*

class DatadogIntegrationTest {

    private lateinit var datadogIntegration: DatadogIntegration
    private lateinit var config: EMConfig

    @BeforeEach
    fun setup() {
        datadogIntegration = DatadogIntegration()
        config = EMConfig()
        
        // Use reflection to set the config field in DatadogIntegration
        val configField = DatadogIntegration::class.java.getDeclaredField("config")
        configField.isAccessible = true
        configField.set(datadogIntegration, config)
    }

    @Test
    fun testGetSutMetricsWhenDisabled() {
        // When Datadog integration is disabled
        config.enableDatadogIntegration = false
        
        // Then getSutMetrics should return null
        val metrics = datadogIntegration.getSutMetrics()
        assertNull(metrics, "Metrics should be null when Datadog integration is disabled")
    }

    @Test
    fun testGetSutMetricsWhenMissingCredentials() {
        // When Datadog integration is enabled but credentials are missing
        config.enableDatadogIntegration = true
        config.datadogApiKey = ""
        config.datadogAppKey = ""
        
        // Then getSutMetrics should return null
        val metrics = datadogIntegration.getSutMetrics()
        assertNull(metrics, "Metrics should be null when Datadog credentials are missing")
    }
}
