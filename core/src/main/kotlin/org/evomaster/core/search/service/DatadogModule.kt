package org.evomaster.core.search.service

import com.google.inject.AbstractModule

/**
 * Module for registering Datadog integration services with the dependency injection system.
 */
class DatadogModule : AbstractModule() {

    override fun configure() {
        // Register DatadogIntegration as a singleton
        bind(DatadogIntegration::class.java)
            .asEagerSingleton()
    }
}
