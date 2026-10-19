package dev.esplugin.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
@State(name = "ElasticsearchClient", storages = [Storage("elasticsearch-client.xml")])
class ConnectionManager : PersistentStateComponent<ConnectionManager.State> {

    class State {
        var connections: MutableList<ConnectionConfig> = mutableListOf()
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    val connections: List<ConnectionConfig>
        get() = myState.connections

    fun add(config: ConnectionConfig) {
        myState.connections.add(config)
    }

    fun update(config: ConnectionConfig) {
        val index = myState.connections.indexOfFirst { it.id == config.id }
        if (index >= 0) {
            myState.connections[index] = config
        } else {
            myState.connections.add(config)
        }
    }

    fun remove(config: ConnectionConfig) {
        myState.connections.removeAll { it.id == config.id }
    }

    fun find(id: String): ConnectionConfig? = myState.connections.firstOrNull { it.id == id }

    companion object {
        fun getInstance(): ConnectionManager = service()
    }
}
