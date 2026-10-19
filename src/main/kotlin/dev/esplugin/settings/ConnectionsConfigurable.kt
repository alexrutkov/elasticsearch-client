package dev.esplugin.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.Messages
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel

class ConnectionsConfigurable : Configurable {

    private val manager = ConnectionManager.getInstance()
    private val listModel = CollectionListModel<ConnectionConfig>()
    private val list = JBList(listModel)
    private var panel: JPanel? = null

    override fun getDisplayName(): String = "Elasticsearch Client"

    override fun createComponent(): JComponent {
        list.cellRenderer = object : ColoredListCellRenderer<ConnectionConfig>() {
            override fun customizeCellRenderer(
                list: JList<out ConnectionConfig>,
                value: ConnectionConfig,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                append(value.name.ifBlank { "Unnamed" }, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                append("  ${value.url}", SimpleTextAttributes.GRAY_ATTRIBUTES)
            }
        }

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(JButton("Add...").apply { addActionListener { addConnection() } })
            add(JButton("Edit...").apply { addActionListener { editConnection() } })
            add(JButton("Remove").apply { addActionListener { removeConnection() } })
        }

        val result = JPanel(BorderLayout(0, 6)).apply {
            add(JBScrollPane(list), BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
        }
        panel = result
        reload()
        return result
    }

    override fun isModified(): Boolean = false

    override fun apply() = Unit

    override fun reset() {
        reload()
    }

    override fun disposeUIResources() {
        panel = null
    }

    private fun reload() {
        listModel.removeAll()
        manager.connections.forEach { listModel.add(it) }
    }

    private fun addConnection() {
        val dialog = ConnectionDialog(null, ConnectionConfig(), isNew = true)
        if (!dialog.showAndGet()) return

        val config = dialog.resultConfig
        manager.add(config)
        if (dialog.resultSecret.isNotEmpty()) {
            SecretStore.set(config.id, dialog.resultSecret)
        }
        reload()
    }

    private fun editConnection() {
        val selected = list.selectedValue ?: return
        val dialog = ConnectionDialog(null, selected.copy(), isNew = false)
        if (!dialog.showAndGet()) return

        manager.update(dialog.resultConfig)
        if (dialog.resultSecret.isNotEmpty()) {
            SecretStore.set(dialog.resultConfig.id, dialog.resultSecret)
        }
        reload()
    }

    private fun removeConnection() {
        val selected = list.selectedValue ?: return
        val answer = Messages.showYesNoDialog(
            "Remove connection \"${selected.name}\"?",
            "Remove Connection",
            Messages.getQuestionIcon(),
        )
        if (answer != Messages.YES) return

        manager.remove(selected)
        SecretStore.set(selected.id, null)
        reload()
    }
}
