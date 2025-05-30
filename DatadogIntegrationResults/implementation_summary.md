# Datadog Integration Implementation Summary

## Overview
This document summarizes the implementation of the Datadog integration with EvoMaster for SUT observability and log-driven search enhancement.

## Implementation Components

### 1. DatadogIntegration Service
The `DatadogIntegration` class is responsible for querying the Datadog API for metrics related to the System Under Test (SUT). It includes methods to retrieve various metrics such as error rates, average response times, and security signals.

```kotlin
data class DatadogMetrics(
    val errorRate: Double,
    val avgResponseTime: Double,
    val p95ResponseTime: Double,
    val securitySignalsCount: Int,
    val criticalFindingsCount: Int
)

class DatadogIntegration @Inject constructor() {
    
    @Inject
    private lateinit var config: EMConfig
    
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    
    private val gson = Gson()
    private val log = LoggerFactory.getLogger(DatadogIntegration::class.java)
    
    fun getSutMetrics(timeRangeMinutes: Int = 15): DatadogMetrics? {
        if (!config.enableDatadogIntegration || config.datadogApiKey.isEmpty() || config.datadogAppKey.isEmpty()) {
            log.debug("Datadog integration is disabled or missing credentials")
            return null
        }
        
        // Query Datadog API for metrics
        // ...
    }
}
```

### 2. DatadogEnhancedSearchAlgorithm
The `DatadogEnhancedSearchAlgorithm` class extends the `SearchAlgorithm` and integrates with the `DatadogIntegration` service to adjust search parameters based on metrics retrieved from Datadog.

```kotlin
class DatadogEnhancedSearchAlgorithm : SearchAlgorithm() {

    @Inject
    private lateinit var mioAlgorithm: MioAlgorithm
    
    @Inject
    private lateinit var datadogIntegration: DatadogIntegration
    
    override fun getType(): EMConfig.Algorithm {
        return EMConfig.Algorithm.DATADOG_ENHANCED
    }
    
    override fun search(): Solution<*> {
        // Initialize search
        
        // Main search loop with periodic Datadog metric checks
        while (!time.shouldStopSearch()) {
            // Get metrics from Datadog
            val metrics = datadogIntegration.getSutMetrics()
            
            // Adjust parameters based on metrics
            if (metrics != null) {
                adjustParametersBasedOnMetrics(metrics)
            }
            
            // Delegate to MIO algorithm for actual search
            mioAlgorithm.searchOneStep()
        }
        
        return archive.extractSolution()
    }
    
    private fun adjustParametersBasedOnMetrics(metrics: DatadogMetrics) {
        // Adjust parameters based on metrics
        // ...
    }
}
```

### 3. SUT Instrumentation
The `ExternalSutController` and `ExternalEvoMasterController` classes were modified to support Datadog agent configuration.

```java
// ExternalSutController.java
public class ExternalSutController {
    
    private volatile String datadogAgentLocation = "";
    private volatile String datadogServiceName = "";
    
    public final ExternalSutController setDatadog(String datadogAgentLocation, String datadogServiceName){
        this.datadogAgentLocation = datadogAgentLocation;
        this.datadogServiceName = datadogServiceName;
        return this;
    }
    
    private boolean isUsingDatadog(){
        return !datadogAgentLocation.isEmpty() && !datadogServiceName.isEmpty();
    }
    
    // Add Datadog agent to JVM parameters
    if(isUsingDatadog()){
        command.add("-javaagent:" + datadogAgentLocation);
        command.add("-Ddd.service=" + datadogServiceName);
        command.add("-Ddd.agent.host=localhost");
        command.add("-Ddd.agent.port=8126");
        command.add("-Ddd.trace.enabled=true");
        command.add("-Ddd.logs.injection=true");
    }
}
```

```java
// ExternalEvoMasterController.java
public class ExternalEvoMasterController extends ExternalSutController {
    
    public ExternalEvoMasterController(){
        if (Boolean.parseBoolean(System.getProperty("evomaster.datadog.enabled", "false"))) {
            String serviceName = System.getProperty("evomaster.datadog.service.name", "features-service");
            String agentPath = System.getProperty("evomaster.datadog.agent.path", 
                    "client-java/controller/src/main/resources/DatadogAgent/dd-java-agent.jar");
            setDatadog(agentPath, serviceName);
        }
    }
}
```

### 4. Configuration Parameters
The `EMConfig` class was extended with Datadog-specific parameters with `@Cfg` annotations.

```kotlin
// EMConfig.kt
enum class Algorithm {
    // ...
    DATADOG_ENHANCED
}

@Cfg("Enable Datadog integration for SUT observability")
var enableDatadogIntegration = false

@Cfg("Datadog API key for authentication")
var datadogApiKey = ""

@Cfg("Datadog application key for authentication")
var datadogAppKey = ""

@Cfg("Datadog API base URL")
var datadogApiUrl = "https://us5.datadoghq.com"

@Cfg("Interval in seconds for polling Datadog metrics")
var datadogPollingInterval = 60

@Cfg("Service name for identifying SUT in Datadog")
var datadogServiceName = "evomaster-sut"
```

### 5. Dependency Injection
The `DatadogModule` class registers Datadog integration services with the dependency injection system.

```kotlin
class DatadogModule : AbstractModule() {

    override fun configure() {
        // Register DatadogIntegration as a singleton
        bind(DatadogIntegration::class.java)
            .asEagerSingleton()
    }
}
```

## Parameter Adjustment Logic
The algorithm adjusts search parameters based on three main scenarios:

1. **High Error Rate or Security Issues**
   - Condition: `metrics.errorRate > 0.1 || metrics.securitySignalsCount > 0`
   - Action: Increase exploration by raising mutation probability and random sampling
   - Parameters: `startNumberOfMutations` (max 10), `probOfRandomSampling` (max 0.5)
   - Purpose: Find potential vulnerabilities by exploring more diverse test cases

2. **Slow Response Times**
   - Condition: `metrics.p95ResponseTime > 5000`
   - Action: Reduce test complexity to prevent timeouts
   - Parameter: `maxTestSize` (min 1)
   - Purpose: Prevent timeouts while still testing core functionality

3. **Low Error Rate with Fast Responses**
   - Condition: `metrics.errorRate < 0.01 && metrics.p95ResponseTime < 1000`
   - Action: Focus on systematic coverage rather than random exploration
   - Parameter: `probOfRandomSampling` (min 0.0)
   - Purpose: Methodically explore the API surface when the SUT is performing well

## Feedback Loop
The integration creates a feedback loop where:
1. SUT is instrumented with Datadog agent during startup
2. Agent collects metrics and sends them to Datadog
3. DatadogIntegration periodically queries Datadog API for metrics
4. DatadogEnhancedSearchAlgorithm adjusts search parameters based on metrics
5. Search algorithm adapts to SUT behavior patterns
