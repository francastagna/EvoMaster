# Datadog Integration Architecture

## Overview
The integration between EvoMaster and Datadog creates a feedback loop where the System Under Test (SUT) behavior is monitored through Datadog, and EvoMaster's search parameters are dynamically adjusted based on the observed metrics.

## Components

### 1. SUT Instrumentation
- **ExternalSutController**: Modified to support Datadog agent configuration
- **ExternalEvoMasterController**: Extended to initialize Datadog agent with system properties
- **Datadog Agent**: Java agent that collects metrics from the SUT

### 2. Datadog API Integration
- **DatadogIntegration**: Service class that queries Datadog API for SUT metrics
- **DatadogModule**: Guice module for registering Datadog services
- **EMConfig**: Configuration parameters for Datadog integration

### 3. Search Algorithm Enhancement
- **DatadogEnhancedSearchAlgorithm**: Extends SearchAlgorithm using composition pattern with MioAlgorithm
- **Parameter Adjustment Logic**: Dynamically modifies search parameters based on SUT behavior

## Feedback Loop
1. SUT is instrumented with Datadog agent during startup
2. Agent collects metrics and sends them to Datadog
3. DatadogIntegration queries Datadog API for metrics
4. DatadogEnhancedSearchAlgorithm adjusts search parameters based on metrics:
   - High error rates → Increase exploration (startNumberOfMutations, probOfRandomSampling)
   - Slow response times → Reduce test complexity (maxTestSize)
   - Low error rates with fast responses → Focus on systematic coverage

## Configuration Parameters
- `enableDatadogIntegration`: Enable/disable Datadog integration
- `datadogApiKey`: Datadog API key for authentication (should be stored securely)
- `datadogAppKey`: Datadog application key for authentication (should be stored securely)
- `datadogApiUrl`: Datadog API base URL (e.g., https://us5.datadoghq.com)
- `datadogPollingInterval`: Interval in seconds for polling Datadog metrics
- `datadogServiceName`: Service name for identifying SUT in Datadog

## API Endpoints Used
- `/api/v2/metrics/query`: For querying error rates and response times
- `/api/v2/security_monitoring/signals`: For retrieving security signals
- `/api/v2/posture_management/findings`: For retrieving critical findings
