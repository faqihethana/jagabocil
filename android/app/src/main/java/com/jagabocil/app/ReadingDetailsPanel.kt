package com.jagabocil.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

@Composable internal fun ReadingDetailsPanel(vm: AppViewModel, recording: Recitation) {
    var expanded by rememberSaveable(recording.id) { mutableStateOf(false) }
    val book = recording.readingKind == "book"
    TextButton(onClick = { expanded = !expanded }) {
        Text(when {
            expanded -> "Tutup rincian bacaan"
            book -> "Lihat cerita dan transkripsi"
            else -> "Lihat transkripsi dan surah"
        })
    }
    if (!expanded) return

    var retry by remember { mutableIntStateOf(0) }
    val result by produceState<Result<ReadingDetails>?>(null, recording.id, recording.hasTranscript, retry) {
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
            val reference = if (book) details.referenceText else vm.quran.chapter(recording.surahId)?.ayahs?.joinToString("\n")
            Surface(color = Soft, shape = RoundedCornerShape(16.dp)) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState()).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    details.matchScore?.let { score ->
                        Text("Kemiripan $score% · Ambang ${details.matchThreshold}%", style = MaterialTheme.typography.titleMedium)
                    }
                    if (reference != null) {
                        Text(if (book) "Teks cerita" else "Teks surah", style = MaterialTheme.typography.titleSmall)
                        Text(reference, style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content))
                    }
                    Text("Transkripsi AI", style = MaterialTheme.typography.titleSmall)
                    Text(details.transcript ?: "Transkripsi belum tersedia. Dengarkan rekamannya.",
                        style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content))
                }
            }
        }
    }
}
