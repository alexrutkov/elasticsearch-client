package dev.esplugin.console

import com.intellij.openapi.fileTypes.FileType
import com.intellij.testFramework.LightVirtualFile
import dev.esplugin.icons.Icons
import javax.swing.Icon

object ConsoleFileType : FileType {
    override fun getName(): String = "Elasticsearch Console"

    override fun getDescription(): String = "Elasticsearch REST console"

    override fun getDefaultExtension(): String = "esconsole"

    override fun isBinary(): Boolean = true

    override fun getIcon(): Icon = Icons.ELASTICSEARCH
}

class ConsoleVirtualFile(
    val connectionId: String,
    val connectionName: String,
    var editingConsoleId: String?,
    val initial: SavedRequest,
    val reuseKey: String?,
    title: String,
) : LightVirtualFile("$title [@$connectionName]", ConsoleFileType, "")
