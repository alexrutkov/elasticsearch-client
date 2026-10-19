package dev.esplugin.console

import com.intellij.util.xmlb.XmlSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class ConsoleStoreTest {

    @Test
    fun `state beans have no-arg constructors for the xml serializer`() {
        assertNotNull(SavedRequest::class.java.getDeclaredConstructor())
        assertNotNull(SavedQuery::class.java.getDeclaredConstructor())
        assertNotNull(ConsoleStore.State::class.java.getDeclaredConstructor())
    }

    @Test
    fun `state survives xml serialization`() {
        val state = ConsoleStore.State()
        state.consoles.add(
            SavedRequest(
                connectionId = "c1",
                name = "Test console",
                method = "POST",
                path = "/_search",
                headers = "X-Test: 1",
                body = """{"query":{"match_all":{}}}""",
            ),
        )
        state.history.add(SavedRequest(connectionId = "c1", name = "GET /", method = "GET", path = "/"))
        state.queries.add(SavedQuery(connectionId = "c1", name = "All", path = "/_search", body = "{}"))

        val element = XmlSerializer.serialize(state)
        val restored = XmlSerializer.deserialize(element, ConsoleStore.State::class.java)

        assertEquals(1, restored.consoles.size)
        assertEquals("Test console", restored.consoles[0].name)
        assertEquals("POST", restored.consoles[0].method)
        assertEquals("/_search", restored.consoles[0].path)
        assertEquals(1, restored.history.size)
        assertEquals(1, restored.queries.size)
        assertEquals("All", restored.queries[0].name)
    }
}
