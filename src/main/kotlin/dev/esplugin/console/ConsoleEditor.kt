package dev.esplugin.console

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import dev.esplugin.client.EsClient
import dev.esplugin.client.EsResponse
import dev.esplugin.settings.ConnectionManager
import dev.esplugin.settings.SecretStore
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.beans.PropertyChangeListener
import java.util.Locale
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke

class ConsoleEditor(
    private val project: Project,
    private val file: ConsoleVirtualFile,
) : UserDataHolderBase(), FileEditor {

    private val document = EditorFactory.getInstance()
        .createDocument(ConsoleRequestParser.format(file.initial))
    private val requestEditor: Editor = EditorFactory.getInstance()
        .createEditor(document, project, PlainTextFileType.INSTANCE, false)
    private val responseDocument = EditorFactory.getInstance()
        .createDocument("Response will appear here")
    private val responseEditor: Editor = EditorFactory.getInstance()
        .createEditor(responseDocument, project, PlainTextFileType.INSTANCE, true)
    private val statusLabel = JBLabel("Ready")
    private val executeButton = JButton("Execute")
    private val formatButton = JButton("Format")
    private val saveButton = JButton("Save")
    private val saveQueryButton = JButton("Save Query")
    private val splitter = OnePixelSplitter(false, 0.45f)
    private val rootPanel = JPanel(BorderLayout(0, 4))

    private var executing = false
    private var disposed = false

    init {
        installHighlighter()
        requestEditor.settings.isLineNumbersShown = true
        requestEditor.settings.isIndentGuidesShown = true
        responseEditor.settings.isLineNumbersShown = true
        responseEditor.settings.isIndentGuidesShown = true
        responseEditor.settings.isUseSoftWraps = false
        installEnterHandler()

        rootPanel.registerKeyboardAction(
            object : AbstractAction() {
                override fun actionPerformed(event: ActionEvent) {
                    execute()
                }
            },
            KeyStroke.getKeyStroke("control ENTER"),
            JComponent.WHEN_IN_FOCUSED_WINDOW,
        )

        executeButton.addActionListener { execute() }
        formatButton.addActionListener { formatRequest() }
        saveButton.addActionListener { saveConsole() }
        saveQueryButton.addActionListener { saveQuery() }

        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(executeButton)
            add(formatButton)
            add(saveButton)
            add(saveQueryButton)
            add(statusLabel)
        }
        val requestPanel = JPanel(BorderLayout()).apply {
            add(requestEditor.component, BorderLayout.CENTER)
        }
        val responsePanel = JPanel(BorderLayout()).apply {
            add(responseEditor.component, BorderLayout.CENTER)
        }

        splitter.firstComponent = requestPanel
        splitter.secondComponent = responsePanel

        rootPanel.border = JBUI.Borders.empty(8)
        rootPanel.add(toolbar, BorderLayout.NORTH)
        rootPanel.add(splitter, BorderLayout.CENTER)
        rootPanel.preferredSize = Dimension(1100, 650)
    }

    private fun installHighlighter() {
        val highlighter = EditorHighlighterFactory.getInstance()
            .createEditorHighlighter(ConsoleSyntaxHighlighter(), requestEditor.colorsScheme)
        (requestEditor as EditorEx).setHighlighter(highlighter)
    }

    private fun installEnterHandler() {
        val enter = object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) {
                insertNewlineWithIndent()
            }
        }
        requestEditor.contentComponent.inputMap.put(KeyStroke.getKeyStroke("ENTER"), "es-console-enter")
        requestEditor.contentComponent.actionMap.put("es-console-enter", enter)
    }

    private fun insertNewlineWithIndent() {
        val caret = requestEditor.caretModel.offset
        val line = document.getLineNumber(caret)
        val lineStart = document.getLineStartOffset(line)
        val before = document.getText(TextRange(lineStart, caret))
        val indent = before.takeWhile { it == ' ' || it == '\t' }
        val trimmed = before.trimEnd()
        val next = if (caret < document.textLength) document.charsSequence[caret] else null

        val extra = when {
            trimmed.endsWith("{") || trimmed.endsWith("[") -> 2
            next == '}' || next == ']' -> -2
            else -> 0
        }
        val newIndent = (indent.length + extra).coerceAtLeast(0)
        val inserted = "\n" + " ".repeat(newIndent)

        ApplicationManager.getApplication().runWriteAction {
            document.insertString(caret, inserted)
            requestEditor.caretModel.moveToOffset(caret + inserted.length)
        }
    }

    private fun formatRequest() {
        val request = ConsoleRequestParser.parse(document.text)
        if (request == null) {
            statusLabel.text = "Cannot parse request"
            return
        }
        if (!ConsoleRequestParser.isJson(request.body)) {
            statusLabel.text = "Body is not valid JSON"
            return
        }

        val formatted = ConsoleRequestParser.format(
            request.copy(body = ConsoleRequestParser.prettyBody(request.body)),
        )
        document.setText(formatted)
        requestEditor.caretModel.moveToOffset(0)
        statusLabel.text = "Formatted"
    }

    private fun execute() {
        if (executing || disposed) return

        val request = ConsoleRequestParser.parse(document.text)
        if (request == null) {
            statusLabel.text = "Cannot parse request: expected METHOD /path"
            return
        }
        val config = ConnectionManager.getInstance().find(file.connectionId)
        if (config == null) {
            statusLabel.text = "Connection not found"
            return
        }

        executing = true
        executeButton.isEnabled = false
        statusLabel.text = "Executing..."

        val secret = SecretStore.get(config.id)
        val started = System.currentTimeMillis()
        val history = SavedRequest(
            connectionId = config.id,
            name = "${request.method} ${request.path}",
            method = request.method,
            path = request.path,
            headers = ConsoleUtils.withoutSecrets(request.headersText),
            body = request.body,
        )

        AppExecutorUtil.getAppExecutorService().execute {
            val result = runCatching {
                EsClient(config, secret).request(
                    method = request.method,
                    path = request.path,
                    body = request.body.takeIf { it.isNotBlank() },
                    headers = request.headers,
                )
            }
            val elapsed = System.currentTimeMillis() - started

            ApplicationManager.getApplication().invokeLater({
                if (disposed) return@invokeLater
                executing = false
                executeButton.isEnabled = true

                ConsoleStore.getInstance().addHistory(history)
                ConsoleNotifier.notifyChanged(config.id)

                result.onSuccess { response ->
                    showResponse(response, elapsed)
                }.onFailure { error ->
                    setResponseText(error.stackTraceToString())
                    statusLabel.text = "Failed: ${error.message ?: error.javaClass.simpleName}"
                }
            }, ModalityState.any())
        }
    }

    private fun showResponse(response: EsResponse, elapsed: Long) {
        setResponseText(ConsoleUtils.prettyJson(response.body).ifBlank { "<empty response>" })
        val size = response.body.toByteArray(Charsets.UTF_8).size
        statusLabel.text = "${response.status} ${reason(response.status)} · $elapsed ms · ${formatSize(size)}"
    }

    private fun setResponseText(text: String) {
        ApplicationManager.getApplication().runWriteAction {
            responseDocument.setText(text)
        }
        responseEditor.caretModel.moveToOffset(0)
        responseEditor.scrollingModel.scrollVertically(0)
    }

    private fun saveConsole() {
        val request = ConsoleRequestParser.parse(document.text) ?: run {
            statusLabel.text = "Cannot parse request"
            return
        }
        val store = ConsoleStore.getInstance()
        val existing = file.editingConsoleId?.let { store.findConsole(it) }
        val defaultName = existing?.name ?: "${request.method} ${request.path}"
        val name = Messages.showInputDialog(
            project,
            "Console name:",
            "Save Console",
            null,
            defaultName,
            null,
        )?.trim().orEmpty()
        if (name.isEmpty()) return

        val saved = existing?.copy(
            name = name,
            connectionId = file.connectionId,
            method = request.method,
            path = request.path,
            headers = ConsoleUtils.withoutSecrets(request.headersText),
            body = request.body,
            timestamp = System.currentTimeMillis(),
        ) ?: SavedRequest(
            connectionId = file.connectionId,
            name = name,
            method = request.method,
            path = request.path,
            headers = ConsoleUtils.withoutSecrets(request.headersText),
            body = request.body,
        )

        store.saveConsole(saved)
        file.editingConsoleId = saved.id
        ConsoleNotifier.notifyChanged(file.connectionId)
        statusLabel.text = "Console \"$name\" saved"
    }

    private fun saveQuery() {
        val request = ConsoleRequestParser.parse(document.text) ?: run {
            statusLabel.text = "Cannot parse request"
            return
        }
        if (request.body.isBlank()) {
            statusLabel.text = "Body is empty: nothing to save as a query"
            return
        }

        val name = Messages.showInputDialog(
            project,
            "Query name:",
            "Save Query",
            null,
            "${request.method} ${request.path}",
            null,
        )?.trim().orEmpty()
        if (name.isEmpty()) return

        ConsoleStore.getInstance().saveQuery(
            SavedQuery(
                connectionId = file.connectionId,
                name = name,
                path = request.path,
                body = request.body,
            ),
        )
        ConsoleNotifier.notifyChanged(file.connectionId)
        statusLabel.text = "Query \"$name\" saved"
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        201 -> "Created"
        202 -> "Accepted"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        409 -> "Conflict"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        else -> ""
    }

    private fun formatSize(bytes: Int): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    override fun getComponent(): JComponent = rootPanel

    override fun getFile(): VirtualFile = file

    override fun getPreferredFocusedComponent(): JComponent = requestEditor.contentComponent

    override fun getName(): String = "Console"

    override fun setState(state: FileEditorState) = Unit

    override fun getState(level: FileEditorStateLevel): FileEditorState = FileEditorState.INSTANCE

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = !disposed

    override fun selectNotify() = Unit

    override fun deselectNotify() = Unit

    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun dispose() {
        if (disposed) return
        disposed = true
        EditorFactory.getInstance().releaseEditor(requestEditor)
        EditorFactory.getInstance().releaseEditor(responseEditor)
    }
}
