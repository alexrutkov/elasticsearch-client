package dev.esplugin.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.FormBuilder
import dev.esplugin.client.EsClient
import java.awt.BorderLayout
import java.awt.Dimension
import java.net.URI
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.JTextArea

class ConnectionDialog(
    private val project: Project?,
    private val original: ConnectionConfig,
    private val isNew: Boolean = false,
) : DialogWrapper(project, true) {

    private val nameField = JBTextField(original.name)
    private val urlField = JBTextField(original.url)
    private val authCombo = ComboBox<AuthType>().apply {
        model = DefaultComboBoxModel(AuthType.entries.toTypedArray())
        selectedItem = original.authType
    }
    private val usernameField = JBTextField(original.username)
    private val secretField = JBPasswordField()
    private val headersArea = JTextArea(original.headers, 3, 40)
    private val verifySslCheck = JBCheckBox("Verify SSL certificates", original.verifySsl)
    private val connectTimeoutField = JBTextField(original.connectTimeoutSeconds.toString())
    private val requestTimeoutField = JBTextField(original.requestTimeoutSeconds.toString())
    private val testButton = JButton("Test Connection")
    private val testResult = JBLabel(" ")

    var resultConfig: ConnectionConfig = original.copy()
        private set

    var resultSecret: String = ""
        private set

    init {
        title = if (isNew) "New Elasticsearch Connection" else "Elasticsearch Connection"
        init()
        updateAuthFields()
    }

    override fun createCenterPanel(): JComponent {
        authCombo.addActionListener { updateAuthFields() }
        testButton.addActionListener { testConnection() }

        val secretHint = if (isNew) {
            "Password, API key or token."
        } else {
            "Leave blank to keep the saved secret."
        }

        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent("Name:", nameField)
            .addLabeledComponent("URL:", urlField)
            .addLabeledComponent("Authentication:", authCombo)
            .addLabeledComponent("Username:", usernameField)
            .addLabeledComponent("Password / API key / token:", secretField)
            .addComponent(JBLabel(secretHint))
            .addLabeledComponent("HTTP headers:", JBScrollPane(headersArea))
            .addComponent(verifySslCheck)
            .addLabeledComponent("Connect timeout, seconds:", connectTimeoutField)
            .addLabeledComponent("Request timeout, seconds:", requestTimeoutField)
            .addComponent(JSeparator())
            .addComponent(
                JPanel(BorderLayout(8, 0)).apply {
                    add(testButton, BorderLayout.WEST)
                    add(testResult, BorderLayout.CENTER)
                },
            )
            .panel

        form.preferredSize = Dimension(640, 500)
        return form
    }

    override fun doValidate(): ValidationInfo? {
        if (nameField.text.isBlank()) {
            return ValidationInfo("Connection name is required", nameField)
        }
        val url = urlField.text.trim()
        if (url.isBlank()) {
            return ValidationInfo("URL is required", urlField)
        }
        val uri = runCatching { URI(url) }.getOrNull()
        if (uri?.scheme?.lowercase() !in listOf("http", "https")) {
            return ValidationInfo("URL must start with http:// or https://", urlField)
        }
        if (connectTimeoutField.text.trim().toIntOrNull() == null) {
            return ValidationInfo("Connect timeout must be a number", connectTimeoutField)
        }
        if (requestTimeoutField.text.trim().toIntOrNull() == null) {
            return ValidationInfo("Request timeout must be a number", requestTimeoutField)
        }
        return null
    }

    override fun doOKAction() {
        resultConfig = collectConfig()
        resultSecret = String(secretField.password)
        super.doOKAction()
    }

    private fun collectConfig(): ConnectionConfig = original.copy().also {
        it.name = nameField.text.trim()
        it.url = urlField.text.trim()
        it.authType = authCombo.selectedItem as? AuthType ?: AuthType.NONE
        it.username = usernameField.text.trim()
        it.headers = headersArea.text
        it.verifySsl = verifySslCheck.isSelected
        it.connectTimeoutSeconds = connectTimeoutField.text.trim().toIntOrNull()?.coerceIn(1, 600) ?: 10
        it.requestTimeoutSeconds = requestTimeoutField.text.trim().toIntOrNull()?.coerceIn(1, 3600) ?: 60
    }

    private fun updateAuthFields() {
        val type = authCombo.selectedItem as? AuthType ?: AuthType.NONE
        usernameField.isEnabled = type == AuthType.BASIC
        secretField.isEnabled = type != AuthType.NONE
    }

    private fun testConnection() {
        val config = collectConfig()
        val typedSecret = String(secretField.password)
        val secret = if (typedSecret.isNotEmpty()) typedSecret else SecretStore.get(original.id)

        testButton.isEnabled = false
        testResult.text = "Connecting..."
        AppExecutorUtil.getAppExecutorService().execute {
            val result = runCatching { EsClient(config, secret).checkConnection() }
            ApplicationManager.getApplication().invokeLater {
                testButton.isEnabled = true
                result.onSuccess { info ->
                    val health = info.health?.status?.let { " / $it" }.orEmpty()
                    testResult.text =
                        "Connected: ${info.cluster.clusterName} ${info.cluster.version}$health"
                }.onFailure { error ->
                    testResult.text = "Failed: ${error.message ?: error.javaClass.simpleName}"
                }
            }
        }
    }
}
