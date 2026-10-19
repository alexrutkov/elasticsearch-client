package dev.esplugin.browser

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class DataBrowserDataTest {

    @Test
    fun `builds columns from mappings of all indices behind an alias`() {
        val mapping = """
            {
              "index_one": {
                "mappings": {
                  "properties": {
                    "attendanceType": {"type": "keyword"},
                    "complexName": {"type": "text"},
                    "nestedObject": {
                      "properties": {
                        "price": {"type": "double"}
                      }
                    }
                  }
                }
              },
              "index_two": {
                "mappings": {
                  "properties": {
                    "chargeTypes": {"type": "keyword"}
                  }
                }
              }
            }
        """.trimIndent()

        val columns = DataBrowserData.columnsFromMapping(mapping, emptyList())

        assertEquals(
            listOf("_id", "attendanceType", "chargeTypes", "complexName", "nestedObject", "nestedObject.price"),
            columns,
        )
    }

    @Test
    fun `falls back to source keys when mapping is empty`() {
        val hit = JsonParser.parseString(
            """{"_id":"1","_source":{"z":1,"a":2,"nested":{"b":3}}}""",
        ).asJsonObject

        assertEquals(
            listOf("_id", "a", "nested", "z"),
            DataBrowserData.columnsFromMapping("""{"index":{"mappings":{}}}""", listOf(hit)),
        )
    }

    @Test
    fun `resolves nested and literal dotted fields`() {
        val source = JsonParser.parseString(
            """{"nested":{"price":100},"a.b":2}""",
        )

        assertEquals(100, DataBrowserData.resolve(source, "nested.price")?.asInt)
        assertEquals(2, DataBrowserData.resolve(source, "a.b")?.asInt)
        assertEquals(null, DataBrowserData.resolve(source, "missing.field"))
    }

    @Test
    fun `renders arrays without quotes and objects as json`() {
        val array = JsonParser.parseString("""["paid","paid_foms"]""")
        assertEquals("[paid, paid_foms]", DataBrowserData.display(array))

        val number = JsonParser.parseString("40000")
        assertEquals("40000", DataBrowserData.display(number))

        val objectValue = JsonParser.parseString("""{"a":1}""")
        assertEquals("""{"a":1}""", DataBrowserData.display(objectValue))

        assertEquals("", DataBrowserData.display(null))
    }

    @Test
    fun `parses search response with object total`() {
        val page = DataBrowserData.parseSearch(
            """
            {
              "hits": {
                "total": {"value": 10000, "relation": "eq"},
                "hits": [
                  {"_id": "1", "_source": {"name": "first"}},
                  {"_id": "2", "_source": {"name": "second"}}
                ]
              }
            }
            """.trimIndent(),
        )

        assertNotNull(page)
        assertEquals(10000, page?.total)
        assertEquals(2, page?.hits?.size)
        assertEquals("first", page?.hits?.first()?.getAsJsonObject("_source")?.get("name")?.asString)
    }

    @Test
    fun `parses search response with numeric total`() {
        val page = DataBrowserData.parseSearch(
            """{"hits":{"total":3,"hits":[]}}""",
        )

        assertEquals(3, page?.total)
        assertEquals(0, page?.hits?.size)
    }
}
