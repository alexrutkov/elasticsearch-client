package dev.esplugin.console

import com.intellij.psi.tree.IElementType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConsoleSyntaxHighlighterTest {

    private fun tokens(text: String): List<Pair<IElementType?, String>> {
        val lexer = ConsoleLexer()
        lexer.start(text)
        val result = mutableListOf<Pair<IElementType?, String>>()
        while (lexer.tokenType != null) {
            result.add(lexer.tokenType to text.substring(lexer.tokenStart, lexer.tokenEnd))
            lexer.advance()
        }
        return result
    }

    @Test
    fun `tokenizes method path and json body`() {
        val text = """
            GET /iom_vo_v3/_search
            {
              "from": 0,
              "size": 50,
              "query": {"match_all": {}}
            }
        """.trimIndent()

        val tokens = tokens(text)
        assertTrue(tokens.any { it.first == ConsoleTokenTypes.METHOD && it.second == "GET" })
        assertTrue(tokens.any { it.first == ConsoleTokenTypes.PATH && it.second == "/iom_vo_v3/_search" })
        assertTrue(tokens.any { it.first == ConsoleTokenTypes.JSON_KEY && it.second == "\"from\"" })
        assertTrue(tokens.any { it.first == ConsoleTokenTypes.JSON_NUMBER && it.second == "0" })
        assertTrue(tokens.any { it.first == ConsoleTokenTypes.JSON_BRACE && it.second == "{" })
        assertTrue(tokens.any { it.first == ConsoleTokenTypes.JSON_OPERATION && it.second == ":" })
    }

    @Test
    fun `tokenizes headers before the body`() {
        val text = """
            POST /index/_search
            Content-Type: application/json
            {"query":{"match_all":{}}}
        """.trimIndent()

        val tokens = tokens(text)
        assertTrue(tokens.any { it.first == ConsoleTokenTypes.HEADER_NAME && it.second == "Content-Type" })
        assertTrue(tokens.any { it.first == ConsoleTokenTypes.HEADER_VALUE && it.second == " application/json" })
        assertEquals(3, tokens.count { it.second == "{" && it.first == ConsoleTokenTypes.JSON_BRACE })
    }

    @Test
    fun `highlights json tokens with editor colors`() {
        val highlighter = ConsoleSyntaxHighlighter()

        assertTrue(highlighter.getTokenHighlights(ConsoleTokenTypes.JSON_KEY).isNotEmpty())
        assertTrue(highlighter.getTokenHighlights(ConsoleTokenTypes.JSON_STRING).isNotEmpty())
        assertTrue(highlighter.getTokenHighlights(ConsoleTokenTypes.JSON_NUMBER).isNotEmpty())
        assertTrue(highlighter.getTokenHighlights(ConsoleTokenTypes.JSON_BRACE).isNotEmpty())
        assertTrue(highlighter.getTokenHighlights(ConsoleTokenTypes.WHITESPACE).isEmpty())
    }
}
