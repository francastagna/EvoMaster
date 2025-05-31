package org.evomaster.core.search.service

import org.evomaster.core.EMConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.util.concurrent.CompletableFuture
import java.net.http.HttpHeaders

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

    @Test
    fun testGetSutMetricsHappyPath() {
        // Enable integration and set credentials
        config.enableDatadogIntegration = true
        config.datadogApiKey = "apiKey"
        config.datadogAppKey = "appKey"
        config.datadogApiUrl = "http://fake.datadog.api"
        config.datadogServiceName = "test-service"

        // Prepare canned JSON responses
        val metricJson = JsonObject().apply {
            add("data", JsonObject().apply {
                add("attributes", JsonObject().apply {
                    add("series", com.google.gson.JsonArray().apply {
                        add(JsonObject().apply {
                            add("values", com.google.gson.JsonArray().apply {
                                add(com.google.gson.JsonArray().apply {
                                    add(0)
                                    add(42.0)
                                })
                            })
                        })
                    })
                })
            })
        }
        val countJson = JsonObject().apply {
            add("data", com.google.gson.JsonArray().apply {
                add(JsonObject())
                add(JsonObject())
            })
        }
        val gson = Gson()
        // Fake HttpResponse implementation
        class FakeHttpResponse(private val body: String) : HttpResponse<String> {
            override fun statusCode() = 200
            override fun body() = body
            override fun headers() = HttpHeaders.of(mapOf(), { _: String?, _: String? -> true })
            override fun request(): HttpRequest? = null
            override fun previousResponse(): java.util.Optional<HttpResponse<String>> = java.util.Optional.empty()
            override fun sslSession(): java.util.Optional<javax.net.ssl.SSLSession> = java.util.Optional.empty()
            override fun uri(): java.net.URI? = null
            override fun version(): HttpClient.Version? = null
        }
        // Fake HttpClient implementation
        class FakeHttpClient : HttpClient() {
            override fun <T : Any?> send(request: HttpRequest?, responseBodyHandler: HttpResponse.BodyHandler<T>?): HttpResponse<T> {
                val uri = request!!.uri().toString()
                println("FakeHttpClient: URI=$uri")
                val body = when {
                    uri.contains("/metrics/query") -> gson.toJson(metricJson)
                    uri.contains("/security_monitoring/signals") -> gson.toJson(countJson)
                    uri.contains("/posture_management/findings") -> gson.toJson(countJson)
                    else -> throw AssertionError("FakeHttpClient: Unexpected URI=$uri")
                }
                println("FakeHttpClient: URI=$uri, body=$body")
                @Suppress("UNCHECKED_CAST")
                return FakeHttpResponse(body) as HttpResponse<T>
            }
            override fun <T : Any?> sendAsync(request: HttpRequest?, responseBodyHandler: HttpResponse.BodyHandler<T>?): CompletableFuture<HttpResponse<T>> {
                throw UnsupportedOperationException()
            }
            override fun <T : Any?> sendAsync(request: HttpRequest?, responseBodyHandler: HttpResponse.BodyHandler<T>?, pushPromiseHandler: HttpResponse.PushPromiseHandler<T>?): CompletableFuture<HttpResponse<T>> {
                throw UnsupportedOperationException()
            }
            override fun followRedirects(): Redirect? = null
            override fun connectTimeout(): java.util.Optional<java.time.Duration> = java.util.Optional.empty()
            override fun cookieHandler(): java.util.Optional<java.net.CookieHandler> = java.util.Optional.empty()
            override fun sslContext(): javax.net.ssl.SSLContext? = null
            override fun sslParameters(): javax.net.ssl.SSLParameters? = null
            override fun executor(): java.util.Optional<java.util.concurrent.Executor> = java.util.Optional.empty()
            override fun proxy(): java.util.Optional<java.net.ProxySelector> = java.util.Optional.empty()
            override fun authenticator(): java.util.Optional<java.net.Authenticator> = java.util.Optional.empty()
            override fun version(): Version? = null
        }
        val fakeHttpClient = FakeHttpClient()
        val integration = DatadogIntegration(fakeHttpClient, gson)
        // Use reflection to inject config
        val configField = DatadogIntegration::class.java.getDeclaredField("config")
        configField.isAccessible = true
        configField.set(integration, config)
        // Call getSutMetrics
        var metrics: DatadogMetrics? = null
        try {
            metrics = integration.getSutMetrics()
            println("metrics = $metrics")
        } catch (e: Exception) {
            println("Exception in getSutMetrics: ${e.message}")
            e.printStackTrace()
        }
        assertNotNull(metrics)
        assertEquals(2, metrics!!.criticalFindingsCount)
        assertEquals(2, metrics.securitySignalsCount)
        assertEquals(42.0, metrics.errorRate)
        assertEquals(42.0, metrics.avgResponseTime)
        assertEquals(42.0, metrics.p95ResponseTime)
    }

    @Test
    fun testGetErrorRateHappyPath() {
        config.enableDatadogIntegration = true
        config.datadogApiKey = "apiKey"
        config.datadogAppKey = "appKey"
        config.datadogApiUrl = "http://fake.datadog.api"
        config.datadogServiceName = "test-service"

        val metricJson = JsonObject().apply {
            add("data", JsonObject().apply {
                add("attributes", JsonObject().apply {
                    add("series", com.google.gson.JsonArray().apply {
                        add(JsonObject().apply {
                            add("values", com.google.gson.JsonArray().apply {
                                add(com.google.gson.JsonArray().apply {
                                    add(0)
                                    add(99.9)
                                })
                            })
                        })
                    })
                })
            })
        }
        val gson = Gson()
        class FakeHttpResponse(private val body: String) : HttpResponse<String> {
            override fun statusCode() = 200
            override fun body() = body
            override fun headers() = HttpHeaders.of(mapOf(), { _: String?, _: String? -> true })
            override fun request(): HttpRequest? = null
            override fun previousResponse(): java.util.Optional<HttpResponse<String>> = java.util.Optional.empty()
            override fun sslSession(): java.util.Optional<javax.net.ssl.SSLSession> = java.util.Optional.empty()
            override fun uri(): java.net.URI? = null
            override fun version(): HttpClient.Version? = null
        }
        class FakeHttpClient : HttpClient() {
            override fun <T : Any?> send(request: HttpRequest?, responseBodyHandler: HttpResponse.BodyHandler<T>?): HttpResponse<T> {
                val uri = request!!.uri().toString()
                val body = when {
                    uri.contains("/metrics/query") -> gson.toJson(metricJson)
                    else -> throw AssertionError("FakeHttpClient: Unexpected URI=$uri")
                }
                @Suppress("UNCHECKED_CAST")
                return FakeHttpResponse(body) as HttpResponse<T>
            }
            override fun <T : Any?> sendAsync(request: HttpRequest?, responseBodyHandler: HttpResponse.BodyHandler<T>?): CompletableFuture<HttpResponse<T>> {
                throw UnsupportedOperationException()
            }
            override fun <T : Any?> sendAsync(request: HttpRequest?, responseBodyHandler: HttpResponse.BodyHandler<T>?, pushPromiseHandler: HttpResponse.PushPromiseHandler<T>?): CompletableFuture<HttpResponse<T>> {
                throw UnsupportedOperationException()
            }
            override fun followRedirects(): Redirect? = null
            override fun connectTimeout(): java.util.Optional<java.time.Duration> = java.util.Optional.empty()
            override fun cookieHandler(): java.util.Optional<java.net.CookieHandler> = java.util.Optional.empty()
            override fun sslContext(): javax.net.ssl.SSLContext? = null
            override fun sslParameters(): javax.net.ssl.SSLParameters? = null
            override fun executor(): java.util.Optional<java.util.concurrent.Executor> = java.util.Optional.empty()
            override fun proxy(): java.util.Optional<java.net.ProxySelector> = java.util.Optional.empty()
            override fun authenticator(): java.util.Optional<java.net.Authenticator> = java.util.Optional.empty()
            override fun version(): Version? = null
        }
        val fakeHttpClient = FakeHttpClient()
        val integration = DatadogIntegration(fakeHttpClient, gson)
        val configField = DatadogIntegration::class.java.getDeclaredField("config")
        configField.isAccessible = true
        configField.set(integration, config)
        val errorRate = integration.javaClass.getDeclaredMethod("getErrorRate", Int::class.java).apply { isAccessible = true }
        val result = errorRate.invoke(integration, 15) as Double
        assertEquals(99.9, result)
    }

    @Test
    fun testGetAvgResponseTimeHappyPath() {
        config.enableDatadogIntegration = true
        config.datadogApiKey = "apiKey"
        config.datadogAppKey = "appKey"
        config.datadogApiUrl = "http://fake.datadog.api"
        config.datadogServiceName = "test-service"

        val metricJson = JsonObject().apply {
            add("data", JsonObject().apply {
                add("attributes", JsonObject().apply {
                    add("series", com.google.gson.JsonArray().apply {
                        add(JsonObject().apply {
                            add("values", com.google.gson.JsonArray().apply {
                                add(com.google.gson.JsonArray().apply {
                                    add(0)
                                    add(123.4)
                                })
                            })
                        })
                    })
                })
            })
        }
        val gson = Gson()
        class FakeHttpResponse(private val body: String) : HttpResponse<String> {
            override fun statusCode() = 200
            override fun body() = body
            override fun headers() = HttpHeaders.of(mapOf(), { _: String?, _: String? -> true })
            override fun request(): HttpRequest? = null
            override fun previousResponse(): java.util.Optional<HttpResponse<String>> = java.util.Optional.empty()
            override fun sslSession(): java.util.Optional<javax.net.ssl.SSLSession> = java.util.Optional.empty()
            override fun uri(): java.net.URI? = null
            override fun version(): HttpClient.Version? = null
        }
        class FakeHttpClient : HttpClient() {
            override fun <T : Any?> send(request: HttpRequest?, responseBodyHandler: HttpResponse.BodyHandler<T>?): HttpResponse<T> {
                val uri = request!!.uri().toString()
                val body = when {
                    uri.contains("/metrics/query") -> gson.toJson(metricJson)
                    else -> throw AssertionError("FakeHttpClient: Unexpected URI=$uri")
                }
                @Suppress("UNCHECKED_CAST")
                return FakeHttpResponse(body) as HttpResponse<T>
            }
            override fun <T : Any?> sendAsync(request: HttpRequest?, responseBodyHandler: HttpResponse.BodyHandler<T>?): CompletableFuture<HttpResponse<T>> {
                throw UnsupportedOperationException()
            }
            override fun <T : Any?> sendAsync(request: HttpRequest?, responseBodyHandler: HttpResponse.BodyHandler<T>?, pushPromiseHandler: HttpResponse.PushPromiseHandler<T>?): CompletableFuture<HttpResponse<T>> {
                throw UnsupportedOperationException()
            }
            override fun followRedirects(): Redirect? = null
            override fun connectTimeout(): java.util.Optional<java.time.Duration> = java.util.Optional.empty()
            override fun cookieHandler(): java.util.Optional<java.net.CookieHandler> = java.util.Optional.empty()
            override fun sslContext(): javax.net.ssl.SSLContext? = null
            override fun sslParameters(): javax.net.ssl.SSLParameters? = null
            override fun executor(): java.util.Optional<java.util.concurrent.Executor> = java.util.Optional.empty()
            override fun proxy(): java.util.Optional<java.net.ProxySelector> = java.util.Optional.empty()
            override fun authenticator(): java.util.Optional<java.net.Authenticator> = java.util.Optional.empty()
            override fun version(): Version? = null
        }
        val fakeHttpClient = FakeHttpClient()
        val integration = DatadogIntegration(fakeHttpClient, gson)
        val configField = DatadogIntegration::class.java.getDeclaredField("config")
        configField.isAccessible = true
        configField.set(integration, config)
        val avgResponseTime = integration.javaClass.getDeclaredMethod("getAvgResponseTime", Int::class.java).apply { isAccessible = true }
        val result = avgResponseTime.invoke(integration, 15) as Double
        assertEquals(123.4, result)
    }

    @Test
    fun testGetP95ResponseTimeHappyPath() {
        config.enableDatadogIntegration = true
        config.datadogApiKey = "apiKey"
        config.datadogAppKey = "appKey"
        config.datadogApiUrl = "http://fake.datadog.api"
        config.datadogServiceName = "test-service"

        val metricJson = JsonObject().apply {
            add("data", JsonObject().apply {
                add("attributes", JsonObject().apply {
                    add("series", com.google.gson.JsonArray().apply {
                        add(JsonObject().apply {
                            add("values", com.google.gson.JsonArray().apply {
                                add(com.google.gson.JsonArray().apply {
                                    add(0)
                                    add(555.5)
                                })
                            })
                        })
                    })
                })
            })
        }
        val gson = Gson()
        class FakeHttpResponse(private val body: String) : HttpResponse<String> {
            override fun statusCode() = 200
            override fun body() = body
            override fun headers() = HttpHeaders.of(mapOf(), { _: String?, _: String? -> true })
            override fun request(): HttpRequest? = null
            override fun previousResponse(): java.util.Optional<HttpResponse<String>> = java.util.Optional.empty()
            override fun sslSession(): java.util.Optional<javax.net.ssl.SSLSession> = java.util.Optional.empty()
            override fun uri(): java.net.URI? = null
            override fun version(): HttpClient.Version? = null
        }
        class FakeHttpClient : HttpClient() {
            override fun <T : Any?> send(request: HttpRequest?, responseBodyHandler: HttpResponse.BodyHandler<T>?): HttpResponse<T> {
                val uri = request!!.uri().toString()
                val body = when {
                    uri.contains("/metrics/query") -> gson.toJson(metricJson)
                    else -> throw AssertionError("FakeHttpClient: Unexpected URI=$uri")
                }
                @Suppress("UNCHECKED_CAST")
                return FakeHttpResponse(body) as HttpResponse<T>
            }
            override fun <T : Any?> sendAsync(request: HttpRequest?, responseBodyHandler: HttpResponse.BodyHandler<T>?): CompletableFuture<HttpResponse<T>> {
                throw UnsupportedOperationException()
            }
            override fun <T : Any?> sendAsync(request: HttpRequest?, responseBodyHandler: HttpResponse.BodyHandler<T>?, pushPromiseHandler: HttpResponse.PushPromiseHandler<T>?): CompletableFuture<HttpResponse<T>> {
                throw UnsupportedOperationException()
            }
            override fun followRedirects(): Redirect? = null
            override fun connectTimeout(): java.util.Optional<java.time.Duration> = java.util.Optional.empty()
            override fun cookieHandler(): java.util.Optional<java.net.CookieHandler> = java.util.Optional.empty()
            override fun sslContext(): javax.net.ssl.SSLContext? = null
            override fun sslParameters(): javax.net.ssl.SSLParameters? = null
            override fun executor(): java.util.Optional<java.util.concurrent.Executor> = java.util.Optional.empty()
            override fun proxy(): java.util.Optional<java.net.ProxySelector> = java.util.Optional.empty()
            override fun authenticator(): java.util.Optional<java.net.Authenticator> = java.util.Optional.empty()
            override fun version(): Version? = null
        }
        val fakeHttpClient = FakeHttpClient()
        val integration = DatadogIntegration(fakeHttpClient, gson)
        val configField = DatadogIntegration::class.java.getDeclaredField("config")
        configField.isAccessible = true
        configField.set(integration, config)
        val p95ResponseTime = integration.javaClass.getDeclaredMethod("getP95ResponseTime", Int::class.java).apply { isAccessible = true }
        val result = p95ResponseTime.invoke(integration, 15) as Double
        assertEquals(555.5, result)
    }

    @Test
    fun testGetSecuritySignalsCountHappyPath() {
        config.enableDatadogIntegration = true
        config.datadogApiKey = "apiKey"
        config.datadogAppKey = "appKey"
        config.datadogApiUrl = "http://fake.datadog.api"
        config.datadogServiceName = "test-service"

        val countJson = JsonObject().apply {
            add("data", com.google.gson.JsonArray().apply {
                add(JsonObject())
                add(JsonObject())
                add(JsonObject())
            })
        }
        val gson = Gson()
        class FakeHttpResponse(private val body: String) : HttpResponse<String> {
            override fun statusCode() = 200
            override fun body() = body
            override fun headers() = HttpHeaders.of(mapOf(), { _: String?, _: String? -> true })
            override fun request(): HttpRequest? = null
            override fun previousResponse(): java.util.Optional<HttpResponse<String>> = java.util.Optional.empty()
            override fun sslSession(): java.util.Optional<javax.net.ssl.SSLSession> = java.util.Optional.empty()
            override fun uri(): java.net.URI? = null
            override fun version(): HttpClient.Version? = null
        }
        class FakeHttpClient : HttpClient() {
            override fun <T : Any?> send(request: HttpRequest?, responseBodyHandler: HttpResponse.BodyHandler<T>?): HttpResponse<T> {
                val uri = request!!.uri().toString()
                val body = when {
                    uri.contains("/security_monitoring/signals") -> gson.toJson(countJson)
                    else -> throw AssertionError("FakeHttpClient: Unexpected URI=$uri")
                }
                @Suppress("UNCHECKED_CAST")
                return FakeHttpResponse(body) as HttpResponse<T>
            }
            override fun <T : Any?> sendAsync(request: HttpRequest?, responseBodyHandler: HttpResponse.BodyHandler<T>?): CompletableFuture<HttpResponse<T>> {
                throw UnsupportedOperationException()
            }
            override fun <T : Any?> sendAsync(request: HttpRequest?, responseBodyHandler: HttpResponse.BodyHandler<T>?, pushPromiseHandler: HttpResponse.PushPromiseHandler<T>?): CompletableFuture<HttpResponse<T>> {
                throw UnsupportedOperationException()
            }
            override fun followRedirects(): Redirect? = null
            override fun connectTimeout(): java.util.Optional<java.time.Duration> = java.util.Optional.empty()
            override fun cookieHandler(): java.util.Optional<java.net.CookieHandler> = java.util.Optional.empty()
            override fun sslContext(): javax.net.ssl.SSLContext? = null
            override fun sslParameters(): javax.net.ssl.SSLParameters? = null
            override fun executor(): java.util.Optional<java.util.concurrent.Executor> = java.util.Optional.empty()
            override fun proxy(): java.util.Optional<java.net.ProxySelector> = java.util.Optional.empty()
            override fun authenticator(): java.util.Optional<java.net.Authenticator> = java.util.Optional.empty()
            override fun version(): Version? = null
        }
        val fakeHttpClient = FakeHttpClient()
        val integration = DatadogIntegration(fakeHttpClient, gson)
        val configField = DatadogIntegration::class.java.getDeclaredField("config")
        configField.isAccessible = true
        configField.set(integration, config)
        val securitySignalsCount = integration.javaClass.getDeclaredMethod("getSecuritySignalsCount", Int::class.java).apply { isAccessible = true }
        val result = securitySignalsCount.invoke(integration, 15) as Int
        assertEquals(3, result)
    }

    @Test
    fun testGetCriticalFindingsCountHappyPath() {
        config.enableDatadogIntegration = true
        config.datadogApiKey = "apiKey"
        config.datadogAppKey = "appKey"
        config.datadogApiUrl = "http://fake.datadog.api"
        config.datadogServiceName = "test-service"

        val countJson = JsonObject().apply {
            add("data", com.google.gson.JsonArray().apply {
                add(JsonObject())
                add(JsonObject())
                add(JsonObject())
                add(JsonObject())
            })
        }
        val gson = Gson()
        class FakeHttpResponse(private val body: String) : HttpResponse<String> {
            override fun statusCode() = 200
            override fun body() = body
            override fun headers() = HttpHeaders.of(mapOf(), { _: String?, _: String? -> true })
            override fun request(): HttpRequest? = null
            override fun previousResponse(): java.util.Optional<HttpResponse<String>> = java.util.Optional.empty()
            override fun sslSession(): java.util.Optional<javax.net.ssl.SSLSession> = java.util.Optional.empty()
            override fun uri(): java.net.URI? = null
            override fun version(): HttpClient.Version? = null
        }
        class FakeHttpClient : HttpClient() {
            override fun <T : Any?> send(request: HttpRequest?, responseBodyHandler: HttpResponse.BodyHandler<T>?): HttpResponse<T> {
                val uri = request!!.uri().toString()
                val body = when {
                    uri.contains("/posture_management/findings") -> gson.toJson(countJson)
                    else -> throw AssertionError("FakeHttpClient: Unexpected URI=$uri")
                }
                @Suppress("UNCHECKED_CAST")
                return FakeHttpResponse(body) as HttpResponse<T>
            }
            override fun <T : Any?> sendAsync(request: HttpRequest?, responseBodyHandler: HttpResponse.BodyHandler<T>?): CompletableFuture<HttpResponse<T>> {
                throw UnsupportedOperationException()
            }
            override fun <T : Any?> sendAsync(request: HttpRequest?, responseBodyHandler: HttpResponse.BodyHandler<T>?, pushPromiseHandler: HttpResponse.PushPromiseHandler<T>?): CompletableFuture<HttpResponse<T>> {
                throw UnsupportedOperationException()
            }
            override fun followRedirects(): Redirect? = null
            override fun connectTimeout(): java.util.Optional<java.time.Duration> = java.util.Optional.empty()
            override fun cookieHandler(): java.util.Optional<java.net.CookieHandler> = java.util.Optional.empty()
            override fun sslContext(): javax.net.ssl.SSLContext? = null
            override fun sslParameters(): javax.net.ssl.SSLParameters? = null
            override fun executor(): java.util.Optional<java.util.concurrent.Executor> = java.util.Optional.empty()
            override fun proxy(): java.util.Optional<java.net.ProxySelector> = java.util.Optional.empty()
            override fun authenticator(): java.util.Optional<java.net.Authenticator> = java.util.Optional.empty()
            override fun version(): Version? = null
        }
        val fakeHttpClient = FakeHttpClient()
        val integration = DatadogIntegration(fakeHttpClient, gson)
        val configField = DatadogIntegration::class.java.getDeclaredField("config")
        configField.isAccessible = true
        configField.set(integration, config)
        val criticalFindingsCount = integration.javaClass.getDeclaredMethod("getCriticalFindingsCount", Int::class.java).apply { isAccessible = true }
        val result = criticalFindingsCount.invoke(integration, 15) as Int
        assertEquals(4, result)
    }

    @Test
    fun testQueryMetricHappyPath() {
        config.enableDatadogIntegration = true
        config.datadogApiKey = "apiKey"
        config.datadogAppKey = "appKey"
        config.datadogApiUrl = "http://fake.datadog.api"
        config.datadogServiceName = "test-service"

        val metricJson = JsonObject().apply {
            add("data", JsonObject().apply {
                add("attributes", JsonObject().apply {
                    add("series", com.google.gson.JsonArray().apply {
                        add(JsonObject().apply {
                            add("values", com.google.gson.JsonArray().apply {
                                add(com.google.gson.JsonArray().apply {
                                    add(0)
                                    add(77.7)
                                })
                            })
                        })
                    })
                })
            })
        }
        val gson = Gson()
        class FakeHttpResponse(private val body: String) : HttpResponse<String> {
            override fun statusCode() = 200
            override fun body() = body
            override fun headers() = HttpHeaders.of(mapOf(), { _: String?, _: String? -> true })
            override fun request(): HttpRequest? = null
            override fun previousResponse(): java.util.Optional<HttpResponse<String>> = java.util.Optional.empty()
            override fun sslSession(): java.util.Optional<javax.net.ssl.SSLSession> = java.util.Optional.empty()
            override fun uri(): java.net.URI? = null
            override fun version(): HttpClient.Version? = null
        }
        class FakeHttpClient : HttpClient() {
            override fun <T : Any?> send(request: HttpRequest?, responseBodyHandler: HttpResponse.BodyHandler<T>?): HttpResponse<T> {
                val uri = request!!.uri().toString()
                val body = when {
                    uri.contains("/metrics/query") -> gson.toJson(metricJson)
                    else -> throw AssertionError("FakeHttpClient: Unexpected URI=$uri")
                }
                @Suppress("UNCHECKED_CAST")
                return FakeHttpResponse(body) as HttpResponse<T>
            }
            override fun <T : Any?> sendAsync(request: HttpRequest?, responseBodyHandler: HttpResponse.BodyHandler<T>?): CompletableFuture<HttpResponse<T>> {
                throw UnsupportedOperationException()
            }
            override fun <T : Any?> sendAsync(request: HttpRequest?, responseBodyHandler: HttpResponse.BodyHandler<T>?, pushPromiseHandler: HttpResponse.PushPromiseHandler<T>?): CompletableFuture<HttpResponse<T>> {
                throw UnsupportedOperationException()
            }
            override fun followRedirects(): Redirect? = null
            override fun connectTimeout(): java.util.Optional<java.time.Duration> = java.util.Optional.empty()
            override fun cookieHandler(): java.util.Optional<java.net.CookieHandler> = java.util.Optional.empty()
            override fun sslContext(): javax.net.ssl.SSLContext? = null
            override fun sslParameters(): javax.net.ssl.SSLParameters? = null
            override fun executor(): java.util.Optional<java.util.concurrent.Executor> = java.util.Optional.empty()
            override fun proxy(): java.util.Optional<java.net.ProxySelector> = java.util.Optional.empty()
            override fun authenticator(): java.util.Optional<java.net.Authenticator> = java.util.Optional.empty()
            override fun version(): Version? = null
        }
        val fakeHttpClient = FakeHttpClient()
        val integration = DatadogIntegration(fakeHttpClient, gson)
        val configField = DatadogIntegration::class.java.getDeclaredField("config")
        configField.isAccessible = true
        configField.set(integration, config)
        val queryMetric = integration.javaClass.getDeclaredMethod("queryMetric", String::class.java, Int::class.java).apply { isAccessible = true }
        val result = queryMetric.invoke(integration, "avg:trace.servlet.request.duration{service:test-service}", 15) as Double
        assertEquals(77.7, result)
    }
}
