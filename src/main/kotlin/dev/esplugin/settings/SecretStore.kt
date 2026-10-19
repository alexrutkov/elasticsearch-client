package dev.esplugin.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * Passwords, API keys and tokens live in the IDE credential store, never in the settings XML.
 */
object SecretStore {

    private fun attributes(connectionId: String) = CredentialAttributes("elasticsearch-client:$connectionId")

    fun get(connectionId: String): String? = PasswordSafe.instance.getPassword(attributes(connectionId))

    fun set(connectionId: String, secret: String?) {
        val safe = PasswordSafe.instance
        val attributes = attributes(connectionId)
        if (secret.isNullOrEmpty()) {
            safe.set(attributes, null)
        } else {
            safe.set(attributes, Credentials(null, secret))
        }
    }
}
