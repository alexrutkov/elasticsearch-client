package dev.esplugin.browser

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class SearchPage(
    val hits: List<JsonObject>,
    val total: Long,
)

object DataBrowserData {

    fun parseSearch(body: String): SearchPage? = runCatching {
        val hitsObject = JsonParser.parseString(body).asJsonObject.getAsJsonObject("hits")
        val totalElement = hitsObject.get("total")
        val total = when {
            totalElement == null || totalElement.isJsonNull -> 0L
            totalElement.isJsonObject -> totalElement.asJsonObject.get("value")?.asLong ?: 0L
            else -> totalElement.asLong
        }
        val documents = hitsObject.getAsJsonArray("hits")
            ?.mapNotNull { if (it.isJsonObject) it.asJsonObject else null }
            ?: emptyList()
        SearchPage(documents, total)
    }.getOrNull()

    /**
     * Builds columns from the mapping of every index behind the alias, so fields that
     * are absent from the current page still get a column.
     */
    fun columnsFromMapping(body: String, documents: List<JsonObject>): List<String> {
        val fields = LinkedHashSet<String>()
        runCatching {
            val root = JsonParser.parseString(body).asJsonObject
            root.entrySet().forEach { (_, value) ->
                val mapping = value.asJsonObject.getAsJsonObject("mappings") ?: return@forEach
                val properties = mapping.getAsJsonObject("properties") ?: return@forEach
                collectProperties(properties, "", fields)
            }
        }
        if (fields.isEmpty()) return columnsFromHits(documents)
        return listOf("_id") + fields.sorted()
    }

    fun columnsFromHits(documents: List<JsonObject>): List<String> {
        val fields = LinkedHashSet<String>()
        documents.forEach { hit ->
            hit.getAsJsonObject("_source")?.keySet()?.let(fields::addAll)
        }
        return listOf("_id") + fields.sorted()
    }

    fun resolve(source: JsonElement?, path: String): JsonElement? {
        if (source == null || !source.isJsonObject) return null
        val objectSource = source.asJsonObject
        objectSource.get(path)?.let { return it }

        var current: JsonElement = objectSource
        for (part in path.split('.')) {
            if (!current.isJsonObject) return null
            current = current.asJsonObject.get(part) ?: return null
        }
        return current
    }

    fun display(value: JsonElement?): String = when {
        value == null || value.isJsonNull -> ""
        value.isJsonPrimitive -> value.asString
        value.isJsonArray -> value.asJsonArray.joinToString(", ", "[", "]") { display(it) }
        value.isJsonObject -> Gson().toJson(value)
        else -> value.toString()
    }

    private fun collectProperties(
        properties: JsonObject,
        prefix: String,
        out: MutableSet<String>,
    ) {
        properties.entrySet().forEach { (name, value) ->
            val path = if (prefix.isEmpty()) name else "$prefix.$name"
            out.add(path)
            val child = value.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            child.getAsJsonObject("properties")?.let { collectProperties(it, path, out) }
        }
    }
}
