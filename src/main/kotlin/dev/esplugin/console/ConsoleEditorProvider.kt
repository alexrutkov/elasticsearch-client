package dev.esplugin.console

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class ConsoleEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean =
        file is ConsoleVirtualFile

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        ConsoleEditor(project, file as ConsoleVirtualFile)

    override fun getEditorTypeId(): String = "elasticsearch-console"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
