# EvoMaster-Datadog Integration

This document describes how to integrate EvoMaster with Datadog for enhanced observability and log-driven search algorithm improvements.

## Overview

The integration enables:
1. Collection of logs, metrics, and traces during EvoMaster test executions
2. Enhanced search algorithms that leverage Datadog logs to improve test generation
3. Visualization of test execution data in Datadog dashboards

## Prerequisites

- EvoMaster installed and configured
- Datadog account with API and Application keys
- Datadog Agent installed on the test environment

## Installation

### 1. Install Datadog Agent

Follow the [official Datadog installation guide](https://docs.datadoghq.com/agent/basic_agent_usage/) for your operating system.

For Linux:
```bash
DD_API_KEY=<YOUR_API_KEY> bash -c "$(curl -L https://raw.githubusercontent.com/DataDog/datadog-agent/master/cmd/agent/install_script.sh)"
```

### 2. Configure Datadog Agent for Log Collection

Edit the Datadog Agent configuration file to enable log collection:

```yaml
# /etc/datadog-agent/datadog.yaml
logs_enabled: true
```

Create a custom log configuration for EvoMaster:

```yaml
# /etc/datadog-agent/conf.d/evomaster.d/conf.yaml
logs:
  - type: file
    path: /path/to/logs/evomaster.log
    service: evomaster
    source: java
    sourcecategory: search
  - type: file
    path: /path/to/logs/evomaster-search-metrics.log
    service: evomaster
    source: java
    sourcecategory: metrics
```

Restart the Datadog Agent:
```bash
sudo systemctl restart datadog-agent
```

## Configuration

### EvoMaster Configuration

Enable Datadog integration by setting the following parameters in your EvoMaster configuration:

```
--datadog_enabled=true
--datadog_api_key=<YOUR_API_KEY>
--datadog_app_key=<YOUR_APP_KEY>
--datadog_service_name=evomaster
```

To enable the enhanced search algorithm that uses Datadog logs:

```
--datadog_enhanced_search=true
--datadog_query_frequency=30
```

## Using the Enhanced Search Algorithm

The Datadog-enhanced search algorithm extends the MIO algorithm to leverage log data for improving test generation. To use it:

1. Enable Datadog integration as described above
2. Set the algorithm parameter to MIO (the enhanced algorithm will be used automatically when Datadog integration is enabled)
3. Run EvoMaster as usual

## How It Works

### Log Collection

The integration uses the following components:
- JSON-formatted logs via Logback's LogstashEncoder
- MDC (Mapped Diagnostic Context) for correlating logs with test executions
- Custom appenders for different log types (general logs and search metrics)

### Search Algorithm Enhancement

The DatadogEnhancedSearchAlgorithm extends the MIO algorithm to:
1. Periodically query Datadog for insights from logs
2. Adjust search parameters based on log analysis
3. Track test execution metrics in Datadog

## Monitoring in Datadog

After running EvoMaster with Datadog integration, you can:

1. View logs in the Datadog Logs Explorer
2. Create dashboards to visualize test execution metrics
3. Set up alerts for specific test conditions

## Troubleshooting

### Common Issues

- **No logs in Datadog**: Verify the Datadog Agent is running and the log paths are correct
- **Authentication errors**: Check your API and Application keys
- **Performance issues**: Adjust the query frequency to reduce API calls

## Limitations

- The enhanced search algorithm requires a running Datadog instance
- API rate limits may apply for frequent queries
- Log analysis capabilities depend on the Datadog plan

## References

- [EvoMaster Documentation](https://evomaster.org/)
- [Datadog Java Logging](https://docs.datadoghq.com/logs/log_collection/java/)
- [Datadog Agent Setup](https://docs.datadoghq.com/agent/)
