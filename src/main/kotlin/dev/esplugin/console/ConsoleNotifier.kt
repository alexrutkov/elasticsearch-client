package dev.esplugin.console

import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.messages.Topic

interface ConsoleChangeListener {
    fun consoleChanged(connectionId: String)
}

object ConsoleNotifier {
    val TOPIC: Topic<ConsoleChangeListener> =
        Topic.create("Elasticsearch console changed", ConsoleChangeListener::class.java)

    fun notifyChanged(connectionId: String) {
        ApplicationManager.getApplication().messageBus
            .syncPublisher(TOPIC)
            .consoleChanged(connectionId)
    }
}
