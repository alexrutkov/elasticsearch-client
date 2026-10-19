package dev.esplugin.ui

import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.concurrency.AppExecutorUtil
import dev.esplugin.client.EsClient
import dev.esplugin.settings.ConnectionConfig
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.Action
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

class ManageAliasesDialog(
    private val project: Project?,
    private val config: ConnectionConfig,
    private val secret: String?,
    private val index: String,
    private val onChanged: () -> Unit,
) : DialogWrapper(project) {

    private val listModel = DefaultListModel<String>()
    private val list = JList(listModel)
    private val statusLabel = JBLabel("Ready")
    private val addButton = JButton("Add Alias...")
    private val removeButton = JButton("Remove Selected")

    init {
        title = "Manage Aliases — $index"
        setOKButtonText("Close")
        init()
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION

        addButton.addActionListener { addAlias() }
        removeButton.addActionListener { removeAlias() }
        loadAliases()
    }

    override fun createCenterPanel(): JComponent {
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(addButton)
            add(removeButton)
        }
        val bottom = JPanel(BorderLayout(0, 6)).apply {
            add(buttons, BorderLayout.NORTH)
            add(statusLabel, BorderLayout.SOUTH)
        }

        val panel = JPanel(BorderLayout(0, 6)).apply {
            add(JBScrollPane(list), BorderLayout.CENTER)
            add(bottom, BorderLayout.SOUTH)
        }
        panel.preferredSize = Dimension(500, 380)
        return panel
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    private fun loadAliases() {
        statusLabel.text = "Loading..."
        AppExecutorUtil.getAppExecutorService().execute {
            val result = runCatching { EsClient(config, secret).get("/$index/_alias") }
            ApplicationManager.getApplication().invokeLater({
                result.onSuccess { response ->
                    if (!response.isSuccess) {
                        statusLabel.text = "HTTP ${response.status}: ${response.body.take(200)}"
                        return@onSuccess
                    }
                    val root = runCatching { JsonParser.parseString(response.body).asJsonObject }.getOrNull()
                    val aliases = LinkedHashSet<String>()
                    root?.entrySet()?.forEach { (_, value) ->
                        value.asJsonObject.getAsJsonObject("aliases")?.keySet()?.let(aliases::addAll)
                    }
                    listModel.clear()
                    aliases.sorted().forEach(listModel::addElement)
                    statusLabel.text = "${aliases.size} alias(es)"
                }.onFailure { error ->
                    statusLabel.text = "Failed: ${error.message ?: error.javaClass.simpleName}"
                }
            }, ModalityState.any())
        }
    }

    private fun addAlias() {
        val alias = Messages.showInputDialog(
            project,
            "Alias name:",
            "Add Alias",
            null,
            "",
            null,
        )?.trim().orEmpty()
        if (alias.isEmpty()) return
        mutate("PUT", "/$index/_alias/$alias", "{}")
    }

    private fun removeAlias() {
        val alias = list.selectedValue ?: return
        val answer = Messages.showYesNoDialog(
            project,
            "Remove alias \"$alias\" from \"$index\"?",
            "Remove Alias",
            Messages.getQuestionIcon(),
        )
        if (answer != Messages.YES) return
        mutate("DELETE", "/$index/_alias/$alias", null)
    }

    private fun mutate(method: String, path: String, body: String?) {
        statusLabel.text = "Applying..."
        AppExecutorUtil.getAppExecutorService().execute {
            val result = runCatching { EsClient(config, secret).request(method, path, body) }
            ApplicationManager.getApplication().invokeLater({
                result.onSuccess { response ->
                    if (response.isSuccess) {
                        statusLabel.text = "Done"
                        onChanged()
                        loadAliases()
                    } else {
                        statusLabel.text = "HTTP ${response.status}: ${response.body.take(200)}"
                    }
                }.onFailure { error ->
                    statusLabel.text = "Failed: ${error.message ?: error.javaClass.simpleName}"
                }
            }, ModalityState.any())
        }
    }
}
