package com.jagabocil.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Book
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
    if (child.readingAssignment == "plan") {
        TaskPlanHome(vm, data, child)
        return
    }
    var showSurahs by rememberSaveable { mutableStateOf(false) }
    var expiryClock by remember { mutableLongStateOf(vm.serverNow()) }
    LaunchedEffect(data.recitations) {
        while (true) {
            expiryClock = vm.serverNow()
            val expiry = data.recitations.firstOrNull { it.canUse(expiryClock) }?.expiresAt ?: break
            delay((expiry - expiryClock).coerceAtLeast(1))
        }
    }
    val step = readingStep(false, false, data.recitations, maxOf(expiryClock, vm.serverNow()))
    Header(vm, data)
    Column(Modifier.widthIn(max = 680.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        when (step) {
            is ReadingStep.Approved, is ReadingStep.Waiting -> ReadingResultCard(vm, child, step)
            else -> {
                val assignment = child.readingAssignment
                val retry = data.recitations.firstOrNull()?.takeIf { it.status == "rejected" }
                ReadingBanner(
                    null,
                    if (assignment == "free") "Mau baca apa hari ini?" else "Tugas bacaanmu",
                    "Baca, rekam, lalu kirim ke orang tua untuk mendapat izin TV.",
                    Peach, illustrated = true,
                )
                retry?.let { Text(it.feedback.ifBlank { "Yuk, coba baca lagi." }, color = Amber) }
                if (assignment == "free") {
                    TaskLink("Ngaji", "Pilih surah Juz 30", Icons.AutoMirrored.Outlined.MenuBook, Sky,
                        enabled = !vm.busy) { showSurahs = true }
                    if (data.bookLibrary) {
                        TaskLink("Baca buku", "Pilih cerita dari rak buku", Icons.Outlined.Book, Butter,
                            enabled = !vm.busy) { vm.openBooks() }
                    }
                    if (data.bookLibrary && vm.selectedBook != null) {
                        TextButton(onClick = { vm.selectedBook?.let(vm::selectBook) }, enabled = !vm.busy) {
                            Text("Lanjut baca ${vm.selectedBook?.title.orEmpty()}")
                        }
                    }
                } else {
                    val book = assignment == "book"
                    TaskLink(if (book) child.bookReading?.title ?: "Baca buku" else vm.quran.name(child.surahId),
                        if (book) "Mulai baca cerita" else "Mulai ngaji",
                        if (book) Icons.Outlined.Book else Icons.AutoMirrored.Outlined.MenuBook, Sky,
                        enabled = !vm.busy) { vm.screen = "child-reading" }
                }
            }
        }
        if (step !is ReadingStep.Approved) {
            TextButton(onClick = vm::openTvSetup, enabled = !vm.busy) {
                Text(if (child.awaitsTv) "Pilih TV" else "TV saya")
            }
        }
        Spacer(Modifier.height(4.dp))
    }
    if (showSurahs && child.readingAssignment == "free") {
        ChildSurahPicker(vm, data, child) { showSurahs = false }
    }
}

@Composable private fun TaskPlanHome(vm: AppViewModel, data: Dashboard, child: Child) {
    val permit = data.taskPermit
    var expired by remember(permit) { mutableStateOf(false) }
    LaunchedEffect(permit) {
        if (permit != null) {
            delay((permit.expiresAt - vm.serverNow()).coerceAtLeast(0))
            expired = true
            vm.refresh()
        }
    }
    val recitations = data.activeTaskRecitations.associateBy { it.taskId }
    val approved = recitations.values.count { it.status == "approved" }
    val waiting = recitations.values.firstOrNull { it.status == "pending" }
    Header(vm, data)
    Column(
        Modifier.widthIn(max = 680.dp).fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ReadingBanner(
            null,
            if (permit != null && !expired) "Izin TV siap" else "Tugas bacaanmu",
            "$approved dari ${child.readingTasks.size} tugas disetujui.",
            if (permit != null && !expired) Mint else Sky,
            if (permit != null && !expired) Icons.Outlined.Tv else Icons.AutoMirrored.Outlined.MenuBook,
        )
        if (permit == null) {
            Text("Kerjakan tugas dalam urutan apa saja. Izin TV siap setelah semuanya disetujui.", color = Muted)
        } else if (expired) {
            TaskLink("Perbarui tugas", "Izin TV sudah kedaluwarsa.", Icons.Outlined.Tv, Butter,
                enabled = !vm.busy, onClick = vm::refresh)
        } else {
            TvPermitControls(vm, child, permit.recitationId, permit.expiresAt)
        }
        child.readingTasks.forEach { task ->
            key(task.id) {
                val recitation = recitations[task.id]
                val retry = data.recitations.firstOrNull {
                    it.taskId == task.id && it.taskCycle == child.readingCycle
                }?.status == "rejected"
                val status = when (recitation?.status) {
                    "approved" -> "Disetujui"
                    "pending" -> "Menunggu orang tua"
                    else -> if (retry) "Rekam ulang" else "Mulai"
                }
                val book = task.kind == "book"
                val detail = listOfNotNull(if (book) "Baca buku" else task.verseLabel ?: "Surah penuh", status).joinToString(" · ")
                TaskLink(task.title(vm.quran), detail,
                    if (book) Icons.Outlined.Book else Icons.AutoMirrored.Outlined.MenuBook,
                    if (recitation?.status == "approved") Mint else if (book) Butter else Sky,
                    enabled = !vm.busy && recitation == null && permit == null) { vm.openTask(task) }
                recitation?.let { AiReadingSummary(it) }
            }
        }
        if (waiting != null) OutlinedButton(
            onClick = { vm.checkReadingResult(waiting.id) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth(),
        ) { Text(if (vm.checkingReadingResult) "Mengecek hasil…" else "Cek hasil") }
        if (permit == null) TextButton(onClick = vm::openTvSetup, enabled = !vm.busy) {
            Text(if (child.awaitsTv) "Pilih TV" else "TV saya")
        }
    }
}

@Composable private fun ChildSurahPicker(vm: AppViewModel, data: Dashboard, child: Child, onClose: () -> Unit) {
    val surahs = if (data.juz30Tasks) vm.quran.juz30
        else vm.quran.juz30.filter { chapter -> data.surahs.any { it.id == chapter.id } }
    QuranTaskPicker(surahs, vm.selectedSurahId ?: child.surahId, onClose) {
        vm.selectSurah(it)
        onClose()
    }
}

@Composable internal fun ChildReadingScreen(vm: AppViewModel, data: Dashboard) {
    val child = data.children.firstOrNull() ?: return
    val assigned = child.readingAssignment != "free"
    val task = vm.selectedTask(child)
    val readingBook = task?.kind == "book" || child.readingAssignment == "book" || (!assigned && data.bookLibrary && vm.readingBook)
    val bookTitle = task?.title ?: if (assigned) child.bookReading?.title else vm.selectedBook?.title
    val bookText = task?.text ?: if (assigned) child.bookReading?.text else vm.selectedBook?.text
    val surah = vm.quran.chapter(task?.surahId ?: if (assigned) child.surahId else vm.selectedSurahId ?: child.surahId)
    val firstAyah = task?.firstAyah ?: 1
    val ayahs = surah?.ayahs?.let { verses ->
        if (task?.lastAyah != null) verses.subList(firstAyah - 1, task.lastAyah) else verses
    }
    val minSeconds = task?.minSeconds(child) ?: child.minSeconds
    val context = LocalContext.current
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startRecording() else vm.message = "Izinkan mikrofon untuk merekam bacaanmu."
    }
    var showSurahs by rememberSaveable(assigned) { mutableStateOf(false) }
    val inPlan = child.readingAssignment == "plan"
    val taskActive = task != null && data.activeTaskRecitations.any { it.taskId == task.id }
    val step = readingStep(vm.recording, vm.recordedFile != null, if (inPlan) emptyList() else data.recitations, vm.serverNow())
    if (step is ReadingStep.Waiting || step is ReadingStep.Approved ||
        inPlan && (task == null || taskActive && step == ReadingStep.Read)) {
        LaunchedEffect(Unit) { vm.screen = "home" }
        return
    }
    val last = if (inPlan) data.recitations.firstOrNull { it.taskId == task?.id && it.taskCycle == child.readingCycle }
        else data.recitations.firstOrNull()
    val readingTitle = if (readingBook) bookTitle ?: "Baca buku" else surah?.name ?: "Bacaan belum tersedia"
    val canRecord = if (readingBook) bookText != null else ayahs != null
    fun start() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.startRecording()
        else microphone.launch(Manifest.permission.RECORD_AUDIO)
    }
    BoxWithConstraints(Modifier.widthIn(max = 680.dp).fillMaxSize().padding(horizontal = 20.dp)) {
        val inlineControls = maxHeight < 480.dp || LocalDensity.current.fontScale > 1.4f
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (step == ReadingStep.Read) {
                LibraryHeader(if (readingBook) "Baca buku" else "Ngaji", { vm.screen = "home" })
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    when (step) {
                        is ReadingStep.Approved, is ReadingStep.Waiting -> Unit
                        ReadingStep.Preview -> ReadingBanner(null, "Periksa rekamanmu", "$readingTitle · ${vm.seconds} detik", Rose, Icons.Outlined.Headphones)
                        ReadingStep.Read, ReadingStep.Recording -> ReadingBanner(
                            if (assigned) "Tugas dari orang tua" else "Bacaan untuk izin TV", readingTitle,
                            when {
                                !canRecord -> "Kembali ke Beranda dan perbarui dari menu akun."
                                readingBook -> "Baca cerita dan rekam minimal $minSeconds detik."
                                else -> "${task?.verseLabel ?: "${surah?.verses} ayat"} · Rekam minimal $minSeconds detik"
                            }, Sky)
                    }
                }
                if (step == ReadingStep.Read && !assigned) item {
                    TextButton(onClick = {
                        if (readingBook) vm.openBooks("child-reading") else showSurahs = true
                    }, enabled = !vm.busy) { Text(if (readingBook) "Ganti cerita" else "Ganti surah") }
                }
                if (!step.showsVerses || inlineControls) {
                    item { ReadingControls(vm, step, minSeconds, last, canRecord, ::start) }
                }
                if (step.showsVerses) {
                    if (readingBook) {
                        if (bookText != null) item { StoryBody(bookText) }
                    } else if (surah != null && ayahs != null) {
                        quranVerses(surah.id, ayahs, firstAyah)
                        item { QuranAttribution() }
                    }
                }
            }
            if (step.showsVerses && !inlineControls) {
                ReadingControls(vm, step, minSeconds, last, canRecord, ::start)
                Spacer(Modifier.height(4.dp))
            }
        }
    }
    if (showSurahs && !assigned) ChildSurahPicker(vm, data, child) { showSurahs = false }
}

@Composable private fun ReadingControls(
    vm: AppViewModel, step: ReadingStep, minSeconds: Int, last: Recitation?,
    canRecord: Boolean, onRecord: () -> Unit,
) {
    val color = when (step) {
        ReadingStep.Recording -> Rose
        else -> MaterialTheme.colorScheme.surfaceContainerLowest
    }
    Surface(color = color, shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (step) {
                ReadingStep.Recording -> {
                    Text("Sedang merekam · %02d:%02d".format(vm.seconds / 60, vm.seconds % 60), style = MaterialTheme.typography.titleMedium)
                    val remaining = (minSeconds - vm.seconds).coerceAtLeast(0)
                    Text(if (remaining > 0) "Baca setidaknya $remaining detik lagi." else "Tekan selesai setelah kamu selesai membaca.", color = Muted)
                    Button(onClick = vm::finishRecording, enabled = !vm.busy && remaining == 0, modifier = Modifier.fillMaxWidth()) { Text("Selesai merekam") }
                    TextButton(onClick = vm::discardRecording, enabled = !vm.busy) { Text("Batalkan rekaman") }
                }
                ReadingStep.Preview -> {
                    val longEnough = vm.seconds >= minSeconds
                    if (longEnough) {
                        OutlinedButton(onClick = vm::previewRecording, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                            Text(if (vm.playing == "local") "Hentikan audio" else "Dengarkan rekaman")
                        }
                        Button(onClick = vm::upload, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Kirim bacaan") }
                    } else Text("Rekaman perlu minimal $minSeconds detik.", color = Amber)
                    TextButton(onClick = vm::discardRecording, enabled = !vm.busy) { Text("Hapus dan kembali membaca") }
                }
                is ReadingStep.Approved, is ReadingStep.Waiting -> Unit
                ReadingStep.Read -> {
                    if (last?.status == "rejected") Text(last.feedback.ifBlank { "Ayah atau Ibu meminta kamu mengulang bacaan." })
                    Button(onClick = onRecord, enabled = !vm.busy && canRecord, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Outlined.Mic, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (last?.status == "rejected") "Rekam ulang" else "Mulai rekam")
                    }
                }
            }
        }
    }
}

@Composable private fun ReadingResultCard(vm: AppViewModel, child: Child, step: ReadingStep) {
    val approved = step is ReadingStep.Approved
    val recitation = when (step) {
        is ReadingStep.Approved -> step.recitation
        is ReadingStep.Waiting -> step.recitation
        else -> return
    }
    ReadingBanner(
        if (approved) "Izin TV siap" else null,
        if (approved) "Bacaan disetujui" else "Menunggu persetujuan",
        recitation.readingTitle(vm.quran),
        if (approved) Mint else Butter,
        if (approved) Icons.Outlined.Tv else Icons.Outlined.Headphones,
    )
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AiReadingSummary(recitation)
            if (approved) {
                TvPermitControls(vm, child, recitation.id, recitation.expiresAt)
            } else {
                Text("Ayah atau Ibu akan mendengarkan bacaanmu.", color = Muted)
                OutlinedButton(onClick = { vm.checkReadingResult(recitation.id) }, enabled = !vm.busy,
                    modifier = Modifier.fillMaxWidth()) {
                    if (vm.checkingReadingResult) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = Forest, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (vm.checkingReadingResult) "Mengecek hasil…" else "Cek hasil")
                }
            }
        }
    }
}

@Composable private fun TvPermitControls(vm: AppViewModel, child: Child, recitationId: String, expiresAt: Long?) {
    val tvReady = !child.awaitsTv && if (child.tvTransport == "infrared") vm.infrared.available else vm.tvPaired
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        expiresAt?.let { Text("Izin berlaku sampai ${time(it)} untuk satu kali menyalakan TV.", color = Muted) }
        if (tvReady && child.tvTransport == "infrared") Text("Arahkan ujung HP ke TV.", color = Muted)
        Button(onClick = { if (tvReady) vm.power(recitationId) else vm.openTvSetup() },
            enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (tvReady) "Nyalakan TV" else "Pilih atau hubungkan TV")
        }
    }
}
