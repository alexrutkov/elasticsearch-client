package dev.esplugin.browser

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.esplugin.client.EsClient
import dev.esplugin.console.ConsoleTabs
import dev.esplugin.console.SavedRequest
import dev.esplugin.settings.ConnectionManager
import dev.esplugin.settings.SecretStore
import dev.esplugin.ui.JsonViewerDialog
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.beans.PropertyChangeListener
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableRowSorter

/**
 * Editor tab that shows documents of an index or alias as a table.
 * Columns are derived from the index mapping, so every mapped field is visible.
 */
class DataBrowserEditor(
    private val project: Project,
    private val file: DataBrowserVirtualFile,
) : UserDataHolderBase(), FileEditor {

    private val pageSize = 50
    private var page = 0
    private var total = 0L
    private var loading = false
    private var disposed = false
    private var columns: List<String> = emptyList()
    private var hits: List<JsonObject> = emptyList()

    private val tableModel = DataTableModel()
    private val table = JBTable(tableModel)
    private val filterField = JBTextField()
    private val statusLabel = JBLabel("0 of 0")
    private val searchButton = JButton("Search")
    private val refreshButton = JButton("Refresh")
    private val prevButton = JButton("Prev")
    private val nextButton = JButton("Next")
    private val consoleButton = JButton("Open in Console")
    private val rootPanel = JPanel(BorderLayout(0, 4))

    init {
        buildUi()
        loadPage(0, true)
    }

    private fun buildUi() {
        table.autoResizeMode = JTable.AUTO_RESIZE_OFF
        table.rowHeight = 22
        table.setShowGrid(true)
        table.rowSorter = TableRowSorter(tableModel)
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2 && table.selectedRow >= 0) {
                    val modelRow = table.convertRowIndexToModel(table.selectedRow)
                    val hit = hits.getOrNull(modelRow) ?: return
                    JsonViewerDialog(
                        project,
                        "Document — ${file.index}",
                        GsonBuilderHolder.pretty.toJson(hit),
                    ).show()
                }
            }
        })

        filterField.columns = 32
        filterField.toolTipText = "Lucene query, for example: status:active"
        filterField.addActionListener { loadPage(0, false) }
        searchButton.addActionListener { loadPage(0, false) }
        refreshButton.addActionListener { loadPage(page, true) }
        prevButton.addActionListener { loadPage((page - 1).coerceAtLeast(0), false) }
        nextButton.addActionListener { loadPage(page + 1, false) }
        consoleButton.addActionListener { openConsole() }
        prevButton.isEnabled = false
        nextButton.isEnabled = false

        val filterRow = JPanel(BorderLayout(6, 0)).apply {
            add(JBLabel("Filter:"), BorderLayout.WEST)
            add(filterField, BorderLayout.CENTER)
            add(searchButton, BorderLayout.EAST)
        }
        val navigation = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(prevButton)
            add(nextButton)
            add(refreshButton)
            add(consoleButton)
            add(statusLabel)
        }

        val tableScroll = JBScrollPane(table)
        tableScroll.setRowHeaderView(RowNumberTable(table))
        tableScroll.rowHeader.background = UIUtil.getTableBackground()
        tableScroll.rowHeader.border = JBUI.Borders.customLineRight(JBColor.border())

        rootPanel.border = JBUI.Borders.empty(8)
        rootPanel.add(filterRow, BorderLayout.NORTH)
        rootPanel.add(tableScroll, BorderLayout.CENTER)
        rootPanel.add(navigation, BorderLayout.SOUTH)
        rootPanel.preferredSize = Dimension(1100, 650)
    }

    private fun loadPage(target: Int, reloadMapping: Boolean) {
        if (disposed || loading) return

        val config = ConnectionManager.getInstance().find(file.connectionId)
        if (config == null) {
            statusLabel.text = "Connection not found"
            return
        }

        loading = true
        setButtonsEnabled(false)
        statusLabel.text = "Loading..."

        val query = mutableMapOf(
            "size" to pageSize.toString(),
            "from" to (target * pageSize).toString(),
            "sort" to "_doc",
        )
        filterField.text.trim().takeIf { it.isNotEmpty() }?.let { query["q"] = it }

        val secret = SecretStore.get(config.id)
        AppExecutorUtil.getAppExecutorService().execute {
            val client = EsClient(config, secret)
            val mappingBody = if (reloadMapping || columns.isEmpty()) {
                runCatching { client.get("/${file.index}/_mapping") }
                    .getOrNull()
                    ?.takeIf { it.isSuccess }
                    ?.body
            } else {
                null
            }
            val search = runCatching { client.get("/${file.index}/_search", query) }

            ApplicationManager.getApplication().invokeLater({
                if (disposed) return@invokeLater
                loading = false
                setButtonsEnabled(true)

                search.onSuccess { response ->
                    if (!response.isSuccess) {
                        statusLabel.text = "HTTP ${response.status}: ${response.body.take(200)}"
                        return@onSuccess
                    }
                    val parsed = DataBrowserData.parseSearch(response.body)
                    if (parsed == null) {
                        statusLabel.text = "Unexpected response (not a search result)"
                        return@onSuccess
                    }

                    columns = if (mappingBody != null) {
                        DataBrowserData.columnsFromMapping(mappingBody, parsed.hits)
                    } else {
                        DataBrowserData.columnsFromHits(parsed.hits)
                    }
                    hits = parsed.hits
                    total = parsed.total
                    page = target

                    tableModel.setData(columns, hits)
                    applyColumnWidths()
                    prevButton.isEnabled = page > 0
                    nextButton.isEnabled = (page + 1L) * pageSize < total
                    statusLabel.text = if (total == 0L) {
                        "0 of 0"
                    } else {
                        "${page * pageSize + 1}–${page * pageSize + hits.size} of $total"
                    }
                }.onFailure { error ->
                    statusLabel.text = "Failed: ${error.message ?: error.javaClass.simpleName}"
                }
            }, ModalityState.any())
        }
    }

    private fun parseSearch(body: String): SearchPage? = DataBrowserData.parseSearch(body)

    private fun columnsFromMapping(body: String, documents: List<JsonObject>): List<String> =
        DataBrowserData.columnsFromMapping(body, documents)

    private fun columnsFromHits(documents: List<JsonObject>): List<String> =
        DataBrowserData.columnsFromHits(documents)

    private fun applyColumnWidths() {
        for (index in columns.indices) {
            if (index >= table.columnCount) continue
            val column = columns[index]
            val width = when {
                column == "_id" -> 260
                column.contains("name", ignoreCase = true) -> 240
                else -> 180
            }
            table.columnModel.getColumn(index).preferredWidth = width
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        searchButton.isEnabled = enabled
        refreshButton.isEnabled = enabled
        consoleButton.isEnabled = enabled
        prevButton.isEnabled = enabled && page > 0
        nextButton.isEnabled = enabled && (page + 1L) * pageSize < total
    }

    private fun openConsole() {
        val config = ConnectionManager.getInstance().find(file.connectionId) ?: return
        ConsoleTabs.open(
            project,
            config,
            SavedRequest(
                connectionId = config.id,
                name = file.index,
                method = "GET",
                path = "/${file.index}/_search",
            ),
            title = file.index,
            reuseKey = "index:${file.index}",
        )
    }

    override fun getComponent(): JComponent = rootPanel

    override fun getPreferredFocusedComponent(): JComponent = filterField

    override fun getName(): String = "Data Browser"

    override fun setState(state: FileEditorState) = Unit

    override fun getState(level: FileEditorStateLevel): FileEditorState = FileEditorState.INSTANCE

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = !disposed

    override fun selectNotify() = Unit

    override fun deselectNotify() = Unit

    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun dispose() {
        disposed = true
    }

    private inner class DataTableModel : AbstractTableModel() {
        private var tableColumns: List<String> = emptyList()
        private var tableRows: List<JsonObject> = emptyList()

        fun setData(newColumns: List<String>, newRows: List<JsonObject>) {
            tableColumns = newColumns
            tableRows = newRows
            fireTableStructureChanged()
        }

        override fun getRowCount(): Int = tableRows.size

        override fun getColumnCount(): Int = tableColumns.size

        override fun getColumnName(column: Int): String = tableColumns.getOrElse(column) { "" }

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val hit = tableRows.getOrNull(rowIndex) ?: return ""
            val column = tableColumns.getOrElse(columnIndex) { return "" }
            if (column == "_id") {
                return hit.get("_id")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
            }
            return DataBrowserData.display(DataBrowserData.resolve(hit.get("_source"), column))
        }

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false
    }
}

private object GsonBuilderHolder {
    val pretty = com.google.gson.GsonBuilder().setPrettyPrinting().create()
}
