package com.jagabocil.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.serialization.encodeToString

@Composable internal fun MultiReadingTaskScreen(vm: AppViewModel, child: Child, onBack: () -> Unit) {
    val initial = when (child.readingAssignment) {
        "quran" -> listOf(ReadingTask("quran", surahId = child.surahId))
        "book" -> listOfNotNull(child.bookReading?.let { ReadingTask("book", title = it.title, text = it.text) })
        else -> child.readingTasks
    }
    val saver = Saver<List<ReadingTask>, String>(
        save = { vm.api.json.encodeToString(it) },
        restore = { vm.api.json.decodeFromString(it) },
    )
    var tasks by rememberSaveable(child.id, stateSaver = saver) { mutableStateOf(initial) }
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    var addingKind by rememberSaveable { mutableStateOf("quran") }
    val index = editing
    BackHandler(enabled = index != null) { editing = null }
    if (index != null) {
        key(index, addingKind) {
            EditReadingTask(vm, tasks.getOrNull(index) ?: ReadingTask(addingKind), onBack = { editing = null }) { task ->
                tasks = if (index == tasks.size) tasks + task else tasks.toMutableList().apply { set(index, task) }
                editing = null
            }
        }
        return
    }
    val duplicate = tasks.distinctBy { it.copy(id = "") }.size != tasks.size
    Column(Modifier.widthIn(max = 680.dp).fillMaxSize().padding(horizontal = 20.dp)) {
        LibraryHeader("Tugas bacaan", { if (!vm.busy) onBack() })
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionTitle("Bacaan untuk ${child.name}")
            Text(if (tasks.isEmpty()) "Tanpa tugas, ${child.name} bisa memilih surah atau buku sendiri."
                else "Izin TV siap setelah semua tugas disetujui orang tua.", color = Muted)
            tasks.forEachIndexed { position, task ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val book = task.kind == "book"
                    Box(Modifier.weight(1f)) {
                        TaskLink(task.title(vm.quran), if (book) "Baca buku" else task.verseLabel ?: "Surah penuh",
                            if (book) Icons.Outlined.Book else Icons.AutoMirrored.Outlined.MenuBook,
                            if (book) Butter else Sky, enabled = !vm.busy) { editing = position }
                    }
                    IconButton(enabled = !vm.busy, onClick = { tasks = tasks.filterIndexed { i, _ -> i != position } }) {
                        Icon(Icons.Outlined.DeleteOutline, "Hapus tugas ${task.title(vm.quran)}")
                    }
                }
            }
            if (tasks.size < 10) {
                OutlinedButton(onClick = { addingKind = "quran"; editing = tasks.size },
                    enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Tambah tugas ngaji") }
                OutlinedButton(onClick = { addingKind = "book"; editing = tasks.size },
                    enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Tambah tugas baca buku") }
            }
            if (duplicate) Text("Pilih bacaan atau rentang ayat yang berbeda untuk setiap tugas.", color = Amber)
            if (tasks != initial) {
                Text("Menyimpan perubahan akan membatalkan pemeriksaan dan izin TV yang masih aktif.",
                    color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        Button(
            onClick = { vm.saveReadingTasks(child, tasks, onBack) },
            enabled = !vm.busy && tasks != initial && !duplicate,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).heightIn(min = 52.dp),
        ) { Text("Simpan tugas") }
    }
}

@Composable private fun EditReadingTask(
    vm: AppViewModel, task: ReadingTask, onBack: () -> Unit, onSave: (ReadingTask) -> Unit,
) {
    val book = task.kind == "book"
    var surahId by rememberSaveable { mutableStateOf(task.surahId.orEmpty()) }
    var partial by rememberSaveable { mutableStateOf(task.firstAyah != null) }
    var first by rememberSaveable { mutableStateOf(task.firstAyah?.toString() ?: "1") }
    var last by rememberSaveable { mutableStateOf(task.lastAyah?.toString() ?: "1") }
    var title by rememberSaveable { mutableStateOf(task.title.orEmpty()) }
    var text by rememberSaveable { mutableStateOf(task.text.orEmpty()) }
    var custom by rememberSaveable {
        mutableStateOf(task.title != null && vm.bookTemplates.none { it.title == title && it.text == text })
    }
    var showSurahs by rememberSaveable { mutableStateOf(false) }
    var showBooks by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showBooks) { showBooks = false }
    if (showBooks) {
        BookLibraryScreen(vm, onPick = { choice ->
            title = choice.title
            text = choice.text
            custom = false
            showBooks = false
        }, onExit = { showBooks = false })
        return
    }
    val chapter = vm.quran.chapter(surahId)
    val rangeStart = first.toIntOrNull()
    val rangeEnd = last.toIntOrNull()
    val rangeValid = chapter != null && rangeStart != null && rangeEnd != null &&
        rangeStart in 1..chapter.verses && rangeEnd in rangeStart..chapter.verses
    val enoughWords = remember(text) { hasEnoughBookWords(text) }
    val valid = if (book) title.isNotBlank() && enoughWords else chapter != null && (!partial || rangeValid)
    Column(Modifier.widthIn(max = 680.dp).fillMaxSize().imePadding().padding(horizontal = 20.dp)) {
        LibraryHeader(if (book) "Tugas baca buku" else "Tugas ngaji", onBack)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (book) {
                TaskLink(if (custom) "Pilih dari Rak Buku" else title.ifBlank { "Pilih cerita" },
                    "Buka koleksi cerita pendek", Icons.Outlined.Book, Butter) { showBooks = true }
                if (custom) {
                    OutlinedTextField(title, { title = it.take(80) }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Judul bacaan") })
                    OutlinedTextField(text, { text = it.take(1600) }, Modifier.fillMaxWidth(), minLines = 4,
                        label = { Text("Teks bacaan") }, supportingText = { Text("Minimal 8 kata · ${text.length}/1600 karakter") })
                } else TextButton(onClick = { custom = true }) { Text("Tulis bacaan sendiri") }
            } else {
                TaskLink(chapter?.name ?: "Pilih surah", chapter?.let { "${it.verses} ayat · Ketuk untuk mengganti" }
                    ?: "Pilih surah dari Juz 30", Icons.AutoMirrored.Outlined.MenuBook, Sky) { showSurahs = true }
                if (chapter != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !partial, onClick = { partial = false }, label = { Text("Surah penuh") })
                        FilterChip(selected = partial, onClick = { partial = true }, label = { Text("Pilih ayat") })
                    }
                    if (partial) {
                        val options = KeyboardOptions(keyboardType = KeyboardType.Number)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(first, { first = it.filter(Char::isDigit).take(3) }, Modifier.weight(1f),
                                singleLine = true, label = { Text("Dari ayat") }, keyboardOptions = options)
                            OutlinedTextField(last, { last = it.filter(Char::isDigit).take(3) }, Modifier.weight(1f),
                                singleLine = true, label = { Text("Sampai ayat") }, keyboardOptions = options)
                        }
                        Text("Gunakan nomor ayat 1–${chapter.verses}. Untuk satu ayat, isi kedua nomor dengan angka yang sama.",
                            color = if (rangeValid) Muted else Amber, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Button(
            onClick = {
                val range = partial && (rangeStart != 1 || rangeEnd != chapter?.verses)
                onSave(if (book) task.copy(title = title.trim(), text = text.trim())
                    else task.copy(surahId = surahId, firstAyah = rangeStart.takeIf { range }, lastAyah = rangeEnd.takeIf { range }))
            },
            enabled = valid,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).heightIn(min = 52.dp),
        ) { Text("Pakai bacaan ini") }
    }
    if (showSurahs) QuranTaskPicker(vm.quran.juz30, surahId, { showSurahs = false }) { choice ->
        if (surahId != choice) { first = "1"; last = "1" }
        surahId = choice
        showSurahs = false
    }
}
