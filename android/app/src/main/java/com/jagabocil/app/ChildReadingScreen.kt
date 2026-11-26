package com.jagabocil.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable internal fun ChildHome(vm: AppViewModel, data: Dashboard) {
    val child = data.children.firstOrNull() ?: return
    val assigned = child.readingAssignment != "free"
    val readingBook = child.readingAssignment == "book" || (!assigned && data.bookLibrary && vm.readingBook)
    val bookTitle = if (assigned) child.bookReading?.title else vm.selectedBook?.title
    val bookText = if (assigned) child.bookReading?.text else vm.selectedBook?.text
    val surah = vm.quran.chapter(if (assigned) child.surahId else vm.selectedSurahId ?: child.surahId)
    val ayahs = surah?.ayahs
    val context = LocalContext.current
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startRecording() else vm.message = "Izinkan mikrofon untuk merekam bacaanmu."
    }
    var showSurahs by rememberSaveable(assigned) { mutableStateOf(false) }
    var expiryClock by remember { mutableLongStateOf(vm.serverNow()) }
    LaunchedEffect(data.recitations) {
        while (true) {
            expiryClock = vm.serverNow()
            val nextExpiry = data.recitations.firstOrNull { it.canUse(expiryClock) }?.expiresAt ?: break
            delay((nextExpiry - expiryClock).coerceAtLeast(1))
        }
    }
    val step = readingStep(vm.recording, vm.recordedFile != null, data.recitations, maxOf(expiryClock, vm.serverNow()))
    val last = data.recitations.firstOrNull()
    val readingTitle = if (readingBook) bookTitle ?: "Baca buku" else surah?.name ?: "Bacaan belum tersedia"
    val canRecord = if (readingBook) bookText != null else ayahs != null
    val tvReady = when {
        child.awaitsTv -> false
        child.tvTransport == "infrared" -> vm.infrared.available
        else -> vm.tvPaired
    }
    fun start() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.startRecording()
        else microphone.launch(Manifest.permission.RECORD_AUDIO)
    }
    BoxWithConstraints(Modifier.widthIn(max = 680.dp).fillMaxSize().padding(horizontal = 20.dp)) {
        val inlineControls = maxHeight < 480.dp || LocalDensity.current.fontScale > 1.4f
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    if (!vm.recording) SectionTitle("Halo, ${child.name}")
                    Spacer(Modifier.height(10.dp))
                    when (step) {
                        is ReadingStep.Approved -> ReadingBanner("Izin TV siap", "Bacaan disetujui", step.recitation.readingTitle(vm.quran), Mint, Icons.Outlined.Tv)
                        is ReadingStep.Waiting -> ReadingBanner(null, "Menunggu diperiksa", step.recitation.readingTitle(vm.quran), Butter, Icons.Outlined.Headphones)
                        ReadingStep.Preview -> ReadingBanner(null, "Periksa rekamanmu", "$readingTitle · ${vm.seconds} detik", Rose, Icons.Outlined.Headphones)
                        ReadingStep.Read, ReadingStep.Recording -> ReadingBanner(
                            if (assigned) "Tugas dari orang tua" else "Bacaan untuk izin TV", readingTitle,
                            when {
                                !canRecord -> if (assigned || !readingBook) "Coba perbarui dari menu akun." else "Pilih cerita yang ingin kamu baca."
                                readingBook -> "Baca cerita dan rekam minimal ${child.minSeconds} detik."
                                else -> "${surah?.verses} ayat · Rekam minimal ${child.minSeconds} detik"
                            }, Sky)
                    }
                }
                if (step == ReadingStep.Read && !assigned && data.bookLibrary) item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilterChip(selected = !readingBook, onClick = { vm.selectReading(false) }, label = { Text("Ngaji") })
                        FilterChip(selected = readingBook, onClick = { if (!vm.busy) vm.screen = "books" }, label = { Text("Baca buku") })
                    }
                }
                if (step == ReadingStep.Read && !assigned && !readingBook) item {
                    TaskLink("Pilih surah", "Pilih bacaan dari Juz 30.", Icons.AutoMirrored.Outlined.MenuBook,
                        Sky, enabled = !vm.busy) { showSurahs = true }
                }
                if (!step.showsVerses || inlineControls) {
                    item { ReadingControls(vm, step, child, last, tvReady, canRecord, ::start) }
                }
                if (step.showsVerses) {
                    if (readingBook) {
                        if (bookText != null) item { StoryBody(bookText) }
                    } else if (surah != null && ayahs != null) {
                        quranVerses(surah.id, ayahs)
                        item { QuranAttribution() }
                    }
                }
            }
            if (step.showsVerses && !inlineControls) {
                ReadingControls(vm, step, child, last, tvReady, canRecord, ::start)
                Spacer(Modifier.height(4.dp))
            }
        }
    }
    if (showSurahs && !assigned) QuranTaskPicker(vm.quran.juz30, surah?.id.orEmpty(), { showSurahs = false }) {
        vm.selectSurah(it)
        showSurahs = false
    }
}

@Composable private fun ReadingControls(
    vm: AppViewModel, step: ReadingStep, child: Child, last: Recitation?,
    tvReady: Boolean, canRecord: Boolean, onRecord: () -> Unit,
) {
    val color = when (step) {
        ReadingStep.Recording -> Rose
        is ReadingStep.Waiting -> Butter
        is ReadingStep.Approved -> Mint
        else -> MaterialTheme.colorScheme.surfaceContainerLowest
    }
    Surface(color = color, shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (step) {
                ReadingStep.Recording -> {
                    Text("Sedang merekam · %02d:%02d".format(vm.seconds / 60, vm.seconds % 60), style = MaterialTheme.typography.titleMedium)
                    val remaining = (child.minSeconds - vm.seconds).coerceAtLeast(0)
                    Text(if (remaining > 0) "Baca setidaknya $remaining detik lagi." else "Tekan selesai setelah kamu selesai membaca.", color = Muted)
                    Button(onClick = vm::finishRecording, enabled = !vm.busy && remaining == 0, modifier = Modifier.fillMaxWidth()) { Text("Selesai merekam") }
                    TextButton(onClick = vm::discardRecording, enabled = !vm.busy) { Text("Batalkan rekaman") }
                }
                ReadingStep.Preview -> {
                    val longEnough = vm.seconds >= child.minSeconds
                    if (longEnough) {
                        OutlinedButton(onClick = vm::previewRecording, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                            Text(if (vm.playing == "local") "Hentikan audio" else "Dengarkan rekaman")
                        }
                        Button(onClick = vm::upload, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Kirim bacaan") }
                    } else Text("Rekaman perlu minimal ${child.minSeconds} detik.", color = Amber)
                    TextButton(onClick = vm::discardRecording, enabled = !vm.busy) { Text("Hapus dan kembali membaca") }
                }
                is ReadingStep.Approved -> {
                    val activePermit = step.recitation
                    activePermit.expiresAt?.let { Text("Izin berlaku sampai ${time(it)} untuk satu kali menyalakan TV.", color = Muted) }
                    if (tvReady && child.tvTransport == "infrared") Text("Arahkan ujung HP ke TV.", color = Muted)
                    Button(onClick = {
                        if (tvReady) vm.power(activePermit) else vm.openTvSetup()
                    }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (tvReady) "Nyalakan TV" else "Pilih atau hubungkan TV")
                    }
                }
                is ReadingStep.Waiting -> {
                    Text(if (child.mode == "ai") "Hasil pemeriksaan akan muncul di sini." else "Ayah atau Ibu akan mendengarkan bacaanmu.", color = Muted)
                    OutlinedButton(
                        onClick = { vm.checkReadingResult(step.recitation.id) },
                        enabled = !vm.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (vm.checkingReadingResult) {
                            CircularProgressIndicator(
                                Modifier.size(16.dp),
                                color = Forest,
                                strokeWidth = 2.dp,
                                trackColor = Forest.copy(alpha = 0.16f),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Mengecek hasil…", color = Forest)
                        } else {
                            Text("Cek hasil")
                        }
                    }
                }
                ReadingStep.Read -> {
                    if (last?.status == "rejected") Text(last.feedback.ifBlank { "Ayah atau Ibu meminta kamu mengulang bacaan." })
                    Button(onClick = onRecord, enabled = !vm.busy && canRecord, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Outlined.Mic, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (last?.status == "rejected") "Rekam ulang" else "Mulai rekam")
                    }
                }
            }
            if (step == ReadingStep.Read || step is ReadingStep.Waiting) {
                TextButton(onClick = vm::openTvSetup, enabled = !vm.busy) {
                    Text(if (child.awaitsTv) "Pilih TV" else "TV saya")
                }
            }
        }
    }
}
