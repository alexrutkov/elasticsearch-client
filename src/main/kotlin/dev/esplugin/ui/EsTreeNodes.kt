package dev.esplugin.ui

import dev.esplugin.settings.ConnectionConfig
import javax.swing.tree.DefaultMutableTreeNode

class RootNode : DefaultMutableTreeNode("root")

class ConfigNode(val config: ConnectionConfig) : DefaultMutableTreeNode(config) {
    var health: String? = null
    var healthLoading: Boolean = false
}

enum class Category(val title: String) {
    INDICES("Indices"),
    ALIASES("Aliases"),
    DATA_STREAMS("Data streams"),
    NODES("Nodes"),
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
) : DefaultMutableTreeNode(title)

class InfoNode(
    val info: String,
    val error: Boolean = false,
) : DefaultMutableTreeNode(info)
