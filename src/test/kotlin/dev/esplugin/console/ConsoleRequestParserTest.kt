package dev.esplugin.console

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ConsoleRequestParserTest {

    @Test
    fun `parses request line and json body without a blank line`() {
        val request = ConsoleRequestParser.parse(
            """
            GET /iom_vo_v3/_search
            {
              "from": 0,
              "size": 50,
              "query": {"match_all": {}}
            }
            """.trimIndent(),
        )

        assertEquals("GET", request?.method)
        assertEquals("/iom_vo_v3/_search", request?.path)
        assertEquals("", request?.headersText)
        assertEquals(true, request?.body?.contains("\"match_all\""))
    }

    @Test
    fun `parses headers and body`() {
        val request = ConsoleRequestParser.parse(
            """
            POST /_search?pretty=true
            Content-Type: application/json
            X-Trace: 42
            {"query":{"match_all":{}}}
            """.trimIndent(),
        )

        assertEquals("POST", request?.method)
        assertEquals("/_search?pretty=true", request?.path)
        assertEquals("application/json", request?.headers?.get("Content-Type"))
        assertEquals("42", request?.headers?.get("X-Trace"))
        assertEquals("""{"query":{"match_all":{}}}""", request?.body)
    }

    @Test
    fun `parses headers separated from body by a blank line`() {
        val request = ConsoleRequestParser.parse(
            """
            PUT /index

            {"settings":{}}
            """.trimIndent(),
        )

        assertEquals("PUT", request?.method)
        assertEquals("/index", request?.path)
        assertEquals("", request?.headersText)
        assertEquals("""{"settings":{}}""", request?.body)
    }

    @Test
    fun `defaults to get when the method is omitted`() {
        val request = ConsoleRequestParser.parse("/_cat/indices?format=json")

        assertEquals("GET", request?.method)
        assertEquals("/_cat/indices?format=json", request?.path)
    }

    @Test
    fun `rejects empty and method-only input`() {
        assertNull(ConsoleRequestParser.parse(""))
        assertNull(ConsoleRequestParser.parse("   \n  "))
        assertNull(ConsoleRequestParser.parse("GET"))
    }

    @Test
    fun `formats and parses a saved request`() {
        val saved = SavedRequest(
            connectionId = "c1",
            name = "search",
            method = "POST",
            path = "/index/_search",
            headers = "Content-Type: application/json",
            body = """{"query":{"match_all":{}}}""",
        )

        val text = ConsoleRequestParser.format(saved)
        val parsed = ConsoleRequestParser.parse(text)

        assertEquals("POST", parsed?.method)
        assertEquals("/index/_search", parsed?.path)
        assertEquals("application/json", parsed?.headers?.get("Content-Type"))
        assertEquals(saved.body, parsed?.body)
    }
}
