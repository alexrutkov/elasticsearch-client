package dev.esplugin.ui

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.concurrency.AppExecutorUtil
import dev.esplugin.client.EsClient
import dev.esplugin.console.EsConsoleDialog
import dev.esplugin.console.SavedRequest
import dev.esplugin.settings.ConnectionConfig
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

/**
 * Read-only data browser: the first 50 documents of an index or alias,
 * with a Lucene query filter and simple pagination.
 */
class DataBrowserDialog(
    private val project: Project?,
    private val config: ConnectionConfig,
    private val secret: String?,
    private val index: String,
) : DialogWrapper(project, false) {

    private val pageSize = 50
    private var page = 0
    private var total = 0L
    private var loading = false
    private var hits: List<JsonObject> = emptyList()

    private val filterField = JBTextField()
    private val statusLabel = JBLabel("Ready")
    private val searchButton = JButton("Search")
    private val refreshButton = JButton("Refresh")
    private val prevButton = JButton("Prev")
    private val nextButton = JButton("Next")
    private val consoleButton = JButton("Open in Console")

    private val model = object : AbstractTableModel() {
        override fun getRowCount(): Int = hits.size

        override fun getColumnCount(): Int = 3

        override fun getColumnName(column: Int): String = when (column) {
            0 -> "_id"
            1 -> "_score"
            else -> "_source"
        }

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val hit = hits[rowIndex]
            return when (columnIndex) {
                0 -> hit.get("_id")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                1 -> hit.get("_score")?.takeIf { !it.isJsonNull }?.asDouble ?: ""
                else -> hit.get("_source")?.let { Gson().toJson(it) }.orEmpty()
            }
        }
    }

    private val table = JBTable(model)

    init {
        title = "Data Browser — $index"
        setModal(false)
        setOKButtonText("Close")
        init()
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        table.autoResizeMode = JBTable.AUTO_RESIZE_LAST_COLUMN
        table.columnModel.getColumn(0).preferredWidth = 220
        table.columnModel.getColumn(1).preferredWidth = 80

        searchButton.addActionListener { loadPage(0) }
        filterField.addActionListener { loadPage(0) }
        refreshButton.addActionListener { loadPage(page) }
        prevButton.addActionListener { loadPage((page - 1).coerceAtLeast(0)) }
        nextButton.addActionListener { loadPage(page + 1) }
        consoleButton.addActionListener {
            EsConsoleDialog(
                project,
                config,
                secret,
                SavedRequest(
                    connectionId = config.id,
                    name = index,
                    method = "GET",
                    path = "/$index/_search",
                ),
            ).show()
        }
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2 && table.selectedRow >= 0) {
                    val hit = hits.getOrNull(table.selectedRow) ?: return
                    JsonViewerDialog(
                        project,
                        "Document — $index",
                        GsonBuilder().setPrettyPrinting().create().toJson(hit),
                    ).show()
                }
            }
        })

        loadPage(0)
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    override fun createCenterPanel(): JComponent {
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
        val top = JPanel(BorderLayout(0, 6)).apply {
            add(filterRow, BorderLayout.NORTH)
            add(navigation, BorderLayout.SOUTH)
        }

        val panel = JPanel(BorderLayout(0, 6)).apply {
            add(top, BorderLayout.NORTH)
            add(JBScrollPane(table), BorderLayout.CENTER)
        }
        panel.preferredSize = Dimension(1000, 650)
        return panel
    }

    private fun loadPage(target: Int) {
        if (loading) return
        loading = true
        setButtonsEnabled(false)
        statusLabel.text = "Loading..."

        val query = mutableMapOf(
            "size" to pageSize.toString(),
            "from" to (target * pageSize).toString(),
            "sort" to "_doc",
        )
        filterField.text.trim().takeIf { it.isNotEmpty() }?.let { query["q"] = it }

        AppExecutorUtil.getAppExecutorService().execute {
            val result = runCatching { EsClient(config, secret).get("/$index/_search", query) }
            ApplicationManager.getApplication().invokeLater({
                loading = false
                setButtonsEnabled(true)
                result.onSuccess { response ->
                    if (!response.isSuccess) {
                        statusLabel.text = "HTTP ${response.status}: ${response.body.take(200)}"
                    } else {
                        applyResponse(target, response.body)
                    }
                }.onFailure { error ->
                    statusLabel.text = "Failed: ${error.message ?: error.javaClass.simpleName}"
                }
            }, ModalityState.any())
        }
    }

    private fun applyResponse(target: Int, body: String) {
        val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
        if (root == null) {
            statusLabel.text = "Unexpected response (not JSON)"
            return
        }
        val hitsObject = root.getAsJsonObject("hits")
        val totalElement = hitsObject.get("total")
        total = when {
            totalElement == null || totalElement.isJsonNull -> 0L
            totalElement.isJsonObject -> totalElement.asJsonObject.get("value")?.asLong ?: 0L
            else -> totalElement.asLong
        }
        hits = hitsObject.getAsJsonArray("hits")
            ?.mapNotNull { if (it.isJsonObject) it.asJsonObject else null }
            ?: emptyList()

        page = target
        model.fireTableDataChanged()
        statusLabel.text = "Page ${page + 1} — ${hits.size} of $total"
        prevButton.isEnabled = page > 0
        nextButton.isEnabled = (page + 1L) * pageSize < total
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        searchButton.isEnabled = enabled
        refreshButton.isEnabled = enabled
        prevButton.isEnabled = enabled
        nextButton.isEnabled = enabled
    }
}
