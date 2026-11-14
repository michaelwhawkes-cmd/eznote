package com.example.notes

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ApiError(val code: Int, msg: String) : Exception(msg)

object Api {
    fun call(method: String, path: String, token: String?, body: ByteArray? = null, ctype: String = "application/json"): ByteArray {
        val c = URL(Config.SERVER + path).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 15000; c.readTimeout = 60000
        token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", ctype); c.outputStream.use { it.write(body) } }
        val code = c.responseCode
        val data = (if (code < 400) c.inputStream else c.errorStream)?.readBytes() ?: ByteArray(0)
        if (code >= 400) throw ApiError(code, runCatching { JSONObject(String(data)).getString("error") }.getOrDefault("Server error ($code)"))
        return data
    }
    fun json(method: String, path: String, token: String?, obj: JSONObject? = null) =
        JSONObject(String(call(method, path, token, obj?.toString()?.toByteArray())))
}
