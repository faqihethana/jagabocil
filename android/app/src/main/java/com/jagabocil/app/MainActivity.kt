package com.jagabocil.app

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

internal val UsernamePattern = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{2,31}")
class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val transparent = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.light(transparent, transparent))
        openNotification(intent)
        setContent { MaterialTheme(colorScheme = AppColors, typography = AppTypography) { JagaBocil(vm) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openNotification(intent)
    }

    private fun openNotification(intent: Intent) {
        val recordingId = intent.getStringExtra(REVIEW_RECORDING_EXTRA) ?: return
        intent.removeExtra(REVIEW_RECORDING_EXTRA)
        if (recordingId.length == 36) vm.openReview(recordingId)
    }
}

@Composable private fun JagaBocil(vm: AppViewModel) {
    val snackbar = remember { SnackbarHostState() }
    val owner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val recordingInProgress = vm.recording || vm.recordedFile != null
    androidx.activity.compose.BackHandler(enabled = vm.screen in listOf("quran", "books") || vm.dashboard != null && (vm.screen != "home" || recordingInProgress)) {
        when {
            vm.screen == "tv-setup" -> if (!vm.busy) vm.leaveTvSetup()
            vm.screen.startsWith("reading-task:") -> if (!vm.busy) vm.screen = vm.screen.split(':')[1]
            vm.screen.startsWith("settings:") -> vm.screen = "family"
            vm.screen.startsWith("review:") -> { vm.stopPlayback(); vm.screen = "home" }
            recordingInProgress -> vm.message = "Selesaikan atau hapus rekaman dulu, ya."
            else -> vm.screen = "home"
        }
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) vm.backgrounded() else if (event == Lifecycle.Event.ON_START) vm.foregrounded() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(vm.recording) {
        val window = (context as? ComponentActivity)?.window
        if (vm.recording) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            if (vm.message == it) vm.message = null
        }
    }
    LaunchedEffect(vm.dashboard?.role) {
        while (vm.dashboard != null) {
            delay(20_000)
            vm.pollUpdates()
        }
    }
    Scaffold(containerColor = Cream, snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (vm.dashboard != null && vm.screen in listOf("home", "history", "family", "quran", "books") && !recordingInProgress) {
                val parent = vm.dashboard?.role == "parent"
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                        val colors = NavigationBarItemDefaults.colors(
                            indicatorColor = Rose, selectedIconColor = Forest, selectedTextColor = Forest,
                            unselectedIconColor = Muted, unselectedTextColor = Muted,
                        )
                        NavigationBarItem(selected = vm.screen == "home", onClick = { vm.screen = "home" }, icon = { Icon(Icons.Outlined.Home, null) }, label = { Text("Beranda") }, colors = colors)
                        NavigationBarItem(selected = vm.screen == "history", onClick = { vm.screen = "history" }, icon = { Icon(Icons.Outlined.History, null) }, label = { Text("Riwayat") }, colors = colors)
                        NavigationBarItem(selected = vm.screen == "quran", onClick = { vm.screen = "quran" }, icon = { Icon(Icons.AutoMirrored.Outlined.MenuBook, null) }, label = { Text("Al-Qur’an", softWrap = false) }, colors = colors)
                        NavigationBarItem(selected = vm.screen == "books", onClick = { vm.screen = "books" }, icon = { Icon(Icons.Outlined.Book, null) }, label = { Text("Buku") }, colors = colors)
                        NavigationBarItem(selected = vm.screen == "family", onClick = { vm.screen = "family" }, icon = { Icon(if (parent) Icons.Outlined.People else Icons.Outlined.Info, null) }, label = { Text(if (parent) "Keluarga" else "Panduan") }, colors = colors)
                    }
                }
            }
        }) { insets ->
        Column(Modifier.padding(insets).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (vm.busy && !vm.checkingReadingResult) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = Forest, trackColor = Mint)
            }
            val data = vm.dashboard
            when {
                vm.screen == "quran" -> QuranScreen(vm.quran, vm.api) { vm.screen = "home" }
                vm.screen == "books" -> BookLibraryScreen(vm) { vm.screen = "home" }
                data == null && !vm.loggedIn -> Welcome(vm)
                data == null -> Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Menyambungkan keluarga…", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = vm::refresh, enabled = !vm.busy) { Text("Coba lagi") }
                    TextButton(onClick = vm::logout, enabled = !vm.busy) { Text("Kembali ke halaman masuk") }
                    TextButton(onClick = { vm.screen = "quran" }) { Text("Baca Al-Qur’an") }
                    TextButton(onClick = { vm.screen = "books" }) { Text("Buka rak buku") }
                }
                vm.screen == "tv-setup" && data.role == "child" -> TvSetupScreen(vm, data)
                vm.screen.startsWith("review:") && data.role == "parent" -> ReviewScreen(vm, data)
                vm.screen.startsWith("reading-task:") && data.role == "parent" -> {
                    val route = vm.screen
                    val child = data.children.find { it.id == route.substringAfterLast(':') }
                    val back = { vm.screen = route.split(':')[1] }
                    if (child != null && data.readingAssignments) {
                        key(child.id) { ReadingTaskScreen(vm, child, back) }
                    } else LaunchedEffect(route) { back() }
                }
                vm.screen.startsWith("settings:") -> {
                    val child = data.children.find { it.id == vm.screen.substringAfter(':') }
                    if (child != null) SettingsScreen(vm, data, child)
                    else LaunchedEffect(Unit) { vm.screen = "family" }
                }
                vm.screen == "home" && data.role == "child" -> ChildHome(vm, data)
                else -> {
                    Header(vm, data)
                    val pageScroll = key(vm.screen) { rememberScrollState() }
                    Column(Modifier.widthIn(max = 680.dp).fillMaxSize().verticalScroll(pageScroll).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        when (vm.screen) {
                            "history" -> History(vm, data)
                            "family" -> if (data.role == "parent") Family(vm, data) else Guide(vm, data)
                            else -> ParentHome(vm, data)
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }
    if (vm.tvPairCodeNeeded) {
        var code by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = vm::cancelTvPair, title = { Text("Kode Android / Google TV") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Masukkan enam karakter pada layar TV, termasuk huruf A–F. Kode berlaku sekitar 90 detik.")
                Field("Kode di layar TV", code, { code = it.uppercase().filter { ch -> ch in "0123456789ABCDEF" }.take(6) })
            }
        }, confirmButton = { TextButton(onClick = { vm.finishTvPair(code) }, enabled = code.length == 6 && !vm.busy) { Text("Hubungkan") } },
            dismissButton = { TextButton(onClick = vm::cancelTvPair) { Text("Batal") } })
    }
    vm.recoveryCode?.let { RecoveryCodeDialog(it, vm::dismissRecoveryCode) }
}

@Composable private fun Brand() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(38.dp).background(Forest, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Outlined.MenuBook, null, tint = Butter, modifier = Modifier.size(22.dp))
        }
        Text("JagaBocil", style = MaterialTheme.typography.titleLarge, color = Ink)
    }
}

@Composable private fun Welcome(vm: AppViewModel) {
    var role by rememberSaveable { mutableStateOf<String?>(null) }
    val child = role == "child"
    var register by rememberSaveable { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var recovering by remember { mutableStateOf(false) }
    val canSubmit = if (register) UsernamePattern.matches(username.trim()) && password.length in 4..128
        else username.isNotBlank() && password.isNotEmpty()
    fun back() {
        password = ""
        if (register) register = false else { role = null; username = "" }
    }
    BackHandler(enabled = role != null && !recovering) { if (!vm.busy) back() }
    val formScroll = key(role) { rememberScrollState() }
    BoxWithConstraints(Modifier.widthIn(max = 480.dp).fillMaxSize()) {
        val extraSpace = (maxHeight - 640.dp).coerceIn(0.dp, 260.dp)
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(formScroll)
            .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (role != null) {
                    IconButton(onClick = ::back, enabled = !vm.busy) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Kembali") }
                }
                Brand()
            }
            if (role == null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.screen = "quran" }, modifier = Modifier.weight(1f)) { Text("Baca Al-Qur’an") }
                    OutlinedButton(onClick = { vm.screen = "books" }, modifier = Modifier.weight(1f)) { Text("Buka rak buku") }
                }
                Surface(color = Peach, modifier = Modifier.fillMaxWidth().padding(top = extraSpace * 0.12f),
                    shape = RoundedCornerShape(30.dp)) {
                    FamilyIllustration(Modifier.fillMaxWidth().height(228.dp + extraSpace * 0.24f).padding(8.dp))
                }
                Column(Modifier.padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Baca dulu,\nbaru nonton.", Modifier.semantics { heading() },
                        style = MaterialTheme.typography.headlineLarge, color = Ink)
                    Text("Ngaji atau baca buku untuk mendapat izin nonton TV.",
                        style = MaterialTheme.typography.bodyLarge, color = Muted)
                }
                Column(Modifier.padding(top = extraSpace * 0.23f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TaskLink("Orang tua", "Atur tugas dan periksa bacaan.", Icons.Outlined.People, Mint) { role = "parent" }
                    TaskLink("Aku anak", "Masuk dan mulai membaca.", Icons.Outlined.Mic, Sky) { role = "child" }
                }
            } else {
                ReadingBanner(
                    null,
                    if (child) "Masuk sebagai anak" else if (register) "Buat akun orang tua" else "Masuk sebagai orang tua",
                    if (child) "Pakai akun anak yang dibuat orang tua." else "Kelola bacaan dan izin TV keluarga.",
                    accent = if (child) Sky else Mint,
                    icon = if (child) Icons.Outlined.Mic else Icons.Outlined.People,
                )
                Surface(color = Color.White, shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field("Username", username, { username = it.take(if (register) 32 else 254) }, KeyboardType.Ascii)
                        if (register) Text("3–32 karakter. Mulai dengan huruf atau angka. Boleh memakai titik, garis bawah, dan tanda hubung.", color = Muted, fontSize = 12.sp)
                        PasswordField(if (register) "Kata sandi (min. 4 karakter)" else "Kata sandi", password) { password = it.take(128) }
                        Button(onClick = { vm.authenticate(username, password, child, register) }, enabled = !vm.busy && canSubmit, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                            Text(if (register) "Buat akun" else "Masuk", fontWeight = FontWeight.SemiBold)
                        }
                        if (child) Text("Lupa kata sandi? Minta orang tua mengubahnya di menu Keluarga.",
                            color = Muted, style = MaterialTheme.typography.bodySmall)
                        if (!child) TextButton(onClick = { register = !register; password = "" }, enabled = !vm.busy, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(if (register) "Sudah punya akun? Masuk" else "Belum punya akun? Daftar") }
                        if (!child && !register) TextButton(onClick = { recovering = true }, enabled = !vm.busy, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Lupa kata sandi?") }
                    }
                }
            }
        }
    }
    if (recovering) RecoverAccountDialog(vm, username, onDismiss = { recovering = false }, onRecovered = { recovered ->
        username = recovered
        password = ""
        recovering = false
    })
}

@Composable private fun Header(vm: AppViewModel, data: Dashboard) {
    var menu by remember { mutableStateOf(false) }
    val today = remember { SimpleDateFormat("EEEE, d MMMM", Locale.forLanguageTag("id-ID")).format(Date()) }
    Row(Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(46.dp).background(Rose, CircleShape), contentAlignment = Alignment.Center) {
            Text(data.name.trim().take(1).uppercase(), color = Forest, style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.weight(1f)) {
            Text(today, color = Muted, style = MaterialTheme.typography.bodySmall)
            Text(data.name, color = Ink, style = MaterialTheme.typography.titleLarge)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Menu akun") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Perbarui") }, enabled = !vm.busy,
                    leadingIcon = { Icon(Icons.Outlined.Refresh, null) }, onClick = { menu = false; vm.refresh() })
                DropdownMenuItem(text = { Text("Keluar dari akun ${data.name}") }, enabled = !vm.busy,
                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Logout, null) }, onClick = { menu = false; vm.logout() })
            }
        }
    }
}

@Composable private fun ParentHome(vm: AppViewModel, data: Dashboard) {
    val pending = data.recitations.filter { it.status == "pending" }
    if (data.children.isEmpty()) {
        ReadingBanner(null, "Buat akun anak", "Setelah akun siap, kamu bisa memilih tugas bacaan untuk anak.",
            accent = Peach, illustrated = true)
        AddChild(vm)
        return
    }
    ReadingBanner("Bacaan keluarga", when {
        data.recitations.isEmpty() -> "Menunggu bacaan pertama"
        pending.isEmpty() -> "Semua bacaan sudah diperiksa"
        else -> "${pending.size} bacaan menunggu"
    },
        if (pending.isEmpty()) "Rekaman baru akan muncul di sini." else "Dengarkan bacaan anak sebelum memberi izin TV.",
        accent = Peach, illustrated = true)
    if (pending.isNotEmpty()) {
        SectionTitle("Antrean pemeriksaan")
        pending.sortedBy { it.createdAt }.forEach { rec ->
            key(rec.id) {
                val child = data.children.find { it.id == rec.childId }
                TaskLink("${child?.name ?: "Anak"} · ${rec.readingTitle(vm.quran)}",
                    "${time(rec.createdAt)} · ${rec.duration.roundToInt()} detik", Icons.Outlined.Headphones, Rose) {
                    vm.openReview(rec.id)
                }
            }
        }
    }
    if (data.readingAssignments) {
        SectionTitle("Tugas bacaan")
        data.children.forEach { child ->
            key(child.id) {
                TaskLink("Atur bacaan ${child.name}", child.readingTaskTitle(vm.quran),
                    Icons.AutoMirrored.Outlined.MenuBook, Sky, enabled = !vm.busy) {
                    vm.screen = "reading-task:home:${child.id}"
                }
            }
        }
    }
    SectionTitle("Akses cepat")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        DashboardLink("Profil anak", "${data.children.size} profil", Icons.Outlined.People, Mint, Modifier.weight(1f)) {
            vm.screen = "family"
        }
        DashboardLink("Al-Qur’an", "Buka daftar surah", Icons.AutoMirrored.Outlined.MenuBook, Sky, Modifier.weight(1f)) {
            vm.screen = "quran"
        }
    }
    if (data.recitations.isEmpty() && !vm.notificationsEnabled) NotificationSetting(vm, parent = true)
}

@Composable private fun ReviewScreen(vm: AppViewModel, data: Dashboard) {
    val rec = data.recitations.firstOrNull { it.id == vm.screen.substringAfter(':') }
    Column(Modifier.widthIn(max = 680.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.stopPlayback(); vm.screen = "home" }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Kembali") }
            SectionTitle("Periksa bacaan")
        }
        if (rec != null) RecordingCard(vm, data, rec, true, showListenButton = true)
        else EmptyCard("Bacaan belum tersedia", "Periksa kembali atau buka daftar bacaan di Beranda.")
    }
}

@Composable private fun History(vm: AppViewModel, data: Dashboard) {
    var filter by rememberSaveable { mutableStateOf("all") }
    ReadingBanner(null, "Riwayat bacaan", "Rekaman keluarga tersimpan selama 7 hari.", Sky, Icons.Outlined.History)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("all" to "Semua", "pending" to "Menunggu", "approved" to "Disetujui", "rejected" to "Perlu diulang").forEach { (id, label) ->
            FilterChip(selected = filter == id, onClick = { filter = id }, label = { Text(label) })
        }
    }
    val visible = data.recitations.filter { filter == "all" || it.status == filter }
    if (visible.isEmpty()) EmptyCard("Belum ada bacaan", if (filter == "all") "Rekaman yang sudah dikirim akan muncul di sini." else "Belum ada rekaman dengan status ini.")
    visible.forEach { rec -> key(rec.id) { RecordingCard(vm, data, rec, data.role == "parent") } }
}

@Composable private fun RecordingCard(vm: AppViewModel, data: Dashboard, r: Recitation, parent: Boolean, showListenButton: Boolean = false) {
    var confirmDelete by remember { mutableStateOf(false) }
    Surface(color = Color.White, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).background(Soft, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Outlined.MenuBook, null) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.readingTitle(vm.quran), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text("${data.children.find { it.id == r.childId }?.name.orEmpty()} · ${r.duration.roundToInt()} dtk · ${time(r.createdAt)}", color = Muted, fontSize = 12.sp)
                }
                if (!showListenButton) IconButton(onClick = { vm.play(r) }, enabled = !vm.busy) { Icon(if (vm.playing == r.id) Icons.Outlined.StopCircle else Icons.Outlined.PlayCircle, if (vm.playing == r.id) "Hentikan" else "Dengarkan rekaman", tint = Forest, modifier = Modifier.size(32.dp)) }
            }
            val label = when { r.usedAt != null -> "Izin sudah dipakai"; r.status == "pending" -> "Menunggu diperiksa"; r.status == "rejected" -> "Coba lagi"; (r.expiresAt ?: 0) <= vm.serverNow() -> "Izin kedaluwarsa"; else -> "Disetujui ${if (r.reviewer == "ai") "AI" else "orang tua"}" }
            val statusColor = when (r.status) {
                "approved" -> Mint
                "rejected" -> Rose
                else -> Butter
            }
            Pill(label, statusColor, if (r.status == "pending") Amber else Ink)
            if (parent || r.status != "pending") Text(r.feedback, fontSize = 13.sp, color = Muted)
            if (parent && (r.hasTranscript || r.readingKind == "book")) ReadingDetailsPanel(vm, r)
            if (showListenButton) OutlinedButton(onClick = { vm.play(r) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                Icon(if (vm.playing == r.id) Icons.Outlined.StopCircle else Icons.Outlined.PlayCircle, null)
                Spacer(Modifier.width(8.dp)); Text(if (vm.playing == r.id) "Hentikan audio" else "Dengarkan bacaan")
            }
            if (parent && r.status == "pending") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { vm.review(r.id, false) }, enabled = !vm.busy, modifier = Modifier.weight(1f)) { Text("Minta ulang") }
                    Button(onClick = { vm.review(r.id, true) }, enabled = !vm.busy, modifier = Modifier.weight(1f)) { Text("Setujui") }
                }
            }
            if (parent) TextButton(onClick = { confirmDelete = true }, enabled = !vm.busy, contentPadding = PaddingValues(0.dp)) { Text("Hapus rekaman", color = Muted, fontSize = 12.sp) }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Hapus rekaman?") }, text = { Text("Audio dan izin dari bacaan ini akan dihapus.") }, confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteRecording(r.id) }) { Text("Hapus") } }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Batal") } })
}

@Composable private fun Family(vm: AppViewModel, data: Dashboard) {
    var adding by rememberSaveable { mutableStateOf(false) }
    ReadingBanner(null, "Akun anak", "Kelola login anak dan aturan pemeriksaan bacaan.", Mint, Icons.Outlined.People)
    data.children.forEach { child -> key(child.id) { ChildCard(vm, child, data.readingAssignments) } }
    if (adding || data.children.isEmpty()) AddChild(vm) { adding = false }
    else OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("Tambah akun anak") }
    SectionTitle("Pengaturan akun")
    NotificationSetting(vm, parent = true)
    RecoverySetting(vm, data.recoveryConfigured)
    if (BuildConfig.DEBUG) data.billing?.let { BillingSetting(vm, it) }
}

@Composable private fun ChildCard(vm: AppViewModel, child: Child, readingAssignments: Boolean) {
    var editingAccount by remember(child.id) { mutableStateOf(false) }
    Surface(color = Color.White, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).background(Rose, CircleShape), contentAlignment = Alignment.Center) { Text(child.name.take(1).uppercase(), fontWeight = FontWeight.Bold, fontSize = 20.sp) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(child.name, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text(child.username?.let { "@$it" } ?: "Login belum disiapkan", color = Muted, fontSize = 12.sp)
                }
                TextButton(onClick = { vm.screen = "settings:${child.id}" }) { Text("Ubah aturan") }
            }
            if (readingAssignments) {
                TaskLink("Tugas bacaan", child.readingTaskTitle(vm.quran),
                    Icons.AutoMirrored.Outlined.MenuBook, Sky, enabled = !vm.busy) {
                    vm.screen = "reading-task:family:${child.id}"
                }
            }
            OutlinedButton(onClick = { editingAccount = true }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (child.username == null) "Siapkan login anak" else "Ubah login anak")
            }
        }
    }
    if (editingAccount) ChildAccountDialog(vm, child) { editingAccount = false }
}

@Composable private fun AddChild(vm: AppViewModel, onCreated: () -> Unit = {}) {
    var name by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Surface(color = Soft, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("Login anak")
            Field("Nama panggilan anak", name, { name = it.take(40) })
            Field("Username anak", username, { username = it.take(32) }, KeyboardType.Ascii)
            Text("Username 3–32 karakter. Gunakan huruf, angka, titik, garis bawah, atau tanda hubung.",
                color = Muted, style = MaterialTheme.typography.bodySmall)
            PasswordField("Kata sandi anak (min. 4 karakter)", password) { password = it.take(128) }
            Button(
                onClick = {
                    vm.addChild(name.trim(), username.trim(), password) {
                        name = ""; username = ""; password = ""; onCreated()
                    }
                },
                enabled = !vm.busy && name.isNotBlank() && UsernamePattern.matches(username.trim()) && password.length in 4..128,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Buat akun anak") }
        }
    }
}

@Composable private fun SettingsScreen(vm: AppViewModel, data: Dashboard, child: Child) {
    var mode by remember(child.id) { mutableStateOf(if (data.readingActivities) child.mode else "manual") }
    var surah by remember(child.id) { mutableStateOf(child.surahId) }
    var seconds by remember(child.id) { mutableFloatStateOf(child.minSeconds.toFloat()) }
    var minutes by remember(child.id) { mutableFloatStateOf(child.unlockMinutes.toFloat()) }
    var brand by remember(child.id) { mutableStateOf(child.tvBrand) }
    var transport by remember(child.id) { mutableStateOf(child.tvTransport) }
    var platform by remember(child.id) { mutableStateOf(child.tvPlatform) }
    var host by remember(child.id) { mutableStateOf(child.tvHost) }
    var mac by remember(child.id) { mutableStateOf(child.tvMac) }
    var consent by remember(child.id) { mutableStateOf(child.aiConsent) }
    var revoke by remember { mutableStateOf(false) }
    var showDiscovery by remember(child.id) { mutableStateOf(false) }
    var showSurahs by remember(child.id) { mutableStateOf(false) }
    var showTvSettings by rememberSaveable(child.id) { mutableStateOf(false) }
    val taskSurahs = if (data.juz30Tasks) vm.quran.juz30
        else vm.quran.juz30.filter { chapter -> data.surahs.any { it.id == chapter.id } }
    Column(Modifier.widthIn(max = 680.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.screen = "family" }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Kembali") }
            SectionTitle("Aturan untuk ${child.name}")
        }
        SectionTitle("Metode pemeriksaan")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Choice("Orang tua", mode == "manual", Modifier.weight(1f)) { mode = "manual" }
            Choice("Bantuan AI", mode == "ai", Modifier.weight(1f), data.aiAvailable && data.readingActivities) { mode = "ai" }
        }
        if (!data.aiAvailable || !data.readingActivities) {
            Text("Bantuan AI versi ini belum tersedia di server.", color = Muted, fontSize = 12.sp)
        }
        if (mode == "ai") {
            Surface(color = Soft, shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Izinkan pengiriman audio dan penyimpanan transkripsi", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                        Switch(checked = consent, onCheckedChange = { consent = it })
                    }
                    Text("AI menyalin rekaman hingga 120 detik. Untuk buku, JagaBocil juga mencocokkan teks. Transkripsi dapat dilihat orang tua selama rekaman tersimpan, hingga 7 hari.", color = Muted, fontSize = 12.sp)
                }
            }
        }
        if (!data.readingAssignments) {
            TaskLink("Surah awal", vm.quran.chapter(surah)?.let { "${it.name} · Anak bisa pilih surah lain" }.orEmpty(),
                Icons.AutoMirrored.Outlined.MenuBook, Sky, enabled = taskSurahs.isNotEmpty()) { showSurahs = true }
        }
        Surface(color = Color.White, shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionTitle("Durasi bacaan dan izin")
                Text("Rekaman minimal ${seconds.roundToInt()} detik", style = MaterialTheme.typography.titleMedium)
                Slider(value = seconds, onValueChange = { seconds = it }, valueRange = 10f..120f, steps = 21)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("Izin berlaku ${minutes.roundToInt()} menit setelah disetujui", style = MaterialTheme.typography.titleMedium)
                Slider(value = minutes, onValueChange = { minutes = it }, valueRange = 1f..60f, steps = 58)
                Text("Izin berlaku untuk satu kali menyalakan TV.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        TextButton(onClick = { showTvSettings = !showTvSettings }) { Text(if (showTvSettings) "Tutup pengaturan TV lanjutan" else "Pengaturan TV lanjutan") }
        if (showTvSettings) Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Sambungkan TV lewat", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("Infrared", transport == "infrared", Modifier.weight(1f)) { transport = "infrared" }
                Choice("Wi-Fi yang sama", transport == "wifi", Modifier.weight(1f)) { transport = "wifi" }
            }
            if (transport == "infrared") {
                Text("Profil infrared TV", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("none" to "Belum", "samsung" to "Samsung", "lg" to "LG").forEach { (id, label) -> Choice(label, brand == id, Modifier.weight(1f)) { brand = id } }
                }
                Text("Perlu HP dengan IR blaster 38 kHz. Profil umum ini belum tentu cocok untuk semua model. Sinyal power dapat menyalakan atau mematikan TV.", color = Muted, fontSize = 12.sp)
            } else {
                Text("Platform TV", fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = { showDiscovery = true }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Search, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Cari TV di Wi-Fi")
                }
                listOf("androidtv" to "Android TV / Google TV", "samsung" to "Samsung Tizen", "lg" to "LG webOS", "wol" to "Wake-on-LAN saja").forEach { (id, label) ->
                    Choice(label, platform == id, Modifier.fillMaxWidth()) { platform = id }
                }
                if (platform != "wol") {
                    Field("IP lokal TV · contoh 192.168.1.20", host, { host = it.trim().take(15) }, KeyboardType.Uri)
                    Text("Cari IP di pengaturan jaringan TV. HP anak harus satu subnet Wi-Fi. Setelah aturan disimpan, hubungkan TV dari halaman anak pada HP yang akan menjadi remote.", color = Muted, fontSize = 12.sp)
                }
                Field(if (platform == "wol") "MAC Wi-Fi TV · wajib" else "MAC Wi-Fi TV · opsional untuk standby", mac, { mac = it.trim().uppercase().take(17) })
                Text("Bangun dari standby perlu Wake-on-LAN / wake melalui Wi-Fi yang didukung dan diaktifkan di TV. Jaringan tamu atau isolasi perangkat dapat memblokir koneksi. Tidak semua model mendukung fitur yang sama.", color = Muted, fontSize = 12.sp)
            }
        }
        val validConnection = transport != "wifi" || (if (platform == "wol") runCatching { WakePacket.macBytes(mac) }.isSuccess else runCatching { LanAddress.parse(host) }.isSuccess && (mac.isBlank() || runCatching { WakePacket.macBytes(mac) }.isSuccess))
        Button(
            onClick = {
                vm.policy(child, Policy(mode, surah, seconds.roundToInt(), minutes.roundToInt(), brand, consent,
                    transport, mac, platform, host, transcriptConsent = mode == "ai" && consent))
            },
            enabled = !vm.busy && validConnection && (mode != "ai" || consent && data.aiAvailable),
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
        ) { Text("Simpan aturan") }
        Text("Menyimpan aturan membatalkan izin lama agar tugas baru tidak terlewati.", color = Muted, fontSize = 12.sp)
        OutlinedButton(onClick = { revoke = true }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Putuskan semua HP anak") }
    }
    if (showSurahs) QuranTaskPicker(taskSurahs, surah, { showSurahs = false }) {
        surah = it
        showSurahs = false
    }
    if (showDiscovery) TvDiscoveryDialog(
        vm,
        onDismiss = { showDiscovery = false },
        onSelect = { tv ->
            if (host != tv.host || platform != tv.platform.id) mac = ""
            host = tv.host
            platform = tv.platform.id
            showDiscovery = false
        },
    )
    if (revoke) AlertDialog(onDismissRequest = { revoke = false }, title = { Text("Putuskan perangkat anak?") }, text = { Text("Semua HP ${child.name} perlu masuk lagi. Izin TV yang belum dipakai ikut dicabut.") }, confirmButton = { TextButton(onClick = { revoke = false; vm.revoke(child.id) }) { Text("Putuskan") } }, dismissButton = { TextButton(onClick = { revoke = false }) { Text("Batal") } })
}

@Composable private fun TvDiscoveryDialog(vm: AppViewModel, onDismiss: () -> Unit, onSelect: (DiscoveredTv) -> Unit) {
    var result by remember { mutableStateOf<Result<List<DiscoveredTv>>?>(null) }
    var search by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) onDismiss()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(search) {
        result = null
        result = try {
            Result.success(vm.discoverTvs())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cari TV di Wi-Fi") },
        text = {
            val outcome = result
            val tvs = outcome?.getOrNull().orEmpty()
            when {
                outcome == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    Text("Mencari TV yang menyala di jaringan ini…")
                }
                tvs.isEmpty() -> Text("TV belum ditemukan. Pastikan TV menyala dan HP serta TV memakai Wi-Fi yang sama.")
                else -> Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    tvs.forEach { tv ->
                        OutlinedButton(onClick = { onSelect(tv) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text(tv.name, fontWeight = FontWeight.SemiBold)
                                if (tvs.count { it.name == tv.name } > 1) Text(tv.host, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (result != null && result?.getOrNull().isNullOrEmpty()) TextButton(onClick = { search++ }) { Text("Cari lagi") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (result == null) "Batal" else "Tutup") } },
    )
}

@Composable private fun TvSetupScreen(vm: AppViewModel, data: Dashboard) {
    val child = data.children.firstOrNull() ?: return
    var searching by remember { mutableStateOf(false) }
    var manual by rememberSaveable { mutableStateOf(false) }
    val needsLink = child.tvTransport == "wifi" && !vm.tvPaired
    Column(Modifier.widthIn(max = 680.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::leaveTvSetup, enabled = !vm.busy) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Kembali") }
            SectionTitle("TV saya")
        }
        val detail = when {
            child.awaitsTv -> "Nyalakan TV, lalu sambungkan HP dan TV ke Wi-Fi yang sama."
            needsLink -> "Nyalakan TV dan ikuti petunjuk di layarnya untuk menghubungkan HP ini."
            child.tvTransport == "wifi" -> "Pilihan Wi-Fi tersimpan di akunmu."
            else -> "Profil infrared ${child.tvBrand.uppercase()} tersimpan di akunmu."
        }
        ReadingBanner(null, if (child.awaitsTv) "Pilih TV" else "Sambungan TV", detail, Sky, Icons.Outlined.Tv)
        if (needsLink) {
            Button(onClick = { vm.pairTv(child) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Hubungkan TV") }
        } else if (!child.awaitsTv) {
            OutlinedButton(onClick = { vm.checkTv(child) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Cek sambungan") }
            if (child.tvTransport == "wifi" && child.tvPlatform != "wol") {
                TextButton(onClick = { vm.pairTv(child) }, enabled = !vm.busy) { Text("Hubungkan ulang TV") }
            }
        }
        TaskLink("Cari TV di Wi-Fi", "Pilih dari TV yang ditemukan.", Icons.Outlined.Search, Mint, enabled = !vm.busy) { searching = true }
        TextButton(onClick = { manual = !manual }, enabled = !vm.busy) { Text(if (manual) "Tutup opsi TV" else "Opsi TV lainnya") }
        if (manual) ManualChildTv(vm, child)
    }
    if (searching) TvDiscoveryDialog(vm, onDismiss = { searching = false }) { tv ->
        searching = false
        val mac = child.tvMac.takeIf { child.tvPlatform == tv.platform.id && child.tvHost == tv.host }.orEmpty()
        vm.saveTv(TvSettings("none", "wifi", tv.platform.id, tv.host, mac), connect = true)
    }
}

@Composable private fun ManualChildTv(vm: AppViewModel, child: Child) {
    var transport by rememberSaveable(child.tvRevision) {
        mutableStateOf(if (child.awaitsTv || !vm.infrared.available) "wifi" else child.tvTransport)
    }
    var brand by rememberSaveable(child.tvRevision) { mutableStateOf(if (child.tvBrand == "lg") "lg" else "samsung") }
    var platform by rememberSaveable(child.tvRevision) {
        mutableStateOf(if (child.tvTransport == "infrared") "androidtv" else child.tvPlatform)
    }
    var host by rememberSaveable(child.tvRevision) { mutableStateOf(child.tvHost) }
    var mac by rememberSaveable(child.tvRevision) { mutableStateOf(child.tvMac) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (vm.infrared.available) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice("Wi-Fi manual", transport == "wifi", Modifier.weight(1f)) { transport = "wifi" }
            Choice("Infrared", transport == "infrared", Modifier.weight(1f)) { transport = "infrared" }
        }
        if (transport == "infrared") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("Samsung", brand == "samsung", Modifier.weight(1f)) { brand = "samsung" }
                Choice("LG", brand == "lg", Modifier.weight(1f)) { brand = "lg" }
            }
        } else {
            listOf("androidtv" to "Android TV / Google TV", "samsung" to "Samsung Tizen", "lg" to "LG webOS", "wol" to "Wake-on-LAN").forEach { (id, label) ->
                Choice(label, platform == id, Modifier.fillMaxWidth()) { platform = id }
            }
            if (platform != "wol") Field("IP lokal TV", host, { host = it.trim().take(15) }, KeyboardType.Uri)
            Field(if (platform == "wol") "MAC Wi-Fi TV" else "MAC Wi-Fi TV (opsional)", mac, { mac = it.trim().uppercase().take(17) })
        }
        val valid = if (transport == "infrared") vm.infrared.available else {
            val validMac = runCatching { WakePacket.macBytes(mac) }.isSuccess
            if (platform == "wol") validMac else runCatching { LanAddress.parse(host) }.isSuccess && (mac.isBlank() || validMac)
        }
        Button(
            onClick = {
                val settings = if (transport == "infrared") TvSettings(brand, "infrared", "wol", "", "")
                    else TvSettings("none", "wifi", platform, if (platform == "wol") "" else host, mac)
                vm.saveTv(settings, connect = transport == "wifi" && platform != "wol")
            },
            enabled = valid && !vm.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Simpan pilihan TV") }
    }
}

@Composable private fun Guide(vm: AppViewModel, data: Dashboard) {
    val child = data.children.firstOrNull() ?: return
    ReadingBanner(null, "Panduan HP dan TV", "Kelola sambungan dan pemberitahuan.", Butter, Icons.Outlined.Tv)
    TaskLink("TV saya", if (child.awaitsTv) "Pilih TV untuk akunmu." else "Cek sambungan atau pilih TV lain.",
        Icons.Outlined.Tv, Sky, enabled = !vm.busy, onClick = vm::openTvSetup)
    NotificationSetting(vm, parent = false)
    Text("Setiap bacaan yang disetujui memberi satu kali izin menyalakan TV.", color = Muted)
}

@Composable private fun Field(label: String, value: String, changed: (String) -> Unit, type: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(value = value, onValueChange = changed, label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = type), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
}
@Composable private fun Choice(text: String, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, click: () -> Unit) {
    OutlinedButton(onClick = click, modifier = modifier, enabled = enabled, shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp), colors = ButtonDefaults.outlinedButtonColors(containerColor = if (selected) Forest else Color.Transparent, contentColor = if (selected) Color.White else Forest)) { Text(text, fontSize = 13.sp, textAlign = TextAlign.Center) }
}
@Composable private fun Pill(text: String, background: Color, color: Color) {
    Text(text, modifier = Modifier.background(background, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp), color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp)
}
@Composable private fun EmptyCard(title: String, body: String) {
    Surface(color = Soft, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(body, color = Muted, fontSize = 14.sp, lineHeight = 22.sp)
        }
    }
}
internal fun time(millis: Long) = SimpleDateFormat("dd MMM, HH:mm", Locale.forLanguageTag("id-ID")).format(Date(millis))
