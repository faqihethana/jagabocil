package com.jagabocil.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

@Composable internal fun AiReadingSummary(recording: Recitation) {
    if (!recording.hasTranscript) return
    Surface(color = Mint, contentColor = Forest, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.AutoMirrored.Outlined.FactCheck, null, modifier = Modifier.size(18.dp))
                Text("Diperiksa AI", style = MaterialTheme.typography.titleSmall)
            }
            recording.matchScore?.let { score ->
                Text("Perkiraan kemiripan teks · $score%", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable internal fun ReadingDetailsPanel(vm: AppViewModel, recording: Recitation, initiallyExpanded: Boolean) {
    var expanded by rememberSaveable(recording.id) { mutableStateOf(initiallyExpanded) }
    val book = recording.readingKind == "book"
    TextButton(onClick = { expanded = !expanded }) {
        Text(when {
            expanded -> "Tutup rincian bacaan"
            book -> if (recording.hasTranscript) "Lihat transkripsi dan cerita" else "Lihat teks cerita"
            else -> if (recording.hasTranscript) "Lihat transkripsi dan surah" else "Lihat teks surah"
        })
    }
    if (!expanded) return

    var retry by remember { mutableIntStateOf(0) }
    val result by produceState<Result<ReadingDetails>?>(null, recording.id, recording.hasTranscript, recording.matchScore, retry) {
        value = null
        value = try {
            Result.success(vm.api.readingDetails(recording.id))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
    val details = result?.getOrNull()
    when {
        result == null -> CircularProgressIndicator(Modifier.size(24.dp))
        details == null -> TextButton(onClick = { retry++ }) { Text("Rincian belum terbuka. Coba lagi") }
        else -> {
            val reference = details.referenceText ?: if (book) null else vm.quran.chapter(recording.surahId)?.ayahs?.joinToString("\n")
            Surface(color = Soft, shape = RoundedCornerShape(16.dp)) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState()).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (recording.matchScore == null) details.matchScore?.let { score ->
                        Text("Perkiraan kemiripan teks · $score%", style = MaterialTheme.typography.titleMedium)
                    }
                    if (details.transcript != null) {
                        Text("Transkripsi AI", style = MaterialTheme.typography.titleSmall)
                        if (book) {
                            Text(details.transcript, style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content))
                            Text("Ambang kemiripan buku ${details.matchThreshold}%", style = MaterialTheme.typography.bodySmall, color = Muted)
                        } else {
                            ArabicText(details.vocalizedTranscript ?: details.transcript, Modifier.fillMaxWidth())
                            Text("Harakat mengikuti teks surah pada kata yang cocok. Dengarkan audio untuk menilai pelafalan dan tajwid.",
                                style = MaterialTheme.typography.bodySmall, color = Muted)
                        }
                    }
                    if (reference != null) {
                        Text(if (book) "Teks cerita" else "Teks surah", style = MaterialTheme.typography.titleSmall)
                        if (book) Text(reference, style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content))
                        else {
                            ArabicText(reference, Modifier.fillMaxWidth())
                            QuranAttribution()
                        }
                    }
                }
            }
        }
    }
}
