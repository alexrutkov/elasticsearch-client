package dev.esplugin.console

import java.util.UUID

/**
 * A saved REST console. Kept as a mutable bean for IntelliJ's XML serializer.
 */
data class SavedRequest(
    var id: String = UUID.randomUUID().toString(),
    var connectionId: String = "",
    var name: String = "Request",
    var method: String = "GET",
    var path: String = "/",
    var headers: String = "",
    var body: String = "",
    var timestamp: Long = System.currentTimeMillis(),
)

/**
 * A saved query body, executed as `POST <path>` from the console.
 */
data class SavedQuery(
    var id: String = UUID.randomUUID().toString(),
    var connectionId: String = "",
    var name: String = "Query",
    var path: String = "/_search",
    var body: String = "",
    var timestamp: Long = System.currentTimeMillis(),
)
