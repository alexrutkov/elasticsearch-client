package dev.esplugin.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import java.awt.Color
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

class EsTreeRenderer : ColoredTreeCellRenderer() {

    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        icon = null
        when (val node = value as? DefaultMutableTreeNode) {
            is ConfigNode -> {
                icon = AllIcons.Nodes.Folder
                append(node.config.name.ifBlank { "Unnamed connection" }, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                node.health?.let { health ->
                    append("  $health", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, healthColor(health)))
                }
                append("  ${node.config.url}", SimpleTextAttributes.GRAY_ATTRIBUTES)
            }

            is CategoryNode -> {
                icon = AllIcons.Nodes.Folder
                append(node.category.title, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            }

            is ItemNode -> {
                append(node.title, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                node.details?.takeIf { it.isNotBlank() }?.let {
                    append("  $it", SimpleTextAttributes.GRAY_ATTRIBUTES)
                }
            }

            is InfoNode -> {
                icon = if (node.error) AllIcons.General.Error else AllIcons.General.Information
                append(
                    node.info,
                    if (node.error) SimpleTextAttributes.ERROR_ATTRIBUTES else SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES,
                )
            }
        }
    }

    private fun healthColor(health: String): Color = when (health.lowercase()) {
        "green" -> Color(0x2E7D32)
        "yellow" -> Color(0xB28704)
        "red" -> Color(0xC62828)
        else -> Color(0x808080)
    }
}
