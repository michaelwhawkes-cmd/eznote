package com.example.notes

import android.content.Context
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import org.json.*
import java.io.File
import java.net.URL

data class Link(val url: String, val title: String, val img: String)
data class Note(val id: String, val title: String = "", val html: String = "", val cat: String = "",
                val updated: Long = System.currentTimeMillis(), val links: List<Link> = emptyList(), val pdfs: List<String> = emptyList())
data class Ink(val color: Int, val hl: Boolean, val pts: List<Float>)

private fun JSONArray.objs() = (0 until length()).map { getJSONObject(it) }
private fun JSONArray.strs() = (0 until length()).map { getString(it) }

class Store(ctx: Context) {
    val dir: File = ctx.filesDir
    private val f = File(dir, "notes.json")
    private val prefs = ctx.getSharedPreferences("p", 0)
    val notes = mutableStateListOf<Note>()
    val cats = mutableStateListOf("Work", "Personal", "Ideas")
    var theme by mutableStateOf(prefs.getInt("theme", 0)) // 0 auto, 1 light, 2 dark
    var sort by mutableStateOf(0)                          // 0 recent, 1 title, 2 category

    val gone = mutableSetOf<String>() // ids of deleted notes, so deletions sync
    var syncing by mutableStateOf(false)
    var msg by mutableStateOf<String?>(null)
    init {
        runCatching { gone.addAll(JSONObject(f.readText()).optJSONArray("g")?.strs() ?: emptyList()) }
        runCatching {
            val j = JSONObject(f.readText())
            cats.clear(); cats.addAll(j.getJSONArray("cats").strs())
            notes.addAll(j.getJSONArray("notes").objs().map { o ->
                Note(o.getString("id"), o.getString("t"), o.getString("h"), o.getString("c"), o.getLong("u"),
                    o.getJSONArray("l").objs().map { Link(it.getString("u"), it.getString("t"), it.getString("i")) },
                    o.getJSONArray("p").strs())
            })
        }
    }

    var acct by mutableStateOf(prefs.getString("acct", null))   // "username||" once signed in; "Guest||" without an account
    var token by mutableStateOf(prefs.getString("token", null)) // server session (null for guests)
    val username: String? get() = acct?.substringBefore('|')
    fun signIn(name: String, tok: String?) { acct = "$name||"; token = tok; prefs.edit().putString("acct", acct).putString("token", tok).apply() }
    fun signOut() { acct = null; token = null; prefs.edit().remove("acct").remove("token").apply() }
    fun setTheme(t: Int) { theme = t; prefs.edit().putInt("theme", t).apply() }
    fun save() { f.writeText(json()) }
    fun merge(text: String) {
        val j = JSONObject(text)
        j.optJSONArray("g")?.strs()?.let { gone.addAll(it) }
        j.optJSONArray("cats")?.strs()?.forEach { if (it !in cats) cats.add(it) }
        j.getJSONArray("notes").objs().map { o ->
            Note(o.getString("id"), o.getString("t"), o.getString("h"), o.getString("c"), o.getLong("u"),
                o.getJSONArray("l").objs().map { Link(it.getString("u"), it.getString("t"), it.getString("i")) }, o.getJSONArray("p").strs())
        }.forEach { r ->
            if (r.id !in gone) { val i = notes.indexOfFirst { it.id == r.id }; if (i < 0) notes.add(r) else if (r.updated > notes[i].updated) notes[i] = r }
        }
        notes.removeAll { it.id in gone }; save()
    }
    fun json(): String {
        return (JSONObject().put("g", JSONArray(gone.toList())).put("cats", JSONArray(cats)).put("notes", JSONArray(notes.map { n ->
            JSONObject().put("id", n.id).put("t", n.title).put("h", n.html).put("c", n.cat).put("u", n.updated)
                .put("l", JSONArray(n.links.map { JSONObject().put("u", it.url).put("t", it.title).put("i", it.img) }))
                .put("p", JSONArray(n.pdfs))
        })).toString())
    }
    fun put(n: Note) { val i = notes.indexOfFirst { it.id == n.id }; if (i < 0) notes.add(n) else notes[i] = n; save() }
    fun del(id: String) { notes.removeAll { it.id == id }; gone.add(id); save() }
    fun addCat(c: String) { if (c.isNotBlank() && c !in cats) { cats.add(c); save() } }

    fun loadInk(pdf: String): Map<Int, List<Ink>> = runCatching {
        val j = JSONObject(File(dir, "$pdf.ink").readText())
        j.keys().asSequence().associate { k ->
            k.toInt() to j.getJSONArray(k).objs().map { o ->
                Ink(o.getInt("c"), o.getBoolean("h"), o.getJSONArray("p").let { a -> (0 until a.length()).map { a.getDouble(it).toFloat() } })
            }
        }
    }.getOrDefault(emptyMap())

    fun saveInk(pdf: String, m: Map<Int, List<Ink>>) {
        val j = JSONObject()
        m.forEach { (k, v) -> j.put(k.toString(), JSONArray(v.map { JSONObject().put("c", it.color).put("h", it.hl).put("p", JSONArray(it.pts)) })) }
        File(dir, "$pdf.ink").writeText(j.toString())
    }
}

/** Fetches title + og:image so a pasted link becomes a thumbnail card. */
suspend fun preview(url: String): Link = withContext(Dispatchers.IO) {
    runCatching {
        val c = URL(url).openConnection().apply { setRequestProperty("User-Agent", "Mozilla/5.0"); connectTimeout = 6000; readTimeout = 6000 }
        val h = c.getInputStream().bufferedReader().readText().take(250000)
        fun m(p: String) = Regex("""<meta[^>]+(?:property|name)=["']$p["'][^>]+content=["']([^"']+)""", RegexOption.IGNORE_CASE).find(h)?.groupValues?.get(1)?.replace("&amp;", "&")
        val title = m("og:title") ?: Regex("<title[^>]*>(.*?)</title>", RegexOption.IGNORE_CASE).find(h)?.groupValues?.get(1) ?: url
        Link(url, title.trim(), m("og:image")?.let { URL(URL(url), it).toString() } ?: "")
    }.getOrDefault(Link(url, url, ""))
}
