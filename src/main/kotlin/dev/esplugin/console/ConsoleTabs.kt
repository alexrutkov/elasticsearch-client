package dev.esplugin.console

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import dev.esplugin.settings.ConnectionConfig

object ConsoleTabs {

    fun open(
        project: Project,
        config: ConnectionConfig,
        initial: SavedRequest? = null,
        editingConsoleId: String? = null,
        title: String = "Console",
        reuseKey: String? = null,
    ) {
        val manager = FileEditorManager.getInstance(project)
        val key = reuseKey ?: editingConsoleId?.let { "saved:$it" }
        val existing = key?.let {
            manager.openFiles
                .filterIsInstance<ConsoleVirtualFile>()
                .firstOrNull { file -> file.connectionId == config.id && file.reuseKey == key }
        }

        val request = initial?.copy()
            ?: SavedRequest(connectionId = config.id, name = title, method = "GET", path = "/")
        val file = existing ?: ConsoleVirtualFile(
            connectionId = config.id,
            connectionName = config.name,
            editingConsoleId = editingConsoleId,
            initial = request,
            reuseKey = key,
            title = title,
        )
        manager.openFile(file, true)
    }
}
