package dev.esplugin.ui

import dev.esplugin.settings.ConnectionConfig
import javax.swing.tree.DefaultMutableTreeNode

class RootNode : DefaultMutableTreeNode("root")

class ConfigNode(val config: ConnectionConfig) : DefaultMutableTreeNode(config) {
    var health: String? = null
    var healthLoading: Boolean = false
}

enum class Category(val title: String) {
    CONSOLES("Consoles"),
    REQUESTS("Requests"),
    QUERIES("Queries"),
    INDICES("indices"),
    ALIASES("aliases"),
    DATA_STREAMS("data streams"),
    NODES("nodes");

    val isLocal: Boolean
        get() = this == CONSOLES || this == REQUESTS || this == QUERIES
}

enum class ItemKind {
    PLAIN,
    CONSOLE,
    HISTORY,
    QUERY,
}

class CategoryNode(
    val config: ConnectionConfig,
    val category: Category,
) : DefaultMutableTreeNode(category.title) {
    var loaded: Boolean = false
    var loading: Boolean = false
}

class ItemNode(
    val title: String,
    val details: String? = null,
    val kind: ItemKind = ItemKind.PLAIN,
    val payloadId: String? = null,
) : DefaultMutableTreeNode(title)

class InfoNode(
    val info: String,
    val error: Boolean = false,
) : DefaultMutableTreeNode(info)
