package com.jagabocil.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.Normalizer

private val ReadingMarks = Regex("\\p{M}")
private val ReadingWord = Regex("[\\p{L}\\p{N}]+")
internal fun hasEnoughBookWords(text: String) =
    ReadingWord.findAll(Normalizer.normalize(text, Normalizer.Form.NFKD).replace(ReadingMarks, "")).take(8).count() == 8

@Composable internal fun ReadingTaskScreen(vm: AppViewModel, child: Child, onBack: () -> Unit) {
    var kind by rememberSaveable { mutableStateOf(child.readingAssignment) }
    var surahId by rememberSaveable { mutableStateOf(child.surahId.takeIf { child.readingAssignment == "quran" }.orEmpty()) }
    val currentBook = child.bookReading.takeIf { child.readingAssignment == "book" }
    val template = remember(currentBook) {
        currentBook?.let { book -> vm.bookTemplates.find { it.title == book.title && it.text == book.text } }
    }
    var bookId by rememberSaveable { mutableStateOf(template?.id.orEmpty()) }
    var custom by rememberSaveable { mutableStateOf(currentBook != null && template == null) }
    var title by rememberSaveable { mutableStateOf(currentBook?.title.orEmpty()) }
    var text by rememberSaveable { mutableStateOf(currentBook?.text.orEmpty()) }
    var showSurahs by rememberSaveable { mutableStateOf(false) }
    var showBooks by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = showBooks) { showBooks = false }
    if (showBooks) {
        BookLibraryScreen(vm, onPick = { book ->
            bookId = book.id
            custom = false
            showBooks = false
        }) { showBooks = false }
        return
    }
    val selectedBook = vm.bookTemplates.find { it.id == bookId }
    val chapter = vm.quran.chapter(surahId)
    val enoughWords = remember(text) { hasEnoughBookWords(text) }
    val valid = when (kind) {
        "quran" -> chapter != null
        "book" -> if (custom) title.isNotBlank() && enoughWords else selectedBook != null
        else -> true
    }
    val changed = kind != child.readingAssignment || when (kind) {
        "quran" -> surahId != child.surahId
        "book" -> if (custom) title.trim() != currentBook?.title || text.trim() != currentBook?.text
            else selectedBook?.title != currentBook?.title || selectedBook?.text != currentBook?.text
        else -> false
    }
    Column(Modifier.widthIn(max = 680.dp).fillMaxSize().imePadding().padding(horizontal = 20.dp)) {
        LibraryHeader("Tugas bacaan", onBack = { if (!vm.busy) onBack() })
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("Bacaan untuk ${child.name}")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("free" to "Anak pilih sendiri", "quran" to "Ngaji", "book" to "Baca buku").forEach { (id, label) ->
                    FilterChip(selected = kind == id, enabled = !vm.busy, label = { Text(label) }, onClick = {
                        kind = id
                        if (id == "quran" && surahId.isEmpty()) showSurahs = true
                    })
                }
            }
            when (kind) {
                "quran" -> TaskLink(chapter?.name ?: "Pilih surah", chapter?.let { "${it.verses} ayat · Ketuk untuk mengganti" }
                    ?: "Pilih satu surah dari Juz 30.", Icons.AutoMirrored.Outlined.MenuBook, Sky, enabled = !vm.busy) {
                    showSurahs = true
                }
                "book" -> {
                    TaskLink(if (custom) "Pilih dari Rak Buku" else selectedBook?.title ?: "Pilih cerita",
                        if (!custom && selectedBook != null) "${selectedBook.theme} · Ketuk untuk mengganti" else "Lihat koleksi cerita pendek.",
                        Icons.Outlined.Book, Mint, enabled = !vm.busy) { showBooks = true }
                    if (custom) {
                        OutlinedTextField(title, { title = it.take(80) }, Modifier.fillMaxWidth(),
                            enabled = !vm.busy, label = { Text("Judul bacaan") }, singleLine = true)
                        OutlinedTextField(text, { text = it.take(1600) }, Modifier.fillMaxWidth(),
                            enabled = !vm.busy, label = { Text("Teks bacaan") }, minLines = 4, maxLines = 8,
                            supportingText = { Text("Minimal 8 kata · ${text.length}/1600 karakter") })
                    } else {
                        TextButton(onClick = { custom = true }, enabled = !vm.busy) { Text("Tulis bacaan sendiri") }
                    }
                }
                else -> Text("${child.name} bisa memilih surah atau cerita sebelum merekam.", color = Muted)
            }
            if (kind != "free") {
                Text("Tugas muncul di Beranda anak dan berlaku sampai kamu menggantinya.", color = Muted,
                    style = MaterialTheme.typography.bodyMedium)
            }
            if (changed) {
                Text("Mengganti tugas membatalkan rekaman yang menunggu dan izin TV yang belum dipakai.", color = Muted,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        Button(
            onClick = {
                val fields = when (kind) {
                    "quran" -> listOf("surahId" to surahId)
                    "book" -> if (custom) listOf("title" to title.trim(), "text" to text.trim()) else listOf("bookId" to bookId)
                    else -> emptyList()
                }
                vm.saveReadingTask(child, listOf("kind" to kind) + fields, onBack)
            },
            enabled = valid && changed && !vm.busy,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).heightIn(min = 52.dp),
        ) { Text("Simpan tugas") }
    }
    if (showSurahs) QuranTaskPicker(vm.quran.juz30, surahId, { showSurahs = false }) {
        surahId = it
        showSurahs = false
    }
}
