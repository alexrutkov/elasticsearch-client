package dev.esplugin.console

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConsoleUtilsTest {

    @Test
    fun `parses name value header lines`() {
        val headers = ConsoleUtils.parseHeaders(
            """
            Content-Type: application/json
            X-Trace-Id:abc-123
            # comment
            InvalidLineWithoutColon
            """.trimIndent(),
        )

        assertEquals("application/json", headers["Content-Type"])
        assertEquals("abc-123", headers["X-Trace-Id"])
        assertEquals(2, headers.size)
    }

    @Test
    fun `last duplicate header wins`() {
        val headers = ConsoleUtils.parseHeaders("X-Test: 1\nX-Test: 2")
        assertEquals("2", headers["X-Test"])
    }

    @Test
    fun `removes credentials from saved headers`() {
        val text = """
            Authorization: Bearer secret
            X-Api-Key: secret2
            Content-Type: application/json
            Cookie: session=1
        """.trimIndent()

        val sanitized = ConsoleUtils.withoutSecrets(text)

        assertFalse(sanitized.contains("secret"))
        assertFalse(sanitized.contains("Cookie"))
        assertTrue(sanitized.contains("Content-Type: application/json"))
    }

    @Test
    fun `pretty prints json and keeps plain text`() {
        val formatted = ConsoleUtils.prettyJson("""{"a":1,"b":[2,3]}""")
        assertTrue(formatted.contains("\n"))
        assertTrue(formatted.contains("\"a\": 1"))

        assertEquals("plain text", ConsoleUtils.prettyJson("plain text"))
        assertEquals("not json {", ConsoleUtils.prettyJson("not json {"))
    }

    @Test
    fun `detects json bodies`() {
        assertTrue(ConsoleUtils.isJson("""{"a":1}"""))
        assertTrue(ConsoleUtils.isJson("[1,2]"))
        assertFalse(ConsoleUtils.isJson("hello"))
        assertFalse(ConsoleUtils.isJson("{broken"))
    }
}
