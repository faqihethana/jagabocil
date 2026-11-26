package com.jagabocil.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val Arabic = FontFamily(Font(R.font.amiri_quran))

@Composable internal fun QuranScreen(library: QuranLibrary, api: Api, onExit: () -> Unit) {
    var outsideJuz by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var downloaded by remember { mutableStateOf(emptySet<String>()) }
    val list = rememberLazyListState()
    val chapter = openId?.let(library::chapter)
    LaunchedEffect(library) { downloaded = library.downloaded() }
    LaunchedEffect(query, outsideJuz) { list.scrollToItem(0) }
    BackHandler(enabled = chapter != null) { openId = null }

    if (chapter != null) {
        key(chapter.id) {
            QuranChapterScreen(chapter, library, api, onBack = { openId = null }) {
                downloaded = downloaded + chapter.id
            }
        }
    } else {
        Column(Modifier.widthIn(max = 680.dp).fillMaxSize().padding(horizontal = 20.dp)) {
            LibraryHeader("Al-Qur’an", onExit)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !outsideJuz, onClick = { outsideJuz = false }, label = { Text("Juz 30") })
                FilterChip(selected = outsideJuz, onClick = { outsideJuz = true }, label = { Text("Surah lainnya") })
            }
            LibrarySearch(query, "Cari nama atau nomor surah") { query = it }
            val entries = remember(outsideJuz, query, library) {
                library.chapters.filter { (it.ayahs == null) == outsideJuz && it.matches(query) }
            }
            LazyColumn(
                state = list, modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp),
            ) {
                if (entries.isEmpty()) item {
                    Surface(color = Butter, shape = MaterialTheme.shapes.large) {
                        Text("Surah tidak ditemukan. Coba nama atau nomor lain.", Modifier.fillMaxWidth().padding(16.dp))
                    }
                }
                items(entries, key = { it.id }) { entry ->
                    val status = when {
                        entry.ayahs != null -> "Luring"
                        entry.id in downloaded -> "Tersimpan"
                        else -> "Perlu diunduh"
                    }
                    QuranRow(entry, status) { openId = entry.id }
                }
            }
            QuranAttribution()
        }
    }
}

@Composable private fun QuranChapterScreen(
    chapter: QuranChapter, library: QuranLibrary, api: Api, onBack: () -> Unit, onDownloaded: () -> Unit,
) {
    var ayahs by remember { mutableStateOf(chapter.ayahs) }
    var loading by remember { mutableStateOf(chapter.ayahs == null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(library, chapter) {
        ayahs = library.read(chapter)
        loading = false
    }
    Column(Modifier.widthIn(max = 680.dp).fillMaxSize().padding(horizontal = 20.dp)) {
        LibraryHeader(chapter.name, onBack)
        Text("${chapter.verses} ayat", Modifier.padding(start = 12.dp, bottom = 10.dp))
        val text = ayahs
        if (text != null) {
            LazyColumn(
                modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                quranVerses(chapter.id, text)
            }
        } else {
            Column(Modifier.weight(1f).fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (loading) {
                    CircularProgressIndicator()
                    Text("Memuat teks surah…")
                } else {
                    Text("Unduh teks agar surah ini bisa dibaca tanpa internet di HP ini.")
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(onClick = {
                        loading = true
                        error = null
                        scope.launch {
                            try {
                                ayahs = library.download(chapter, api)
                                onDownloaded()
                            } catch (failure: Exception) {
                                if (failure is CancellationException) throw failure
                                error = if (failure is ApiError && failure.status == 404) {
                                    "Server JagaBocil belum mendukung unduhan surah ini."
                                } else {
                                    "Surah belum berhasil diunduh. Periksa koneksi dan coba lagi."
                                }
                            } finally {
                                loading = false
                            }
                        }
                    }) { Text(if (error == null) "Unduh surah" else "Coba unduh lagi") }
                }
            }
        }
        QuranAttribution()
    }
}

@Composable internal fun QuranTaskPicker(
    chapters: List<QuranChapter>, selected: String, onDismiss: () -> Unit, onChoose: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val visible = remember(query, chapters) { chapters.filter { it.matches(query) } }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Pilih surah Juz 30") },
        text = {
            Column {
                LibrarySearch(query, "Cari nama atau nomor surah") { query = it }
                LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    if (visible.isEmpty()) item { Text("Surah tidak ditemukan.", Modifier.padding(vertical = 16.dp)) }
                    items(visible, key = { it.id }) { chapter ->
                        QuranRow(chapter, if (chapter.id == selected) "Dipilih" else null) { onChoose(chapter.id) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
    )
}

@Composable internal fun LibrarySearch(query: String, label: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query, onValueChange = onChange, label = { Text(label) }, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(), singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
    )
}

@Composable private fun QuranRow(chapter: QuranChapter, status: String?, onClick: () -> Unit) {
    val paper = MaterialTheme.colorScheme.surfaceContainerLowest
    Surface(onClick = onClick, color = paper, shape = RoundedCornerShape(16.dp)) {
        ListItem(
            headlineContent = { Text(chapter.name, fontWeight = FontWeight.SemiBold) },
            supportingContent = { Text("${chapter.verses} ayat") },
            leadingContent = {
                val accent = when (status) {
                    "Perlu diunduh" -> Sky
                    "Tersimpan", "Dipilih" -> Mint
                    else -> Butter
                }
                Box(Modifier.size(42.dp).background(accent, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    Text(chapter.id, fontWeight = FontWeight.SemiBold, color = Forest)
                }
            },
            trailingContent = status?.let { label -> { Text(label, color = Muted, style = MaterialTheme.typography.labelMedium) } },
            colors = ListItemDefaults.colors(containerColor = paper),
        )
    }
}

@Composable internal fun LibraryHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Kembali") }
        SectionTitle(title)
    }
}

internal fun LazyListScope.quranVerses(id: String, ayahs: List<String>) {
    // Tanzil includes the basmala before verse one except in Al-Fatihah and At-Tawbah.
    val first = if (id != "1" && id != "9") ayahs.first().split(' ', limit = 5) else emptyList()
    if (first.size == 5) {
        item { ArabicText(first.take(4).joinToString(" "), Modifier.fillMaxWidth(), TextAlign.Center) }
    }
    itemsIndexed(ayahs) { index, ayah ->
        QuranAyah(if (index == 0 && first.size == 5) first.last() else ayah, index + 1)
    }
}

@Composable private fun QuranAyah(ayah: String, number: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.padding(top = 5.dp, end = 12.dp).size(30.dp).background(Mint, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center) {
                Text(number.toString(), color = Forest, style = MaterialTheme.typography.labelMedium)
            }
            ArabicText(ayah, Modifier.weight(1f), TextAlign.Right)
        }
    }
}

@Composable private fun ArabicText(text: String, modifier: Modifier, alignment: TextAlign) {
    Text(
        text, modifier, style = TextStyle(
            fontFamily = Arabic, fontSize = 30.sp, lineHeight = 54.sp,
            textAlign = alignment, textDirection = TextDirection.Rtl,
        ),
    )
}

@Composable internal fun QuranAttribution() {
    val uri = LocalUriHandler.current
    TextButton(onClick = { uri.openUri("https://tanzil.net/") }) { Text("Teks Arab · Tanzil Project") }
}
