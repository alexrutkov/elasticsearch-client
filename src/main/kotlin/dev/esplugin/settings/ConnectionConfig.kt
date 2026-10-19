package dev.esplugin.settings

import java.util.UUID

enum class AuthType {
    NONE,
    BASIC,
    API_KEY,
    BEARER;

    val displayName: String
        get() = when (this) {
            NONE -> "No authentication"
            BASIC -> "Basic (username and password)"
            API_KEY -> "API key"
            BEARER -> "Bearer token"
        }

    override fun toString(): String = displayName
}

/**
 * Serializable connection settings. Secrets are stored separately in [com.intellij.ide.passwordSafe.PasswordSafe].
 */
class ConnectionConfig {
    var id: String = UUID.randomUUID().toString()
    var name: String = "Local cluster"
    var url: String = "http://localhost:9200"
    var authType: AuthType = AuthType.NONE
    var username: String = ""
    var headers: String = ""
    var verifySsl: Boolean = true
    var connectTimeoutSeconds: Int = 10
    var requestTimeoutSeconds: Int = 60

    fun copy(): ConnectionConfig = ConnectionConfig().also {
        it.id = id
        it.name = name
        it.url = url
        it.authType = authType
        it.username = username
        it.headers = headers
        it.verifySsl = verifySsl
        it.connectTimeoutSeconds = connectTimeoutSeconds
        it.requestTimeoutSeconds = requestTimeoutSeconds
    }

    override fun toString(): String = name
}
