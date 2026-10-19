package dev.esplugin.browser

import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Dimension
import javax.swing.JTable
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * One-column gutter intended for [javax.swing.JScrollPane.setRowHeaderView].
 * Shows 1-based view row numbers, so the numbers stay 1..N after sorting.
 */
class RowNumberTable(private val target: JTable) : JBTable(RowNumberModel(target)) {

    private var initialized = false

    init {
        isFocusable = false
        rowSelectionAllowed = true
        columnSelectionAllowed = false
        autoResizeMode = AUTO_RESIZE_OFF
        setShowGrid(false)
        intercellSpacing = Dimension(0, 0)
        rowHeight = target.rowHeight
        selectionModel = target.selectionModel
        setDefaultRenderer(Any::class.java, NumberRenderer())

        target.addPropertyChangeListener("rowHeight") {
            rowHeight = target.rowHeight
            refresh()
        }
        target.addPropertyChangeListener("font") { refresh() }
        target.model.addTableModelListener { refresh() }
        target.rowSorter?.addRowSorterListener { refresh() }

        initialized = true
        syncWidth()
    }

    override fun updateUI() {
        super.updateUI()
        // updateUI() runs in the JTable superclass constructor before `target` is assigned.
        background = UIUtil.getTableBackground()
        foreground = UIUtil.getTableForeground()
        if (initialized) syncWidth()
    }

    private fun refresh() {
        // TableModel listeners fire in reverse registration order, so the view model
        // may not have been updated yet. Defer width/repaint until it has.
        SwingUtilities.invokeLater {
            syncWidth()
            repaint()
        }
    }

    private fun syncWidth() {
        val width = gutterWidth()
        val column = columnModel.getColumn(0)
        if (column.width != width) {
            column.minWidth = width
            column.maxWidth = width
            column.preferredWidth = width
            column.width = width
        }
        revalidate()
    }

    private fun gutterWidth(): Int {
        val digits = target.rowCount.coerceAtLeast(1).toString().length.coerceAtLeast(2)
        return getFontMetrics(font).charWidth('0') * digits + JBUI.scale(12)
    }

    private class NumberRenderer : DefaultTableCellRenderer() {
        init {
            horizontalAlignment = RIGHT
        }

        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            super.getTableCellRendererComponent(table, value, isSelected, false, row, column)
            border = JBUI.Borders.empty(0, 4)
            return this
        }
    }
}

private class RowNumberModel(private val target: JTable) : AbstractTableModel() {
    override fun getRowCount(): Int = target.rowCount

    override fun getColumnCount(): Int = 1

    override fun getColumnName(column: Int): String = ""

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = rowIndex + 1

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false
}
