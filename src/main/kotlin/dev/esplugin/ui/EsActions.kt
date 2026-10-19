package dev.esplugin.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

class AddConnectionAction(private val panel: EsTreePanel) : AnAction(
    "Add Connection",
    "Add a new Elasticsearch connection",
    AllIcons.General.Add,
) {
    override fun actionPerformed(event: AnActionEvent) {
        panel.addConnection()
    }
}

class EditConnectionAction(private val panel: EsTreePanel) : AnAction(
    "Edit Connection",
    "Edit the selected connection",
    AllIcons.Actions.Edit,
) {
    override fun actionPerformed(event: AnActionEvent) {
        panel.editSelectedConnection()
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = panel.hasSelectedConnection()
    }
}

class RemoveConnectionAction(private val panel: EsTreePanel) : AnAction(
    "Remove Connection",
    "Remove the selected connection",
    AllIcons.General.Remove,
) {
    override fun actionPerformed(event: AnActionEvent) {
        panel.removeSelectedConnection()
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = panel.hasSelectedConnection()
    }
}

class RefreshConnectionsAction(private val panel: EsTreePanel) : AnAction(
    "Refresh Connections",
    "Reload connections from settings",
    AllIcons.Actions.Refresh,
) {
    override fun actionPerformed(event: AnActionEvent) {
        panel.reloadConnections()
    }
}
