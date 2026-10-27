package tk.glucodata.drivers.ottai

import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID
import org.json.JSONObject

/** Common CN phone transport for account, activation and OTA requests. No account state is written. */
internal class OttaiCnTransport(
    headers: Map<String, String>,
    private val send: (String, String, String?, Map<String, String>) -> OttaiNetSecurity.Reply,
    private val refreshTimestamp: Boolean = false,
) {
    private val identityHeaders = headers + mapOf("versionName" to "1.63.0", "versionCode" to "264122")
    private val codec = OttaiNetSecurity(identityHeaders.getValue("deviceId"),
        exchange = { body -> raw("POST", "/security/exchangeKey", emptyMap(), emptyMap(), body.toString()) },
        send = ::raw)

    private fun raw(method: String, path: String, cipherHeaders: Map<String, String>,
        query: Map<String, String>, body: String?): OttaiNetSecurity.Reply {
        val qs = query.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        val url = OttaiConstants.API_BASE + OttaiConstants.OTA_SECURE_PREFIX + path +
            if (qs.isEmpty()) "" else "?$qs"
        // Legacy business signatures use the original common timestamp. OTA has no such
        // signature and can refresh it when reporting after a long-running transfer.
        val clockHeaders = if (refreshTimestamp) mapOf("timestamp" to System.currentTimeMillis().toString(),
            "traceId" to UUID.randomUUID().toString()) else emptyMap()
        val requestHeaders = identityHeaders + clockHeaders + cipherHeaders +
            if (body == null) emptyMap() else mapOf("Content-Type" to "application/json;charset=UTF-8")
        return send(method, url, body, requestHeaders)
    }

    fun request(method: String, path: String, query: Map<String, String> = emptyMap(), body: String? = null): JSONObject =
        codec.request(method, path, query, body)

    fun requestReply(method: String, route: Route, body: String?): OttaiNetSecurity.Reply =
        codec.requestReply(method, route.path, route.query, body)

    fun close() = codec.close()

    internal data class Route(val path: String, val query: Map<String, String>)

    companion object {
        /** Select only the exact CN mobile host/prefix and the identity that issued CN phone tokens. */
        fun route(url: String, headers: Map<String, String>): Route? {
            val prefix = OttaiConstants.API_BASE + OttaiConstants.PREFIX + "/"
            if (!url.startsWith(prefix) || headers["appName"] != "ottai" ||
                headers["packageName"] != "com.ottai.tag") return null
            val relative = url.removePrefix(OttaiConstants.API_BASE + OttaiConstants.PREFIX)
            require(!relative.contains('#')) { "cn_route" }
            val query = relative.substringAfter('?', "").takeIf { it.isNotEmpty() }?.split('&')
                ?.associate { field ->
                    URLDecoder.decode(field.substringBefore('='), "UTF-8") to
                        URLDecoder.decode(field.substringAfter('=', ""), "UTF-8")
                }.orEmpty()
            return Route(relative.substringBefore('?'), query)
        }

        private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
    }
}
