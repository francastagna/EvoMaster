package org.evomaster.core.search.service

import com.google.inject.Inject
import org.evomaster.core.EMConfig
import org.slf4j.LoggerFactory
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.URI
import java.time.Duration
import com.google.gson.Gson
import com.google.gson.JsonObject

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
            return null
        }
        
        return try {
            val errorRate = getErrorRate(timeRangeMinutes)
            val avgResponseTime = getAvgResponseTime(timeRangeMinutes)
            val p95ResponseTime = getP95ResponseTime(timeRangeMinutes)
            val securitySignalsCount = getSecuritySignalsCount(timeRangeMinutes)
            val criticalFindingsCount = getCriticalFindingsCount(timeRangeMinutes)
            
            DatadogMetrics(errorRate, avgResponseTime, p95ResponseTime, securitySignalsCount, criticalFindingsCount)
        } catch (e: Exception) {
            log.warn("Failed to retrieve Datadog metrics: ${e.message}")
            null
        }
    }
    
    private fun getErrorRate(timeRangeMinutes: Int): Double {
        val query = "sum:trace.servlet.request.errors{service:${config.datadogServiceName}}.as_rate()"
        return queryMetric(query, timeRangeMinutes)
    }
    
    private fun getAvgResponseTime(timeRangeMinutes: Int): Double {
        val query = "avg:trace.servlet.request.duration{service:${config.datadogServiceName}}"
        return queryMetric(query, timeRangeMinutes)
    }
    
    private fun getP95ResponseTime(timeRangeMinutes: Int): Double {
        val query = "p95:trace.servlet.request.duration{service:${config.datadogServiceName}}"
        return queryMetric(query, timeRangeMinutes)
    }
    
    private fun getSecuritySignalsCount(timeRangeMinutes: Int): Int {
        return try {
            val fromTime = System.currentTimeMillis() - (timeRangeMinutes * 60 * 1000)
            val toTime = System.currentTimeMillis()
            
            val url = "${config.datadogApiUrl}/api/v2/security_monitoring/signals"
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$url?filter[query]=service:${config.datadogServiceName}&filter[from]=$fromTime&filter[to]=$toTime"))
                .header("DD-API-KEY", config.datadogApiKey)
                .header("DD-APPLICATION-KEY", config.datadogAppKey)
                .header("Content-Type", "application/json")
                .GET()
                .build()
            
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                val jsonResponse = gson.fromJson(response.body(), JsonObject::class.java)
                jsonResponse.getAsJsonArray("data")?.size() ?: 0
            } else 0
        } catch (e: Exception) {
            log.debug("Failed to get security signals count: ${e.message}")
            0
        }
    }
    
    private fun getCriticalFindingsCount(timeRangeMinutes: Int): Int {
        return try {
            val fromTime = System.currentTimeMillis() - (timeRangeMinutes * 60 * 1000)
            
            val url = "${config.datadogApiUrl}/api/v2/posture_management/findings"
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$url?filter[status]=critical&filter[discovery_timestamp]=>$fromTime"))
                .header("DD-API-KEY", config.datadogApiKey)
                .header("DD-APPLICATION-KEY", config.datadogAppKey)
                .header("Content-Type", "application/json")
                .GET()
                .build()
            
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                val jsonResponse = gson.fromJson(response.body(), JsonObject::class.java)
                jsonResponse.getAsJsonArray("data")?.size() ?: 0
            } else 0
        } catch (e: Exception) {
            log.debug("Failed to get critical findings count: ${e.message}")
            0
        }
    }
    
    private fun queryMetric(query: String, timeRangeMinutes: Int): Double {
        val fromTime = System.currentTimeMillis() / 1000 - (timeRangeMinutes * 60)
        val toTime = System.currentTimeMillis() / 1000
        
        val url = "${config.datadogApiUrl}/api/v2/metrics/query" +
                "?query=${query}&from=${fromTime}&to=${toTime}"
        
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("DD-API-KEY", config.datadogApiKey)
            .header("DD-APPLICATION-KEY", config.datadogAppKey)
            .header("Content-Type", "application/json")
            .GET()
            .build()
        
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() == 200) {
            val jsonResponse = gson.fromJson(response.body(), JsonObject::class.java)
            return parseMetricValue(jsonResponse)
        }
        
        return 0.0
    }
    
    private fun parseMetricValue(jsonResponse: JsonObject): Double {
        return try {
            val data = jsonResponse.getAsJsonObject("data")
            val attributes = data?.getAsJsonObject("attributes")
            val series = attributes?.getAsJsonArray("series")
            if (series != null && series.size() > 0) {
                val firstSeries = series[0].asJsonObject
                val values = firstSeries.getAsJsonArray("values")
                if (values != null && values.size() > 0) {
                    val lastValue = values[values.size() - 1].asJsonArray
                    lastValue[1].asDouble
                } else 0.0
            } else 0.0
        } catch (e: Exception) {
            log.debug("Failed to parse metric value: ${e.message}")
            0.0
        }
    }
}
