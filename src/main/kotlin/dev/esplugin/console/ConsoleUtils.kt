package dev.esplugin.console

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser

object ConsoleUtils {

    private val sensitiveHeaders = setOf(
        "authorization",
        "proxy-authorization",
        "cookie",
        "set-cookie",
        "x-api-key",
        "api-key",
    )

    /**
     * Parses `Name: Value` lines. Blank lines and `#` comments are ignored.
     * The last occurrence of a duplicated header wins.
     */
    fun parseHeaders(text: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val separator = line.indexOf(':')
            if (separator <= 0) return@forEach
            val name = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            if (name.isNotEmpty()) {
                result[name] = value
            }
        }
        return result
    }

    /**
     * Removes credentials from headers before they are written to the plain-text XML state.
     * Connection-level credentials already live in PasswordSafe.
     */
    fun withoutSecrets(text: String): String =
        text.lineSequence()
            .filterNot { line ->
                val name = line.substringBefore(':', "").trim().lowercase()
                name in sensitiveHeaders
            }
            .joinToString("\n")
            .trim()

    fun prettyJson(body: String): String {
        val trimmed = body.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return body
        val element = runCatching { JsonParser.parseString(trimmed) }.getOrNull() ?: return body
        return GsonBuilder().setPrettyPrinting().create().toJson(element)
    }

    fun isJson(body: String): Boolean {
        val trimmed = body.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return false
        return runCatching { JsonParser.parseString(trimmed) }.isSuccess
    }
}
