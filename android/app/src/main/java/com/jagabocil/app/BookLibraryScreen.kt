package com.jagabocil.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable internal fun BookLibraryScreen(vm: AppViewModel, onExit: () -> Unit) {
    val templates = vm.bookTemplates
    val themes = remember(templates) { templates.map { it.theme }.distinct() }
    val dashboard = vm.dashboard
    val childId = dashboard?.takeIf { it.role == "child" && it.bookLibrary }?.children?.firstOrNull()?.id
    var query by rememberSaveable { mutableStateOf("") }
    var theme by rememberSaveable { mutableStateOf("") }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    val template = openId?.let { id -> templates.find { it.id == id } }
    LaunchedEffect(childId) {
        if (childId != null) {
            try {
                vm.loadGeneratedStories(childId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Built-in stories remain accessible offline.
            }
        }
    }
    LaunchedEffect(query, theme) { list.scrollToItem(0) }
    BackHandler(enabled = template != null) { openId = null }

    if (template != null) {
        BookStoryScreen(vm, template, canUseForTv = childId != null, onBack = { openId = null })
        return
    }
    val saved = vm.generatedStories
    val entries = remember(templates, query, theme, saved) {
        val keyword = query.trim()
        templates.filter {
            (theme.isEmpty() || theme == it.theme) && (
                it.title.contains(keyword, ignoreCase = true) ||
                    it.theme.contains(keyword, ignoreCase = true) ||
                    saved[it.id]?.title?.contains(keyword, ignoreCase = true) == true
                )
        }
    }
    Column(Modifier.widthIn(max = 680.dp).fillMaxSize().padding(horizontal = 20.dp)) {
        LibraryHeader("Rak Buku", onExit)
        LibrarySearch(query, "Cari judul atau tema") { query = it }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = theme.isEmpty(), onClick = { theme = "" }, label = { Text("Semua") })
            themes.forEach { entry ->
                FilterChip(selected = theme == entry, onClick = { theme = entry }, label = { Text(entry) })
            }
        }
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 10.dp),
        ) {
            if (entries.isEmpty()) item { Text("Cerita tidak ditemukan.", Modifier.padding(16.dp), color = Muted) }
            items(entries, key = { it.id }) { book ->
                val accent = when (book.theme) {
                    "Hewan" -> Butter
                    "Alam" -> Mint
                    "Persahabatan" -> Rose
                    else -> Sky
                }
                val variant = saved[book.id]
                val subtitle = if (variant == null) book.theme else "${book.theme} · Versi AI tersimpan"
                TaskLink(book.title, subtitle, Icons.Outlined.Book, accent) { openId = book.id }
            }
        }
    }
}

@Composable private fun BookStoryScreen(vm: AppViewModel, template: Story, canUseForTv: Boolean, onBack: () -> Unit) {
    var showAi by rememberSaveable(template.id) { mutableStateOf(false) }
    var generating by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val variant = vm.generatedStories[template.id]
    val reading = if (showAi) variant ?: template else template
    Column(Modifier.widthIn(max = 680.dp).fillMaxSize().padding(horizontal = 20.dp)) {
        LibraryHeader(reading.title, onBack)
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            if (variant != null) item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !showAi, onClick = { showAi = false }, label = { Text("Cerita bawaan") })
                    FilterChip(selected = showAi, onClick = { showAi = true }, label = { Text("Versi AI") })
                }
            }
            item { StoryBody(reading.text) }
            if (canUseForTv && vm.dashboard?.storyGeneration == true && variant == null) item {
                OutlinedButton(onClick = {
                    generating = true
                    failure = null
                    scope.launch {
                        try {
                            vm.generateStory(template.id)
                            showAi = true
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            failure = (error as? ApiError)?.message ?: "Cerita AI belum tersedia. Coba lagi saat terhubung."
                        } finally {
                            generating = false
                        }
                    }
                }, enabled = !generating, modifier = Modifier.fillMaxWidth()) {
                    if (generating) {
                        CircularProgressIndicator(Modifier.padding(end = 4.dp).size(16.dp), strokeWidth = 2.dp)
                        Text("Membuat cerita…")
                    } else {
                        Text("Buat versi AI")
                    }
                }
            }
            failure?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        }
        if (canUseForTv) {
            Button(
                onClick = { vm.selectBook(reading) }, enabled = !vm.busy,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            ) { Text("Pakai untuk izin TV") }
        }
    }
}

@Composable internal fun StoryBody(text: String) {
    val paragraphs = remember(text) { text.split(Regex("\\n\\s*\\n")).filter { it.isNotBlank() } }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            paragraphs.forEach { paragraph ->
                Text(paragraph.trim(), style = MaterialTheme.typography.bodyLarge.copy(fontSize = 19.sp, lineHeight = 29.sp))
            }
        }
    }
}
