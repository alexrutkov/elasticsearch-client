package dev.esplugin.client

import dev.esplugin.settings.AuthType
import dev.esplugin.settings.ConnectionConfig
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * Opt-in integration test against a real cluster:
 *
 * `gradle test -Des.url=http://192.168.56.2:9200`
 */
@EnabledIfSystemProperty(named = "es.url", matches = ".+")
class EsIntegrationTest {

    private val baseUrl: String = System.getProperty("es.url")

    private fun client(): EsClient = EsClient(
        ConnectionConfig().apply {
            name = "integration"
            url = baseUrl
            authType = AuthType.NONE
            requestTimeoutSeconds = 30
        },
        null,
    )

    @Test
    fun `reads cluster info and health`() {
        val info = client().checkConnection()

        assertNotNull(info.cluster.clusterName)
        assertTrue(info.cluster.version.isNotBlank(), "version should not be blank")
        assertNotNull(info.health, "cluster health should be available")
    }

    @Test
    fun `lists indices via cat API`() {
        val response = client().get(
            "/_cat/indices",
            mapOf("format" to "json", "h" to "index,health,status,docs.count,store.size"),
        )

        assertTrue(response.isSuccess) { "HTTP ${response.status}: ${response.body}" }
        assertTrue(response.body.trimStart().startsWith("["), "expected a JSON array")
    }

    @Test
    fun `lists aliases and nodes`() {
        val aliases = client().get("/_cat/aliases", mapOf("format" to "json", "h" to "alias,index"))
        assertTrue(aliases.isSuccess) { "aliases: HTTP ${aliases.status}" }

        val nodes = client().get(
            "/_cat/nodes",
            mapOf("format" to "json", "h" to "name,ip,node.role,master,heap.percent,cpu"),
        )
        assertTrue(nodes.isSuccess) { "nodes: HTTP ${nodes.status}" }
        assertTrue(nodes.body.contains("\"name\""), "expected node names in response")
    }

    @Test
    fun `reads data streams`() {
        val response = client().get("/_data_stream")

        assertTrue(response.status == 200 || response.status == 400 || response.status == 404) {
            "unexpected HTTP ${response.status}: ${response.body}"
        }
    }
}
