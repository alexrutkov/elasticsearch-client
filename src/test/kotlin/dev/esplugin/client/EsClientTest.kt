package dev.esplugin.client

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.esplugin.settings.AuthType
import dev.esplugin.settings.ConnectionConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.Base64

class EsClientTest {

    private lateinit var server: HttpServer
    private var lastAuthorization: String? = null
    private var unauthorized = false

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun `parses cluster info and health`() {
        val info = EsClient(config(), null).checkConnection()

        assertEquals("test-cluster", info.cluster.clusterName)
        assertEquals("8.13.0", info.cluster.version)
        assertEquals("green", info.health?.status)
        assertEquals(1, info.health?.numberOfNodes)
    }

    @Test
    fun `sends basic authorization header`() {
        EsClient(config(AuthType.BASIC), "secret").checkConnection()

        val expected = "Basic " + Base64.getEncoder().encodeToString("user:secret".toByteArray())
        assertEquals(expected, lastAuthorization)
    }

    @Test
    fun `encodes raw api key`() {
        EsClient(config(AuthType.API_KEY), "id:key").checkConnection()

        val expected = "ApiKey " + Base64.getEncoder().encodeToString("id:key".toByteArray())
        assertEquals(expected, lastAuthorization)
    }

    @Test
    fun `accepts already encoded api key`() {
        EsClient(config(AuthType.API_KEY), "YWJjOmRlZg==").checkConnection()

        assertEquals("ApiKey YWJjOmRlZg==", lastAuthorization)
    }

    @Test
    fun `sends bearer token`() {
        EsClient(config(AuthType.BEARER), "token").checkConnection()

        assertEquals("Bearer token", lastAuthorization)
    }

    @Test
    fun `reports http errors`() {
        unauthorized = true

        val error = assertThrows(EsException::class.java) {
            EsClient(config(), null).checkConnection()
        }

        assertEquals(401, error.status)
    }

    private fun handle(exchange: HttpExchange) {
        lastAuthorization = exchange.requestHeaders.getFirst("Authorization")
        val path = exchange.requestURI.path
        val (status, body) = when {
            unauthorized && path == "/" -> 401 to """{"error":"unauthorized"}"""
            path == "/" -> 200 to """{"cluster_name":"test-cluster","version":{"number":"8.13.0"},"tagline":"You Know, for Search"}"""
            path == "/_cluster/health" -> 200 to """{"status":"green","number_of_nodes":1}"""
            else -> 404 to """{"error":"not found"}"""
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun config(auth: AuthType = AuthType.NONE): ConnectionConfig =
        ConnectionConfig().apply {
            name = "test"
            url = "http://127.0.0.1:${server.address.port}"
            authType = auth
            username = "user"
            connectTimeoutSeconds = 5
            requestTimeoutSeconds = 5
        }
}
