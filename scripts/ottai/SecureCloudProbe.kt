package tk.glucodata.drivers.ottai

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

/** Manual anonymous transport check using the production codec. No sensor/account access. */
fun main() {
    val deviceId = "ottai:a:n:${UUID.randomUUID()}"
    val seedSource = File("Common/src/main/java/tk/glucodata/drivers/ottai/OttaiCloudClient.kt").readText()
    val seed = requireNotNull(Regex("const val SEED\\s*=\\s*\"([^\"]+)\"").find(seedSource)).groupValues[1]
    fun raw(method: String, path: String, extra: Map<String, String>, query: Map<String, String>, body: String?): OttaiNetSecurity.Reply {
        val params = query.entries.joinToString("&") { "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val url = "https://api.ottai.com/cgm/security/app/server$path" + if (params.isEmpty()) "" else "?$params"
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            val headers = mapOf("deviceId" to deviceId, "appName" to "ottai", "applicationType" to "ottai_main",
                "packageName" to "com.ottai.tag", "versionName" to "1.63.0", "versionCode" to "264122",
                "ua" to "Android", "timestamp" to System.currentTimeMillis().toString()) + extra
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            OttaiNetSecurity.Reply(status, stream?.bufferedReader()?.use { JSONObject(it.readText()) })
        } finally { connection.disconnect() }
    }
    val client = OttaiNetSecurity(deviceId,
        exchange = { body -> raw("POST", "/security/exchangeKey", emptyMap(), emptyMap(), body.toString()) },
        send = ::raw)
    try {
        val timestamp = System.currentTimeMillis()
        val signature = OttaiCrypto.bytesToHex(MessageDigest.getInstance("MD5")
            .digest("ottai$deviceId$timestamp$seed".toByteArray()))
        val response = client.request("GET", "/user/apiToken", mapOf("signature" to signature))
        check(response.getString("code") == "OK" && response.optString("data").isNotBlank())
        println("Production codec: secure exchange, encrypted GET and authenticated response passed (anonymous identity).")
    } finally { client.close() }
}
