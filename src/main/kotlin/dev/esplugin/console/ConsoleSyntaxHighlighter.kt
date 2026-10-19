package dev.esplugin.console

import com.intellij.lexer.Lexer
import com.intellij.lexer.LexerPosition
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.tree.IElementType

object ConsoleTokenTypes {
    val METHOD = IElementType("ES_CONSOLE_METHOD", null)
    val PATH = IElementType("ES_CONSOLE_PATH", null)
    val HEADER_NAME = IElementType("ES_CONSOLE_HEADER_NAME", null)
    val HEADER_VALUE = IElementType("ES_CONSOLE_HEADER_VALUE", null)
    val JSON_KEY = IElementType("ES_CONSOLE_JSON_KEY", null)
    val JSON_STRING = IElementType("ES_CONSOLE_JSON_STRING", null)
    val JSON_NUMBER = IElementType("ES_CONSOLE_JSON_NUMBER", null)
    val JSON_KEYWORD = IElementType("ES_CONSOLE_JSON_KEYWORD", null)
    val JSON_BRACE = IElementType("ES_CONSOLE_JSON_BRACE", null)
    val JSON_OPERATION = IElementType("ES_CONSOLE_JSON_OPERATION", null)
    val BAD = IElementType("ES_CONSOLE_BAD", null)
    val WHITESPACE = IElementType("ES_CONSOLE_WHITESPACE", null)
}

class ConsoleSyntaxHighlighter : SyntaxHighlighterBase() {

    override fun getHighlightingLexer(): Lexer = ConsoleLexer()

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> = when (tokenType) {
        ConsoleTokenTypes.METHOD,
        ConsoleTokenTypes.PATH,
        ConsoleTokenTypes.HEADER_NAME,
        ConsoleTokenTypes.JSON_KEY,
        ConsoleTokenTypes.JSON_KEYWORD,
        -> arrayOf(DefaultLanguageHighlighterColors.KEYWORD)

        ConsoleTokenTypes.HEADER_VALUE,
        ConsoleTokenTypes.JSON_STRING,
        -> arrayOf(DefaultLanguageHighlighterColors.STRING)

        ConsoleTokenTypes.JSON_NUMBER -> arrayOf(DefaultLanguageHighlighterColors.NUMBER)
        ConsoleTokenTypes.JSON_BRACE -> arrayOf(DefaultLanguageHighlighterColors.BRACES)
        ConsoleTokenTypes.JSON_OPERATION -> arrayOf(DefaultLanguageHighlighterColors.OPERATION_SIGN)
        ConsoleTokenTypes.BAD -> arrayOf(DefaultLanguageHighlighterColors.INVALID_STRING_ESCAPE)
        else -> emptyArray()
    }
}

private data class ConsoleToken(val type: IElementType, val start: Int, val end: Int)

private class ConsoleLexerPosition(
    private val offset: Int,
    private val state: Int,
) : LexerPosition {
    override fun getOffset(): Int = offset

    override fun getState(): Int = state
}

/**
 * Tokenizes the console request DSL: a method/path line, optional headers, then JSON.
 * It is intentionally small - the editor only needs colours, not a PSI tree.
 */
class ConsoleLexer : Lexer() {

    private var buffer: CharSequence = ""
    private var tokens: List<ConsoleToken> = emptyList()
    private var index = 0

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        tokens = tokenize(buffer, startOffset, endOffset)
        index = 0
    }

    override fun getState(): Int = 0

    override fun getCurrentPosition(): LexerPosition = ConsoleLexerPosition(
        tokens.getOrNull(index)?.start ?: buffer.length,
        0,
    )

    override fun restore(position: LexerPosition) {
        val offset = position.offset
        index = tokens.indexOfFirst { it.start >= offset }.let { if (it < 0) tokens.size else it }
    }

    override fun getTokenType(): IElementType? = tokens.getOrNull(index)?.type

    override fun getTokenStart(): Int = tokens.getOrNull(index)?.start ?: buffer.length

    override fun getTokenEnd(): Int = tokens.getOrNull(index)?.end ?: buffer.length

    override fun advance() {
        if (index < tokens.size) index++
    }

    override fun getBufferSequence(): CharSequence = buffer

    override fun getBufferEnd(): Int = buffer.length

    private fun tokenize(text: CharSequence, start: Int, end: Int): List<ConsoleToken> {
        val tokens = mutableListOf<ConsoleToken>()

        fun add(type: IElementType, from: Int, to: Int) {
            if (to > from) tokens.add(ConsoleToken(type, from, to))
        }

        val lines = mutableListOf<Int>()
        var lineStart = start
        while (lineStart < end) {
            lines.add(lineStart)
            val newline = text.indexOf('\n', lineStart)
            if (newline < 0 || newline >= end) break
            lineStart = newline + 1
        }
        if (lines.isEmpty()) return tokens

        fun lineEnd(lineStart: Int): Int {
            val newline = text.indexOf('\n', lineStart)
            return if (newline < 0 || newline > end) end else newline
        }

        val firstLineIndex = lines.indexOfFirst { lineStart ->
            val lineEnd = lineEnd(lineStart)
            text.subSequence(lineStart, lineEnd).isNotBlank()
        }
        if (firstLineIndex < 0) return tokens

        val requestStart = lines[firstLineIndex]
        val requestEnd = lineEnd(requestStart)
        tokenizeRequestLine(text, requestStart, requestEnd, tokens, ::add)
        if (requestEnd < end) add(ConsoleTokenTypes.WHITESPACE, requestEnd, requestEnd + 1)

        var bodyStart = end
        for (i in firstLineIndex + 1 until lines.size) {
            val lineStartOffset = lines[i]
            val lineEndOffset = lineEnd(lineStartOffset)
            val line = text.subSequence(lineStartOffset, lineEndOffset)
            if (line.isBlank()) continue
            val trimmedStart = lineStartOffset + line.indexOfFirst { !it.isWhitespace() }
            if (line.trimStart().startsWith("{") || line.trimStart().startsWith("[")) {
                bodyStart = trimmedStart
                break
            }
        }

        for (i in firstLineIndex + 1 until lines.size) {
            val lineStartOffset = lines[i]
            if (lineStartOffset >= bodyStart) break
            val lineEndOffset = lineEnd(lineStartOffset)
            val line = text.subSequence(lineStartOffset, lineEndOffset)
            val firstNonWhitespace = lineStartOffset + line.indexOfFirst { !it.isWhitespace() }
            if (firstNonWhitespace >= bodyStart) break
            tokenizeHeaderLine(text, lineStartOffset, lineEndOffset, tokens, ::add)
            if (lineEndOffset < end) add(ConsoleTokenTypes.WHITESPACE, lineEndOffset, lineEndOffset + 1)
        }

        if (bodyStart < end) {
            tokenizeJson(text, bodyStart, end, tokens, ::add)
        }

        return tokens
    }

    private fun tokenizeRequestLine(
        text: CharSequence,
        start: Int,
        end: Int,
        tokens: MutableList<ConsoleToken>,
        add: (IElementType, Int, Int) -> Unit,
    ) {
        var pos = start
        while (pos < end && text[pos].isWhitespace()) pos++
        if (pos >= end) return

        val methodEnd = text.indexOf(' ', pos).let { if (it < 0 || it > end) end else it }
        val token = text.subSequence(pos, methodEnd).toString()
        if (token.all { it.isLetter() } && methodEnd < end) {
            add(ConsoleTokenTypes.METHOD, pos, methodEnd)
            while (methodEnd < end && text[methodEnd] == ' ') {
                add(ConsoleTokenTypes.WHITESPACE, methodEnd, methodEnd + 1)
                pos = methodEnd + 1
                break
            }
            add(ConsoleTokenTypes.PATH, pos, end)
        } else {
            add(ConsoleTokenTypes.PATH, pos, end)
        }
    }

    private fun tokenizeHeaderLine(
        text: CharSequence,
        start: Int,
        end: Int,
        tokens: MutableList<ConsoleToken>,
        add: (IElementType, Int, Int) -> Unit,
    ) {
        var pos = start
        while (pos < end && text[pos].isWhitespace()) {
            add(ConsoleTokenTypes.WHITESPACE, pos, pos + 1)
            pos++
        }
        if (pos >= end) return

        val colon = text.indexOf(':', pos).let { if (it < 0 || it > end) -1 else it }
        if (colon < 0) {
            add(ConsoleTokenTypes.HEADER_VALUE, pos, end)
            return
        }

        add(ConsoleTokenTypes.HEADER_NAME, pos, colon)
        add(ConsoleTokenTypes.JSON_OPERATION, colon, colon + 1)
        add(ConsoleTokenTypes.HEADER_VALUE, colon + 1, end)
    }

    private fun tokenizeJson(
        text: CharSequence,
        start: Int,
        end: Int,
        tokens: MutableList<ConsoleToken>,
        add: (IElementType, Int, Int) -> Unit,
    ) {
        var pos = start
        while (pos < end) {
            val c = text[pos]
            when {
                c.isWhitespace() -> {
                    val from = pos
                    while (pos < end && text[pos].isWhitespace()) pos++
                    add(ConsoleTokenTypes.WHITESPACE, from, pos)
                }

                c == '{' || c == '}' || c == '[' || c == ']' -> {
                    add(ConsoleTokenTypes.JSON_BRACE, pos, pos + 1)
                    pos++
                }

                c == ':' || c == ',' -> {
                    add(ConsoleTokenTypes.JSON_OPERATION, pos, pos + 1)
                    pos++
                }

                c == '"' -> {
                    val from = pos
                    pos++
                    while (pos < end) {
                        when (text[pos]) {
                            '\\' -> pos = (pos + 2).coerceAtMost(end)
                            '"' -> {
                                pos++
                                break
                            }
                            else -> pos++
                        }
                    }
                    var next = pos
                    while (next < end && text[next].isWhitespace()) next++
                    val type = if (next < end && text[next] == ':') {
                        ConsoleTokenTypes.JSON_KEY
                    } else {
                        ConsoleTokenTypes.JSON_STRING
                    }
                    add(type, from, pos)
                }

                c == '-' || c.isDigit() -> {
                    val from = pos
                    if (text[pos] == '-') pos++
                    while (pos < end && text[pos].isDigit()) pos++
                    if (pos < end && text[pos] == '.') {
                        pos++
                        while (pos < end && text[pos].isDigit()) pos++
                    }
                    if (pos < end && (text[pos] == 'e' || text[pos] == 'E')) {
                        pos++
                        if (pos < end && (text[pos] == '+' || text[pos] == '-')) pos++
                        while (pos < end && text[pos].isDigit()) pos++
                    }
                    add(ConsoleTokenTypes.JSON_NUMBER, from, pos)
                }

                c.isLetter() -> {
                    val from = pos
                    while (pos < end && text[pos].isLetter()) pos++
                    val word = text.subSequence(from, pos).toString()
                    add(
                        if (word == "true" || word == "false" || word == "null") {
                            ConsoleTokenTypes.JSON_KEYWORD
                        } else {
                            ConsoleTokenTypes.BAD
                        },
                        from,
                        pos,
                    )
                }

                else -> {
                    add(ConsoleTokenTypes.BAD, pos, pos + 1)
                    pos++
                }
            }
        }
    }
}
