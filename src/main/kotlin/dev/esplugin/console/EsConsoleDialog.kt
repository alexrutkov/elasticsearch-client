package dev.esplugin.console

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import dev.esplugin.client.EsClient
import dev.esplugin.client.EsResponse
import dev.esplugin.settings.ConnectionConfig
import dev.esplugin.ui.Category
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.util.UUID
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

/**
 * Modeless REST console for one connection.
 *
 * Execute with Ctrl+Enter (or the Execute button). The console can be saved to
 * "Consoles", its body can be saved to "Queries", and every execution is added
 * to "Requests" history.
 */
class EsConsoleDialog(
    private val project: Project?,
    private val config: ConnectionConfig,
    private val secret: String?,
    initial: SavedRequest? = null,
    private var editingConsoleId: String? = null,
    private val onChanged: (Category?) -> Unit = {},
) : DialogWrapper(project, false) {

    private val store = ConsoleStore.getInstance()

    private val methodCombo = JComboBox(arrayOf("GET", "POST", "PUT", "DELETE", "HEAD", "PATCH"))
    private val pathField = JBTextField("/")
    private val headersArea = createTextArea("")
    private val bodyArea = createTextArea("")
    private val responseArea = createTextArea("").apply { isEditable = false }
    private val statusLabel = JBLabel("Ready")
    private val executeButton = JButton("Execute")
    private val saveButton = JButton("Save")
    private val saveQueryButton = JButton("Save Query")

    init {
        title = "Elasticsearch Console — ${config.name}"
        setModal(false)
        setOKButtonText("Execute")
        setCancelButtonText("Close")

        if (initial != null) {
            methodCombo.selectedItem = initial.method
            pathField.text = initial.path
            headersArea.text = initial.headers
            bodyArea.text = initial.body
        }

        init()

        executeButton.addActionListener { execute() }
        saveButton.addActionListener { saveConsole() }
        saveQueryButton.addActionListener { saveQuery() }
        rootPane.registerKeyboardAction(
            { execute() },
            KeyStroke.getKeyStroke("control ENTER"),
            JComponent.WHEN_IN_FOCUSED_WINDOW,
        )
        SwingUtilities.invokeLater { pathField.requestFocusInWindow() }
    }

    override fun createCenterPanel(): JComponent {
        val connectionLabel = JBLabel("Connection: ${config.name}  ${config.url}")
        connectionLabel.font = connectionLabel.font.deriveFont(Font.BOLD)

        val savePanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            add(saveButton)
            add(saveQueryButton)
        }
        val headerRow = JPanel(BorderLayout()).apply {
            add(connectionLabel, BorderLayout.WEST)
            add(savePanel, BorderLayout.EAST)
        }

        val requestRow = JPanel(BorderLayout(6, 0)).apply {
            add(methodCombo, BorderLayout.WEST)
            add(pathField, BorderLayout.CENTER)
            add(executeButton, BorderLayout.EAST)
        }

        val top = JPanel(BorderLayout(0, 6)).apply {
            add(headerRow, BorderLayout.NORTH)
            add(requestRow, BorderLayout.SOUTH)
        }

        val requestTabs = JTabbedPane().apply {
            addTab("Headers", JBScrollPane(headersArea))
            addTab("Body", JBScrollPane(bodyArea))
        }

        val responsePanel = JPanel(BorderLayout(0, 4)).apply {
            add(statusLabel, BorderLayout.NORTH)
            add(JBScrollPane(responseArea), BorderLayout.CENTER)
        }

        val splitter = OnePixelSplitter(true, 0.45f).apply {
            firstComponent = requestTabs
            secondComponent = responsePanel
        }

        val panel = JPanel(BorderLayout(0, 8)).apply {
            border = JBUI.Borders.empty(8)
            add(top, BorderLayout.NORTH)
            add(splitter, BorderLayout.CENTER)
        }
        panel.preferredSize = Dimension(900, 650)
        return panel
    }

    override fun doOKAction() {
        execute()
    }

    private fun execute() {
        if (!executeButton.isEnabled) return
        val method = (methodCombo.selectedItem as? String ?: "GET").uppercase()
        val path = pathField.text.trim().ifEmpty { "/" }
        val headers = ConsoleUtils.parseHeaders(headersArea.text)
        val body = bodyArea.text.takeIf { it.isNotBlank() }

        executeButton.isEnabled = false
        statusLabel.text = "Executing $method $path..."
        AppExecutorUtil.getAppExecutorService().execute {
            val result = runCatching { EsClient(config, secret).request(method, path, body, headers = headers) }
            ApplicationManager.getApplication().invokeLater({
                executeButton.isEnabled = true
                result.onSuccess { response ->
                    showResponse(response)
                }.onFailure { error ->
                    statusLabel.text = "Failed: ${error.message ?: error.javaClass.simpleName}"
                    responseArea.text = error.stackTraceToString()
                    responseArea.caretPosition = 0
                }
                store.addHistory(
                    SavedRequest(
                        connectionId = config.id,
                        name = "$method $path",
                        method = method,
                        path = path,
                        headers = ConsoleUtils.withoutSecrets(headersArea.text),
                        body = bodyArea.text,
                    ),
                )
                onChanged(Category.REQUESTS)
            }, ModalityState.any())
        }
    }

    private fun showResponse(response: EsResponse) {
        val bytes = response.body.toByteArray(Charsets.UTF_8).size
        statusLabel.text = "${response.status} ${reason(response.status)} · ${response.durationMs} ms · ${formatSize(bytes)}"
        responseArea.text = ConsoleUtils.prettyJson(response.body)
        responseArea.caretPosition = 0
    }

    private fun saveConsole() {
        val defaultName = pathField.text.trim().ifEmpty { "Console" }
        val name = Messages.showInputDialog(
            project,
            "Console name:",
            "Save Console",
            null,
            defaultName,
            null,
        )?.trim().orEmpty()
        if (name.isEmpty()) return

        val id = editingConsoleId ?: UUID.randomUUID().toString()
        store.saveConsole(
            SavedRequest(
                id = id,
                connectionId = config.id,
                name = name,
                method = (methodCombo.selectedItem as? String ?: "GET"),
                path = pathField.text.trim().ifEmpty { "/" },
                headers = ConsoleUtils.withoutSecrets(headersArea.text),
                body = bodyArea.text,
            ),
        )
        editingConsoleId = id
        title = "Elasticsearch Console — $name"
        statusLabel.text = "Saved console \"$name\""
        onChanged(Category.CONSOLES)
    }

    private fun saveQuery() {
        if (bodyArea.text.isBlank()) {
            Messages.showWarningDialog(project, "Query body is empty.", "Save Query")
            return
        }
        val defaultName = pathField.text.trim().ifEmpty { "Query" }
        val name = Messages.showInputDialog(
            project,
            "Query name:",
            "Save Query",
            null,
            defaultName,
            null,
        )?.trim().orEmpty()
        if (name.isEmpty()) return

        store.saveQuery(
            SavedQuery(
                connectionId = config.id,
                name = name,
                path = pathField.text.trim().ifEmpty { "/_search" },
                body = bodyArea.text,
            ),
        )
        statusLabel.text = "Saved query \"$name\""
        onChanged(Category.QUERIES)
    }

    private fun createTextArea(text: String): JBTextArea = JBTextArea(text).apply {
        lineWrap = true
        wrapStyleWord = true
        font = Font("JetBrains Mono", Font.PLAIN, JBUI.scale(12))
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        201 -> "Created"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        409 -> "Conflict"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        else -> ""
    }

    private fun formatSize(bytes: Int): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }
}
