package dev.esplugin.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

object EsNotifications {

    fun error(project: Project?, title: String, content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Elasticsearch Client")
            .createNotification(title, content, NotificationType.ERROR)
            .notify(project)
    }

    fun info(project: Project?, title: String, content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Elasticsearch Client")
            .createNotification(title, content, NotificationType.INFORMATION)
            .notify(project)
    }
}
