package dev.esplugin.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import dev.esplugin.console.ConsoleUtils
import java.awt.Dimension
import java.awt.Font
import javax.swing.Action
import javax.swing.JComponent

class JsonViewerDialog(
    project: Project?,
    title: String,
    json: String,
) : DialogWrapper(project) {

    private val text = ConsoleUtils.prettyJson(json)

    init {
        this.title = title
        setOKButtonText("Close")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val area = JBTextArea(text).apply {
            isEditable = false
            lineWrap = false
            font = Font("JetBrains Mono", Font.PLAIN, JBUI.scale(12))
            caretPosition = 0
        }
        val scroll = JBScrollPane(area)
        scroll.preferredSize = Dimension(900, 600)
        return scroll
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)
}
