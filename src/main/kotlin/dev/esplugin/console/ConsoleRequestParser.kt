package dev.esplugin.console

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser

data class ConsoleRequest(
    val method: String,
    val path: String,
    val headersText: String,
    val headers: Map<String, String>,
    val body: String,
)

/**
 * The console uses a small request DSL:
 *
 * GET /index/_search
 * Header: value
 * {
 *   "query": {"match_all": {}}
 * }
 *
 * The request line is required, headers are optional and the body starts either
 * after a blank line or at the first line that begins with `{` or `[`.
 */
object ConsoleRequestParser {

    private val METHODS = setOf("GET", "POST", "PUT", "DELETE", "HEAD", "PATCH")

    fun format(request: SavedRequest): String {
        val headers = ConsoleUtils.withoutSecrets(request.headers).trim()
        val body = prettyBody(request.body)
        return buildString {
            append(request.method.ifBlank { "GET" }.uppercase()).append(' ')
            append(request.path.ifBlank { "/" })
            if (headers.isNotEmpty()) append('\n').append(headers)
            if (body.isNotBlank()) append('\n').append(body)
        }
    }

    /**
     * Pretty-prints a JSON object/array while leaving non-JSON bodies untouched.
     */
    fun prettyBody(body: String): String {
        if (body.isBlank()) return body
        return runCatching {
            val element = JsonParser.parseString(body)
            if (element.isJsonObject || element.isJsonArray) {
                GsonBuilder()
                    .setPrettyPrinting()
                    .disableHtmlEscaping()
                    .create()
                    .toJson(element)
            } else {
                body
            }
        }.getOrDefault(body)
    }

    fun isJson(body: String): Boolean {
        if (body.isBlank()) return false
        return runCatching {
            val element = JsonParser.parseString(body)
            element.isJsonObject || element.isJsonArray
        }.getOrDefault(false)
    }

    fun format(request: ConsoleRequest): String = format(
        SavedRequest(
            connectionId = "",
            name = "",
            method = request.method,
            path = request.path,
            headers = request.headersText,
            body = request.body,
        ),
    )

    fun parse(text: String): ConsoleRequest? {
        val lines = text.lines()
        val firstIndex = lines.indexOfFirst { it.isNotBlank() }
        if (firstIndex < 0) return null

        val requestLine = parseRequestLine(lines[firstIndex].trim()) ?: return null
        val rest = lines.drop(firstIndex + 1)
        val blankIndex = rest.indexOfFirst { it.isBlank() }

        val headerLines: List<String>
        val bodyLines: List<String>
        if (blankIndex >= 0) {
            headerLines = rest.take(blankIndex)
            bodyLines = rest.drop(blankIndex + 1)
        } else {
            val bodyStart = rest.indexOfFirst {
                val trimmed = it.trimStart()
                trimmed.startsWith("{") || trimmed.startsWith("[")
            }
            if (bodyStart >= 0) {
                headerLines = rest.take(bodyStart)
                bodyLines = rest.drop(bodyStart)
            } else {
                headerLines = rest
                bodyLines = emptyList()
            }
        }

        val headersText = headerLines.joinToString("\n").trim()
        return ConsoleRequest(
            method = requestLine.first,
            path = requestLine.second,
            headersText = headersText,
            headers = ConsoleUtils.parseHeaders(headersText),
            body = bodyLines.joinToString("\n").trim(),
        )
    }

    private fun parseRequestLine(line: String): Pair<String, String>? {
        val space = line.indexOf(' ')
        if (space <= 0) {
            if (line.uppercase() in METHODS) return null
            return "GET" to line
        }

        val token = line.substring(0, space).uppercase()
        if (token in METHODS) {
            val path = line.substring(space + 1).trim()
            if (path.isEmpty()) return null
            return token to path
        }
        return "GET" to line
    }
}
