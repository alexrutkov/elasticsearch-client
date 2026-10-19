package dev.esplugin.console

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import java.util.UUID

/**
 * Application-level storage for saved consoles, request history and saved queries.
 * Every mutating method is expected to be called from the EDT.
 */
@Service(Service.Level.APP)
@State(name = "ElasticsearchConsoleStore", storages = [Storage("elasticsearch-console.xml")])
class ConsoleStore : PersistentStateComponent<ConsoleStore.State> {

    class State {
        var consoles: MutableList<SavedRequest> = mutableListOf()
        var history: MutableList<SavedRequest> = mutableListOf()
        var queries: MutableList<SavedQuery> = mutableListOf()
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    fun consoles(connectionId: String): List<SavedRequest> =
        myState.consoles
            .filter { it.connectionId == connectionId }
            .sortedBy { it.name.lowercase() }

    fun history(connectionId: String): List<SavedRequest> =
        myState.history
            .filter { it.connectionId == connectionId }
            .sortedByDescending { it.timestamp }

    fun queries(connectionId: String): List<SavedQuery> =
        myState.queries
            .filter { it.connectionId == connectionId }
            .sortedBy { it.name.lowercase() }

    fun findConsole(id: String): SavedRequest? = myState.consoles.firstOrNull { it.id == id }

    fun findHistory(id: String): SavedRequest? = myState.history.firstOrNull { it.id == id }

    fun findQuery(id: String): SavedQuery? = myState.queries.firstOrNull { it.id == id }

    fun saveConsole(request: SavedRequest) {
        val index = myState.consoles.indexOfFirst { it.id == request.id }
        if (index >= 0) {
            myState.consoles[index] = request
        } else {
            myState.consoles.add(request)
        }
    }

    fun saveQuery(query: SavedQuery) {
        val index = myState.queries.indexOfFirst { it.id == query.id }
        if (index >= 0) {
            myState.queries[index] = query
        } else {
            myState.queries.add(query)
        }
    }

    fun addHistory(request: SavedRequest) {
        val entry = request.copy(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
        )
        myState.history.add(0, entry)

        val forConnection = myState.history.filter { it.connectionId == entry.connectionId }
        if (forConnection.size > MAX_HISTORY_PER_CONNECTION) {
            myState.history.removeAll(forConnection.drop(MAX_HISTORY_PER_CONNECTION).toSet())
        }
    }

    fun deleteConsole(id: String) {
        myState.consoles.removeAll { it.id == id }
    }

    fun deleteHistory(id: String) {
        myState.history.removeAll { it.id == id }
    }

    fun deleteQuery(id: String) {
        myState.queries.removeAll { it.id == id }
    }

    fun clearHistory(connectionId: String) {
        myState.history.removeAll { it.connectionId == connectionId }
    }

    fun removeConnection(connectionId: String) {
        myState.consoles.removeAll { it.connectionId == connectionId }
        myState.history.removeAll { it.connectionId == connectionId }
        myState.queries.removeAll { it.connectionId == connectionId }
    }

    companion object {
        private const val MAX_HISTORY_PER_CONNECTION = 100

        fun getInstance(): ConsoleStore = service()
    }
}
