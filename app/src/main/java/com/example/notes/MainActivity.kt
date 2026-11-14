package com.example.notes

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.text.*
import android.text.style.*
import android.util.Patterns
import android.view.Gravity
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private val incoming = mutableStateOf<Intent?>(null)
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val store = Store(this); incoming.value = intent
        setContent {
            val dark = when (store.theme) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
            MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xFF7B92FF)) else lightColorScheme(primary = Color(0xFF4F6DF5))) {
                Surface(Modifier.fillMaxSize()) { if (store.acct == null) LoginScreen(store) else App(store, incoming) }
            }
        }
    }
    override fun onNewIntent(i: Intent) { super.onNewIntent(i); incoming.value = i }
}

fun importPdf(ctx: Context, s: Store, uri: Uri): String {
    val name = "${System.currentTimeMillis()}_" + (uri.lastPathSegment?.substringAfterLast('/') ?: "doc.pdf").replace(Regex("[^A-Za-z0-9._-]"), "_")
    ctx.contentResolver.openInputStream(uri)?.use { i -> File(s.dir, name).outputStream().use { i.copyTo(it) } }
    return name
}

@Composable fun App(s: Store, inIntent: MutableState<Intent?>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    fun sync() {
        if (s.syncing) return
        if (s.token == null) { s.msg = "Create an account or sign in to turn on sync"; return }
        scope.launch { Sync.finish(s) }
    }
    LaunchedEffect(Unit) { if (s.token != null) sync() }
    var open by remember { mutableStateOf<String?>(null) }
    var pdf by remember { mutableStateOf<String?>(null) }
    // Web clips (share a page from Chrome) and PDFs (share/open from Files) arrive here.
    LaunchedEffect(inIntent.value) {
        val i = inIntent.value ?: return@LaunchedEffect; inIntent.value = null
        val uri = if (i.action == Intent.ACTION_SEND) i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) else if (i.action == Intent.ACTION_VIEW) i.data else null
        val txt = i.getStringExtra(Intent.EXTRA_TEXT)
        if (uri != null) {
            val n = Note(UUID.randomUUID().toString(), title = "PDF", pdfs = listOf(withContext(Dispatchers.IO) { importPdf(ctx, s, uri) }))
            s.put(n); open = n.id
        } else if (txt != null) {
            val url = Patterns.WEB_URL.matcher(txt).let { if (it.find()) it.group() else null }
            val link = url?.let { preview(it) }
            val rest = txt.replace(url ?: "", "").trim()
            val n = Note(UUID.randomUUID().toString(), title = i.getStringExtra(Intent.EXTRA_SUBJECT) ?: link?.title ?: "Clip",
                html = "<p>" + TextUtils.htmlEncode(rest) + "</p>", links = listOfNotNull(link))
            s.put(n); open = n.id
        }
    }
    when {
        pdf != null -> PdfScreen(s, pdf!!) { pdf = null }
        open != null -> Editor(s, open!!, { pdf = it }) { open = null }
        else -> ListScreen(s, { sync() }) { open = it }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ListScreen(s: Store, onSync: () -> Unit, onOpen: (String) -> Unit) {
    var more by remember { mutableStateOf(false) }
    val lctx = LocalContext.current; val lscope = rememberCoroutineScope()
    var filter by remember { mutableStateOf("all") }
    var q by remember { mutableStateOf("") }
    var dlg by remember { mutableStateOf(false) }
    val sorts = listOf("Recent", "Title", "Category")
    val list = s.notes.filter {
        (filter == "all" || (if (filter == "none") it.cat == "" else it.cat == filter)) && (q.isBlank() || (it.title + it.html).contains(q, true))
    }.let { l ->
        when (s.sort) {
            1 -> l.sortedBy { it.title.lowercase() }
            2 -> l.sortedWith(compareBy<Note> { it.cat.ifEmpty { "~" } }.thenByDescending { it.updated })
            else -> l.sortedByDescending { it.updated }
        }
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text("EZ Note", fontWeight = FontWeight.ExtraBold) }, actions = {
            TextButton(onSync) { Text(if (s.syncing) "Syncing…" else "☁ Sync") }
            Box {
                TextButton({ more = true }) { Text("⋮", fontSize = 20.sp) }
                DropdownMenu(more, { more = false }) {
                    DropdownMenuItem({ Text("Sort: ${sorts[s.sort]}") }, { s.sort = (s.sort + 1) % 3; more = false })
                    DropdownMenuItem({ Text("Theme: ${listOf("Auto", "Light", "Dark")[s.theme]}") }, { s.setTheme((s.theme + 1) % 3); more = false })
                    if (s.token != null) DropdownMenuItem({ Text("Add a passkey") }, {
                        more = false
                        lscope.launch { try { Auth.passkeyRegister(lctx as Activity, s.username ?: "", s.token); s.msg = "Passkey added" } catch (e: Exception) { s.msg = Auth.message(e) } }
                    })
                    DropdownMenuItem({ Text("Sign out") }, { more = false; s.signOut() })
                }
            }
        }) },
        floatingActionButton = { FloatingActionButton({
            val n = Note(UUID.randomUUID().toString(), cat = if (filter in listOf("all", "none")) "" else filter); s.put(n); onOpen(n.id)
        }) { Text("+", fontSize = 28.sp) } }
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp)) {
            s.msg?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp)) }
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search") }, singleLine = true, shape = RoundedCornerShape(14.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                items(listOf("all" to "All", "none" to "No category") + s.cats.map { it to it }) { (k, l) -> FilterChip(filter == k, { filter = k }, { Text(l) }) }
                item { AssistChip({ dlg = true }, { Text("+ New") }) }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(list, key = { it.id }) { n ->
                    Card(Modifier.fillMaxWidth().clickable { onOpen(n.id) }, shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(n.title.ifBlank { "Untitled" }, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                            Text(Html.fromHtml(n.html, 0).toString().trim(), maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (n.cat != "") Text(n.cat, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
        }
    }
    if (dlg) Prompt("New category", { dlg = false }) { s.addCat(it) }
}

@Composable fun Prompt(title: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var t by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { OutlinedTextField(t, { t = it }, singleLine = true) },
        confirmButton = { TextButton({ onOk(t.trim()); onDismiss() }) { Text("Add") } }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

fun fmt(e: EditText, k: String) {
    val a = e.selectionStart; val b = e.selectionEnd; val t = e.text
    fun tog(mk: () -> Any, same: (Any) -> Boolean) {
        if (a >= b) return
        val o = t.getSpans(a, b, Any::class.java).filter(same)
        if (o.isEmpty()) t.setSpan(mk(), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) else o.forEach { t.removeSpan(it) }
    }
    when (k) {
        "B" -> tog({ StyleSpan(Typeface.BOLD) }, { it is StyleSpan && it.style == Typeface.BOLD })
        "I" -> tog({ StyleSpan(Typeface.ITALIC) }, { it is StyleSpan && it.style == Typeface.ITALIC })
        "U" -> tog({ UnderlineSpan() }, { it is UnderlineSpan })
        "H" -> tog({ RelativeSizeSpan(1.4f) }, { it is RelativeSizeSpan })
        else -> { // bullet / checklist, applied to the current line
            val ls = if (a == 0) 0 else t.toString().lastIndexOf('\n', a - 1) + 1
            val cur = listOf("• ", "☐ ", "☑ ").firstOrNull { t.substring(ls).startsWith(it) }
            val new = if (k == "•") (if (cur == "• ") "" else "• ") else when (cur) { "☐ " -> "☑ "; "☑ " -> ""; else -> "☐ " }
            if (cur != null) t.delete(ls, ls + 2)
            t.insert(ls, new)
        }
    }
}

@Composable fun Editor(s: Store, id: String, onPdf: (String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var n by remember { mutableStateOf(s.notes.first { it.id == id }) }
    fun upd(f: (Note) -> Note) { n = f(n).copy(updated = System.currentTimeMillis()); s.put(n) }
    var et by remember { mutableStateOf<EditText?>(null) }
    var menu by remember { mutableStateOf(false) }; var catDlg by remember { mutableStateOf(false) }; var clip by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) upd { it.copy(pdfs = it.pdfs + importPdf(ctx, s, u)) } }
    fun addLink(u: String) { scope.launch { val l = preview(u); upd { it.copy(links = it.links + l) } } }
    fun leave() { if (n.title.isBlank() && n.html.isBlank() && n.pdfs.isEmpty() && n.links.isEmpty()) s.del(id); onBack() }
    BackHandler { leave() }
    val tc = MaterialTheme.colorScheme.onSurface.toArgb()
    val clear = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent)
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton({ leave() }) { Text("←") }; Spacer(Modifier.weight(1f))
            Box {
                TextButton({ menu = true }) { Text(n.cat.ifEmpty { "No category" } + " ▾") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("No category") }, { upd { it.copy(cat = "") }; menu = false })
                    s.cats.forEach { c -> DropdownMenuItem({ Text(c) }, { upd { it.copy(cat = c) }; menu = false }) }
                    DropdownMenuItem({ Text("New…") }, { menu = false; catDlg = true })
                }
            }
            TextButton({ // Share sheet: pick Apple Notes-compatible targets (iCloud mail to self, Messages, etc.)
                val body = Html.fromHtml(n.html, 0).toString().trim() + n.links.joinToString("") { "\n" + it.url }
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, n.title).putExtra(Intent.EXTRA_TEXT, body), null))
            }) { Text("Share") }
            TextButton({ s.del(id); onBack() }) { Text("Delete") }
        }
        TextField(n.title, { v -> upd { it.copy(title = v) } }, Modifier.fillMaxWidth(), placeholder = { Text("Title") },
            textStyle = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold), colors = clear)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("B", "I", "U", "H", "•", "☐").forEach { k ->
                OutlinedButton({ et?.let { e -> fmt(e, k); upd { x -> x.copy(html = Html.toHtml(e.text, Html.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE)) } } },
                    contentPadding = PaddingValues(horizontal = 14.dp)) { Text(k, fontWeight = if (k == "B") FontWeight.Bold else null) }
            }
            OutlinedButton({ pick.launch(arrayOf("application/pdf")) }) { Text("PDF") }
            OutlinedButton({ clip = true }) { Text("Clip") }
        }
        AndroidView({ c ->
            EditText(c).apply {
                background = null; gravity = Gravity.TOP; minLines = 8; textSize = 17f; setPadding(0, 24, 0, 24); hint = "Start writing…"
                setText(Html.fromHtml(n.html, Html.FROM_HTML_MODE_COMPACT)); et = this
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
                    override fun onTextChanged(x: CharSequence?, st: Int, bf: Int, ct: Int) { // a pasted URL becomes a thumbnail card
                        if (ct >= 8 && x != null) { val p = x.subSequence(st, st + ct).toString().trim(); if (" " !in p && Patterns.WEB_URL.matcher(p).matches()) addLink(p) }
                    }
                    override fun afterTextChanged(e: Editable?) { if (e != null) upd { it.copy(html = Html.toHtml(e, Html.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE)) } }
                })
            }
        }, Modifier.fillMaxWidth(), update = { it.setTextColor(tc); it.setHintTextColor(tc and 0x80FFFFFF.toInt()) })
        n.links.forEach { l ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(l.url))) }, shape = RoundedCornerShape(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (l.img.isNotEmpty()) AsyncImage(l.img, null, Modifier.size(84.dp), contentScale = ContentScale.Crop)
                    Column(Modifier.padding(12.dp).weight(1f)) {
                        Text(l.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        Text(Uri.parse(l.url).host ?: "", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton({ upd { it.copy(links = it.links - l) } }) { Text("✕") }
                }
            }
        }
        n.pdfs.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip({ onPdf(p) }, { Text("📄 " + p.substringAfter('_')) }); TextButton({ upd { it.copy(pdfs = it.pdfs - p) } }) { Text("✕") }
            }
        }
        Spacer(Modifier.height(48.dp))
    }
    if (catDlg) Prompt("New category", { catDlg = false }) { c -> s.addCat(c); upd { it.copy(cat = c) } }
    if (clip) {
        var u by remember { mutableStateOf("") }; var tx by remember { mutableStateOf("") }
        AlertDialog({ clip = false }, title = { Text("Web clip") },
            text = { Column { OutlinedTextField(u, { u = it }, label = { Text("Link") }, singleLine = true); OutlinedTextField(tx, { tx = it }, label = { Text("Clipped text") }, minLines = 3) } },
            confirmButton = { TextButton({ if (u.isNotBlank()) addLink(u.trim()); if (tx.isNotBlank()) et?.append("\n\n" + tx); clip = false }) { Text("Add") } },
            dismissButton = { TextButton({ clip = false }) { Text("Cancel") } })
    }
}

@Composable fun PdfScreen(s: Store, name: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val r = remember { PdfRenderer(ParcelFileDescriptor.open(File(s.dir, name), ParcelFileDescriptor.MODE_READ_ONLY)) }
    DisposableEffect(Unit) { onDispose { r.close() } }
    val ink = remember { mutableStateMapOf<Int, List<Ink>>().apply { putAll(s.loadInk(name)) } }
    var tool by remember { mutableStateOf(0) }; var ci by remember { mutableStateOf(0) }
    val colors = listOf(0xFFFFEB3B, 0xFFE53935, 0xFF1E88E5, 0xFF43A047).map { it.toInt() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onBack) { Text("←") }
            listOf("Scroll", "Pen", "Highlight", "Erase").forEachIndexed { i, l -> FilterChip(tool == i, { tool = i }, { Text(l) }) }
            colors.forEachIndexed { i, c -> Box(Modifier.size(if (ci == i) 34.dp else 26.dp).background(Color(c), CircleShape).clickable { ci = i }) }
        }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(r.pageCount) { i -> PdfPage(r, i, ink[i] ?: emptyList(), tool, colors[ci]) { ink[i] = it; s.saveInk(name, ink.toMap()) } }
        }
    }
}

@Composable fun PdfPage(r: PdfRenderer, i: Int, strokes: List<Ink>, tool: Int, color: Int, onChange: (List<Ink>) -> Unit) {
    val bmp by produceState<Bitmap?>(null, i) {
        value = withContext(Dispatchers.IO) {
            synchronized(r) {
                r.openPage(i).use { p ->
                    val w = 1080
                    Bitmap.createBitmap(w, w * p.height / p.width, Bitmap.Config.ARGB_8888).also { b -> b.eraseColor(android.graphics.Color.WHITE); p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                }
            }
        }
    }
    val b = bmp
    if (b == null) { Box(Modifier.fillMaxWidth().height(400.dp)); return }
    var live by remember { mutableStateOf<List<Float>>(emptyList()) }
    var sz by remember { mutableStateOf(IntSize(1, 1)) }
    val cur by rememberUpdatedState(strokes)
    Box(Modifier.fillMaxWidth().aspectRatio(b.width.toFloat() / b.height).onSizeChanged { sz = it }) {
        Image(b.asImageBitmap(), null, Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize().then(if (tool == 0) Modifier else Modifier.pointerInput(tool, color) {
            detectDragGestures(
                onDragStart = { o -> live = listOf(o.x / sz.width, o.y / sz.height) },
                onDrag = { c, _ ->
                    c.consume(); val x = c.position.x / sz.width; val y = c.position.y / sz.height
                    if (tool == 3) onChange(cur.filterNot { st -> st.pts.chunked(2).any { abs(it[0] - x) < .02f && abs(it[1] - y) < .02f } }) else live = live + listOf(x, y)
                },
                onDragEnd = { if (tool != 3 && live.size > 2) onChange(cur + Ink(color, tool == 2, live)); live = emptyList() },
                onDragCancel = { live = emptyList() })
        })) {
            fun draw(st: Ink) {
                val p = Path()
                st.pts.chunked(2).forEachIndexed { k, q -> if (k == 0) p.moveTo(q[0] * size.width, q[1] * size.height) else p.lineTo(q[0] * size.width, q[1] * size.height) }
                drawPath(p, Color(st.color).copy(alpha = if (st.hl) .35f else 1f), style = Stroke(width = if (st.hl) 28f else 5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            strokes.forEach(::draw); if (live.size > 2) draw(Ink(color, tool == 2, live))
        }
    }
}

@Composable fun LoginScreen(s: Store) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }; var pw by remember { mutableStateOf("") }
    var usePw by remember { mutableStateOf(false) }; var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    fun go(block: suspend () -> Pair<String, String>) {
        busy = true; err = null
        scope.launch { try { val (u, t) = block(); s.signIn(u, t) } catch (e: Exception) { err = Auth.message(e) } finally { busy = false } }
    }
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(76.dp).background(Brush.linearGradient(listOf(Color(0xFF2F6BFF), Color(0xFF22C4F0))), RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(88.dp))
        }
        Spacer(Modifier.height(16.dp)); Text("EZ Note", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
        Text("Jot it. Clip it. Done.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        OutlinedTextField(name, { name = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
        if (usePw) OutlinedTextField(pw, { pw = it }, label = { Text("Password (8+ characters)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp), shape = RoundedCornerShape(14.dp))
        Spacer(Modifier.height(16.dp))
        Button({ if (usePw) go { Auth.passwordLogin(name.trim(), pw) } else go { Auth.passkeyLogin(ctx as Activity) } },
            enabled = !busy && (!usePw || (name.isNotBlank() && pw.isNotEmpty())), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(if (usePw) "Sign in" else "Sign in with a passkey", fontWeight = FontWeight.SemiBold)
        }
        OutlinedButton({ if (usePw) go { Auth.passwordRegister(name.trim(), pw) } else go { Auth.passkeyRegister(ctx as Activity, name.trim()) } },
            enabled = !busy && name.isNotBlank() && (!usePw || pw.isNotEmpty()), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(52.dp).padding(top = 8.dp)) {
            Text(if (usePw) "Create account" else "Create account with a passkey")
        }
        TextButton({ usePw = !usePw; err = null }) { Text(if (usePw) "Use a passkey instead" else "Use a password instead") }
        if (busy) CircularProgressIndicator(Modifier.padding(top = 8.dp).size(24.dp))
        err?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        TextButton({ s.signIn("Guest", null) }) { Text("Use without an account") }
    }
}
