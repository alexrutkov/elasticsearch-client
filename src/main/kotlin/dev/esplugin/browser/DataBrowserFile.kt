package dev.esplugin.browser

import com.intellij.openapi.fileTypes.FileType
import com.intellij.testFramework.LightVirtualFile
import dev.esplugin.icons.Icons
import javax.swing.Icon

object DataBrowserFileType : FileType {
    override fun getName(): String = "Elasticsearch Data Browser"

    override fun getDescription(): String = "Read-only Elasticsearch documents"

    override fun getDefaultExtension(): String = "esdata"

    override fun isBinary(): Boolean = true

    override fun getIcon(): Icon = Icons.ELASTICSEARCH
}

class DataBrowserVirtualFile(
    val connectionId: String,
    val index: String,
    connectionName: String,
) : LightVirtualFile("$index [@$connectionName]", DataBrowserFileType, "")
