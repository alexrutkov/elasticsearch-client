package dev.esplugin.client

import com.google.gson.JsonParser
import dev.esplugin.settings.AuthType
import dev.esplugin.settings.ConnectionConfig
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class EsResponse(
    val status: Int,
    val body: String,
    val durationMs: Long,
) {
    val isSuccess: Boolean get() = status in 200..299
}

data class EsClusterInfo(
    val clusterName: String,
    val version: String,
    val distribution: String?,
    val tagline: String?,
)

data class EsHealth(
    val status: String,
    val numberOfNodes: Int,
)

data class EsConnectionInfo(
    val cluster: EsClusterInfo,
    val health: EsHealth?,
)

class EsException(
    message: String,
    val status: Int? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Minimal synchronous Elasticsearch/OpenSearch REST client.
 *
 * Every call is blocking, therefore it must never be invoked from the EDT.
 */
class EsClient(
    private val config: ConnectionConfig,
    private val secret: String?,
) {

    private val baseUrl: String = config.url.trim().trimEnd('/')

    private val http: HttpClient by lazy { buildHttpClient() }

    fun request(
        method: String,
        path: String,
        body: String? = null,
        query: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): EsResponse {
        val uri = buildUri(path, query)
        val builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(config.requestTimeoutSeconds.coerceAtLeast(1).toLong()))
            .header("Accept", "application/json")

        authHeader()?.let { header ->
            if (headers.keys.none { it.equals("Authorization", ignoreCase = true) }) {
                builder.header("Authorization", header)
            }
        }

        val overridden = headers.keys.map { it.lowercase() }.toSet()
        customHeaders()
            .filterNot { it.first.lowercase() in overridden }
            .forEach { (name, value) -> builder.header(name, value) }
        headers.forEach { (name, value) -> builder.header(name, value) }

        val publisher = if (body != null) {
            val hasContentType = (customHeaders() + headers.entries.map { it.key to it.value })
                .any { it.first.equals("Content-Type", ignoreCase = true) }
            if (!hasContentType) {
                builder.header("Content-Type", "application/json")
            }
            HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)
        } else {
            HttpRequest.BodyPublishers.noBody()
        }
        builder.method(method.uppercase(), publisher)

        val startedAt = System.nanoTime()
        try {
            val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            val durationMs = (System.nanoTime() - startedAt) / 1_000_000
            return EsResponse(response.statusCode(), response.body().orEmpty(), durationMs)
        } catch (e: java.net.http.HttpTimeoutException) {
            throw EsException("Request timed out after ${config.requestTimeoutSeconds}s", cause = e)
        } catch (e: java.net.ConnectException) {
            throw EsException("Cannot connect to $baseUrl: ${e.message}", cause = e)
        } catch (e: java.net.UnknownHostException) {
            throw EsException("Unknown host: ${e.message}", cause = e)
        } catch (e: javax.net.ssl.SSLException) {
            throw EsException("TLS error: ${e.message}", cause = e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw EsException("Request interrupted", cause = e)
        } catch (e: Exception) {
            throw EsException("Request failed: ${e.message ?: e.javaClass.simpleName}", cause = e)
        }
    }

    fun get(path: String, query: Map<String, String> = emptyMap()): EsResponse = request("GET", path, query = query)

    fun checkConnection(): EsConnectionInfo {
        val infoResponse = get("/")
        if (!infoResponse.isSuccess) {
            throw EsException(
                "HTTP ${infoResponse.status}: ${infoResponse.body.take(500).ifBlank { "empty response" }}",
                infoResponse.status,
            )
        }
        val info = parseClusterInfo(infoResponse.body)

        // Health is optional: some restricted users cannot call _cluster/health.
        val health = runCatching { fetchHealth() }.getOrNull()
        return EsConnectionInfo(info, health)
    }

    private fun fetchHealth(): EsHealth {
        val response = get("/_cluster/health")
        if (!response.isSuccess) {
            throw EsException("HTTP ${response.status}", response.status)
        }
        val root = JsonParser.parseString(response.body).asJsonObject
        val status = root.get("status")?.asString ?: "unknown"
        val nodes = root.get("number_of_nodes")?.asInt ?: 0
        return EsHealth(status, nodes)
    }

    private fun parseClusterInfo(body: String): EsClusterInfo {
        val root = runCatching { JsonParser.parseString(body).asJsonObject }
            .getOrElse { throw EsException("Unexpected response from the cluster (not JSON)") }

        val versionObject = runCatching { root.getAsJsonObject("version") }.getOrNull()
        return EsClusterInfo(
            clusterName = root.get("cluster_name")?.asString ?: "unknown",
            version = versionObject?.get("number")?.asString ?: "unknown",
            distribution = versionObject?.get("distribution")?.asString,
            tagline = root.get("tagline")?.asString,
        )
    }

    private fun authHeader(): String? {
        return when (config.authType) {
            AuthType.NONE -> null
            AuthType.BASIC -> {
                val raw = "${config.username}:${secret.orEmpty()}"
                "Basic " + Base64.getEncoder().encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
            }
            AuthType.API_KEY -> {
                val raw = secret.orEmpty()
                val encoded = if (raw.contains(':')) {
                    Base64.getEncoder().encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
                } else {
                    raw
                }
                "ApiKey $encoded"
            }
            AuthType.BEARER -> "Bearer ${secret.orEmpty()}"
        }
    }

    private fun customHeaders(): List<Pair<String, String>> =
        config.headers.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) return@mapNotNull null
                val name = line.substring(0, separator).trim()
                val value = line.substring(separator + 1).trim()
                if (name.isEmpty()) null else name to value
            }
            .toList()

    private fun buildUri(path: String, query: Map<String, String>): URI {
        val normalizedPath = if (path.startsWith('/')) path else "/$path"
        val queryString = if (query.isEmpty()) {
            ""
        } else {
            val separator = if (normalizedPath.contains('?')) "&" else "?"
            separator + query.entries.joinToString("&") { (name, value) ->
                "${encode(name)}=${encode(value)}"
            }
        }
        val text = baseUrl + normalizedPath + queryString
        return try {
            URI.create(text)
        } catch (e: IllegalArgumentException) {
            throw EsException("Invalid URL: $text", cause = e)
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    private fun buildHttpClient(): HttpClient {
        val builder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(config.connectTimeoutSeconds.coerceAtLeast(1).toLong()))
            .followRedirects(HttpClient.Redirect.NORMAL)

        if (!config.verifySsl) {
            // HttpClient checks the hostname separately from the trust manager and has no public switch.
            // The property is global, so it is only set after the user explicitly disabled verification.
            System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true")
            builder.sslContext(trustAllSslContext())
        }
        return builder.build()
    }

    private fun trustAllSslContext(): SSLContext {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        return context
    }
}
