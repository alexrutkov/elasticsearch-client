package dev.esplugin.browser

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class DataBrowserEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean =
        file is DataBrowserVirtualFile

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        DataBrowserEditor(project, file as DataBrowserVirtualFile)

    override fun getEditorTypeId(): String = "elasticsearch-data-browser"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
