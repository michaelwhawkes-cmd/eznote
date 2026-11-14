package com.example.notes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

/** Syncs notes, PDFs and PDF markup with your EZ Note server. */
object Sync {
    suspend fun finish(s: Store) {
        s.syncing = true
        try {
            run(s)
            s.msg = "Synced at " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())
        } catch (e: ApiError) {
            if (e.code == 401) { s.signOut(); s.msg = "Please sign in again" } else s.msg = "Sync failed: ${e.message}"
        } catch (e: Exception) { s.msg = "Sync failed: ${Auth.message(e)}" } finally { s.syncing = false }
    }

    private suspend fun run(s: Store) = withContext(Dispatchers.IO) {
        val tok = s.token ?: error("Not signed in")
        // 1. Notes: the server merges (newest edit per note wins, deletions propagate) and returns the result.
        s.merge(String(Api.call("POST", "/api/sync", tok, s.json().toByteArray())))
        // 2. PDFs and their markup.
        val arr = JSONArray(String(Api.call("GET", "/api/files", tok)))
        val remote = (0 until arr.length()).associate { arr.getJSONObject(it).let { o -> o.getString("name") to o } }
        for (name in s.notes.flatMap { it.pdfs }.flatMap { listOf(it, "$it.ink") }.distinct()) {
            val f = File(s.dir, name); val r = remote[name]
            fun up() { Api.call("PUT", "/api/files/$name", tok, f.readBytes(), "application/octet-stream") }
            fun down() { f.writeBytes(Api.call("GET", "/api/files/$name", tok)) }
            when {
                f.exists() && r == null -> up()
                !f.exists() && r != null -> down()
                f.exists() && r != null && name.endsWith(".ink") -> {
                    val rt = r.getLong("mtime")
                    if (rt > f.lastModified() + 2000) down() else if (f.lastModified() > rt + 2000) up()
                }
            }
        }
    }
}
