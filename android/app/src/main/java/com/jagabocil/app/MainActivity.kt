package com.jagabocil.app

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val UsernamePattern = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{2,31}")
class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openNotification(intent)
        setContent { MaterialTheme(colorScheme = AppColors) { JagaBocil(vm) } }
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
    androidx.activity.compose.BackHandler(enabled = vm.screen == "quran" || vm.dashboard != null && (vm.screen != "home" || recordingInProgress)) {
        when {
            vm.screen == "tv-setup" -> if (!vm.busy) vm.leaveTvSetup()
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
        while (vm.dashboard != null) { delay(10_000); if (!vm.recording && vm.recordedFile == null) vm.refresh() }
    }
    Scaffold(containerColor = Cream, snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (vm.dashboard != null && vm.screen in listOf("home", "history", "family", "quran") && !recordingInProgress) {
                val parent = vm.dashboard?.role == "parent"
                NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                    NavigationBarItem(selected = vm.screen == "home", onClick = { vm.screen = "home" }, icon = { Icon(Icons.Outlined.Home, null) }, label = { Text("Beranda") })
                    NavigationBarItem(selected = vm.screen == "history", onClick = { vm.screen = "history" }, icon = { Icon(Icons.Outlined.History, null) }, label = { Text("Riwayat") })
                    NavigationBarItem(selected = vm.screen == "quran", onClick = { vm.screen = "quran" }, icon = { Icon(Icons.AutoMirrored.Outlined.MenuBook, null) }, label = { Text("Al-Qur’an", maxLines = 1) })
                    NavigationBarItem(selected = vm.screen == "family", onClick = { vm.screen = "family" }, icon = { Icon(if (parent) Icons.Outlined.People else Icons.Outlined.Info, null) }, label = { Text(if (parent) "Keluarga" else "Panduan") })
                }
            }
        }) { insets ->
        Column(Modifier.padding(insets).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Forest, trackColor = Lime)
            val data = vm.dashboard
            when {
                vm.screen == "quran" -> QuranScreen(vm.quran, vm.api) { vm.screen = "home" }
                data == null && !vm.loggedIn -> Welcome(vm)
                data == null -> Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Menyambungkan keluarga…", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = vm::refresh, enabled = !vm.busy) { Text("Coba lagi") }
                    TextButton(onClick = vm::logout, enabled = !vm.busy) { Text("Kembali ke halaman masuk") }
                    TextButton(onClick = { vm.screen = "quran" }) { Text("Baca Al-Qur’an") }
                }
                vm.screen == "tv-setup" && data.role == "child" -> TvSetupScreen(vm, data)
                vm.screen.startsWith("review:") && data.role == "parent" -> ReviewScreen(vm, data)
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
    vm.pairing?.let { code ->
        val child = vm.pairingChild
        var now by remember(code) { mutableLongStateOf(vm.serverNow()) }
        LaunchedEffect(code) { while (now < code.expiresAt) { delay(1000); now = vm.serverNow() } }
        val expired = now >= code.expiresAt
        AlertDialog(onDismissRequest = vm::dismissPairing,
            title = { Text("Hubungkan HP anak") }, text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (expired) Text("Kode sudah kedaluwarsa. Buat kode baru untuk menyambungkan HP anak.")
                    else {
                        Text("Di HP anak, pilih Aku anak → Pindai QR.")
                        PairingQr(code.code, Modifier.size(160.dp))
                        Text("Pilihan lain, masukkan kode ini di HP anak.", textAlign = TextAlign.Center)
                        Text(code.code.chunked(3).joinToString(" "), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Forest)
                        Text("Kode berlaku 10 menit dan hanya sekali pakai.", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                        if (child?.awaitsTv == true) Text("Setelah HP tersambung, dampingi anak saat menyiapkan TV.", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    }
                }
            }, confirmButton = {
                if (expired && child != null) TextButton(onClick = { vm.pairChild(child) }, enabled = !vm.busy) { Text("Buat kode baru") }
                else TextButton(onClick = vm::dismissPairing) { Text("Selesai") }
            }, dismissButton = {
                if (expired && child != null) TextButton(onClick = vm::dismissPairing) { Text("Tutup") }
            })
    }
}

@Composable private fun Brand() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(38.dp).background(Forest, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Outlined.MenuBook, null, tint = Lime, modifier = Modifier.size(23.dp))
        }
        Text("jagabocil", fontWeight = FontWeight.Bold, fontSize = 23.sp, letterSpacing = (-0.8).sp)
    }
}

@Composable private fun Welcome(vm: AppViewModel) {
    var role by rememberSaveable { mutableStateOf<String?>(null) }
    val child = role == "child"
    var register by rememberSaveable { mutableStateOf(false) }
    var server by rememberSaveable { mutableStateOf(vm.api.baseUrl) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var enterCode by rememberSaveable { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var recovering by remember { mutableStateOf(false) }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val scanned = result.contents ?: return@rememberLauncherForActivityResult
        val pairing = readPairingQr(scanned)
        if (pairing == null) vm.message = "QR ini tidak cocok. Pindai QR dari akun orang tua di JagaBocil."
        else vm.authenticate(server, "", "", pairing, child = true, register = false)
    }
    val canSubmit = when {
        child -> code.length == 6
        register -> UsernamePattern.matches(username.trim()) && password.length in 4..128
        else -> username.isNotBlank() && password.isNotEmpty()
    }
    fun back() {
        when {
            enterCode -> { enterCode = false; code = "" }
            register -> { register = false; password = "" }
            else -> { role = null; password = ""; code = "" }
        }
    }
    BackHandler(enabled = role != null && !recovering) { if (!vm.busy) back() }
    val formScroll = key(role) { rememberScrollState() }
    Column(Modifier.widthIn(max = 600.dp).fillMaxSize().imePadding().verticalScroll(formScroll).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (role != null) {
                IconButton(onClick = ::back, enabled = !vm.busy) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Kembali") }
            }
            Brand()
            if (role == null) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { vm.screen = "quran" }) { Text("Baca Al-Qur’an") }
            }
        }
        if (role == null) {
            ReadingBanner(null, "Ngaji sebelum nonton TV", "Rekam bacaan untuk mendapat izin menyalakan TV keluarga.")
            SectionTitle("Siapa yang memakai HP ini?")
            TaskLink("Orang tua", "Atur tugas dan periksa bacaan anak.", Icons.Outlined.People, Lime) { role = "parent" }
            TaskLink("Aku anak", "Hubungkan HP untuk merekam bacaan.", Icons.Outlined.Mic, Peach) { role = "child" }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle(if (child) "Hubungkan HP anak" else if (register) "Buat akun orang tua" else "Masuk sebagai orang tua")
                if (child) {
                    if (!enterCode) {
                        Text("Minta orang tua membuka QR JagaBocil di HP mereka.", color = Muted, fontSize = 14.sp)
                        Button(onClick = {
                            scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                .setPrompt("Arahkan kamera ke QR JagaBocil dari HP orang tua").setBeepEnabled(false).setOrientationLocked(false))
                        }, enabled = !vm.busy && server.isNotBlank(), modifier = Modifier.fillMaxWidth().height(54.dp)) {
                            Icon(Icons.Outlined.QrCodeScanner, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Pindai QR")
                        }
                    } else {
                        Field("6 angka dari orang tua", code, { code = it.filter { digit -> digit in '0'..'9' }.take(6) }, KeyboardType.Number)
                    }
                } else {
                    Field("Username", username, { username = it }, KeyboardType.Ascii)
                    if (register) Text("3–32 karakter. Mulai dengan huruf atau angka. Boleh memakai titik, garis bawah, dan tanda hubung.", color = Muted, fontSize = 12.sp)
                    OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text(if (register) "Kata sandi (min. 4 karakter)" else "Kata sandi") }, visualTransformation = PasswordVisualTransformation(), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
                }
                if (!child || enterCode) {
                    Button(onClick = { vm.authenticate(server, username, password, code, child, register) }, enabled = !vm.busy && server.isNotBlank() && canSubmit, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) {
                        Text(if (child) "Sambungkan HP" else if (register) "Buat akun" else "Masuk", fontWeight = FontWeight.SemiBold)
                    }
                }
                if (child) TextButton(onClick = { enterCode = !enterCode }, enabled = !vm.busy, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(if (enterCode) "Kembali ke pindai QR" else "Masukkan kode angka") }
                if (!child) TextButton(onClick = { register = !register; password = "" }, enabled = !vm.busy, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(if (register) "Sudah punya akun? Masuk" else "Belum punya akun? Daftar") }
                if (!child && !register) TextButton(onClick = { recovering = true }, enabled = !vm.busy, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Lupa kata sandi?") }
            }
        }
        TextButton(onClick = { showAdvanced = !showAdvanced }) { Text(if (showAdvanced) "Tutup pengaturan lanjutan" else "Pengaturan lanjutan") }
        if (showAdvanced) {
            Field("Alamat server JagaBocil", server, { server = it }, KeyboardType.Uri)
            Text("Gunakan alamat yang sama di kedua HP.", color = Muted, fontSize = 12.sp)
        }
    }
    if (recovering) RecoverAccountDialog(vm, server, username, onDismiss = { recovering = false }, onRecovered = { recovered ->
        username = recovered
        password = ""
        recovering = false
    })
}

@Composable private fun Header(vm: AppViewModel, data: Dashboard) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Brand()
        Spacer(Modifier.weight(1f))
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
        ReadingBanner("Keluarga ${data.name}", "Mulai dari profil anak", "Setelah profil dibuat, pindai QR dari HP anak untuk menghubungkannya.")
        AddChild(vm)
        return
    }
    ReadingBanner("Keluarga ${data.name}", when {
        data.recitations.isEmpty() -> "Menunggu bacaan pertama"
        pending.isEmpty() -> "Semua bacaan sudah diperiksa"
        else -> "${pending.size} bacaan menunggu"
    },
        if (pending.isEmpty()) "Rekaman baru akan muncul di beranda ini." else "Pilih rekaman untuk mendengarkan dan memberi keputusan.")
    if (pending.isNotEmpty()) {
        SectionTitle("Antrean pemeriksaan")
        pending.sortedBy { it.createdAt }.forEach { rec ->
            key(rec.id) {
                val child = data.children.find { it.id == rec.childId }
                TaskLink("${child?.name ?: "Anak"} · ${vm.quran.name(rec.surahId)}",
                    "${time(rec.createdAt)} · ${rec.duration.roundToInt()} detik", Icons.Outlined.Headphones, Peach) {
                    vm.openReview(rec.id)
                }
            }
        }
    }
    SectionTitle("Keluarga")
    TaskLink("Kelola profil anak", "${data.children.size} profil · Aturan bacaan dan sambungan HP", Icons.Outlined.People, Lime) {
        vm.screen = "family"
    }
    if (data.recitations.isEmpty() && !vm.notificationsEnabled) NotificationSetting(vm, parent = true)
}

@Composable private fun ReviewScreen(vm: AppViewModel, data: Dashboard) {
    val rec = data.recitations.firstOrNull { it.id == vm.screen.substringAfter(':') }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
    ReadingBanner("Riwayat", "Jejak bacaan", "Rekaman keluarga tersimpan selama 7 hari.")
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
                    Text(vm.quran.name(r.surahId), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text("${data.children.find { it.id == r.childId }?.name.orEmpty()} · ${r.duration.roundToInt()} dtk · ${time(r.createdAt)}", color = Muted, fontSize = 12.sp)
                }
                if (!showListenButton) IconButton(onClick = { vm.play(r) }, enabled = !vm.busy) { Icon(if (vm.playing == r.id) Icons.Outlined.StopCircle else Icons.Outlined.PlayCircle, if (vm.playing == r.id) "Hentikan" else "Dengarkan rekaman", tint = Forest, modifier = Modifier.size(32.dp)) }
            }
            val label = when { r.usedAt != null -> "Izin sudah dipakai"; r.status == "pending" -> "Menunggu diperiksa"; r.status == "rejected" -> "Coba lagi"; (r.expiresAt ?: 0) <= vm.serverNow() -> "Izin kedaluwarsa"; else -> "Disetujui ${if (r.reviewer == "ai") "AI" else "orang tua"}" }
            Pill(label, if (r.status == "approved") Soft else Color(0xFFFFF0D8), if (r.status == "approved") Forest else Amber)
            if (r.status != "pending" || r.reviewer == "ai") Text(r.feedback, fontSize = 13.sp, color = Muted)
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
    ReadingBanner("Keluarga ${data.name}", "Profil anak", "Atur tugas bacaan dan hubungkan perangkat untuk setiap anak.")
    data.children.forEach { child -> key(child.id) { ChildCard(vm, child) } }
    if (adding || data.children.isEmpty()) AddChild(vm) { adding = false }
    else OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("Tambah profil anak") }
    SectionTitle("Pengaturan akun")
    NotificationSetting(vm, parent = true)
    RecoverySetting(vm, data.recoveryConfigured)
    if (BuildConfig.DEBUG) data.billing?.let { BillingSetting(vm, it) }
    Text("Kode penghubung hanya untuk HP anak. Jangan bagikan kata sandi akun orang tua.", fontSize = 12.sp, color = Muted)
}

@Composable private fun ChildCard(vm: AppViewModel, child: Child) {
    var showDiscovery by remember(child.id) { mutableStateOf(false) }
    Surface(color = Color.White, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).background(Lime, CircleShape), contentAlignment = Alignment.Center) { Text(child.name.take(1).uppercase(), fontWeight = FontWeight.Bold, fontSize = 20.sp) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(child.name, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text("${vm.quran.name(child.surahId)} · ${if (child.mode == "ai") "Verifikasi AI" else "Diperiksa orang tua"}", color = Muted, fontSize = 12.sp)
                }
                TextButton(onClick = { vm.screen = "settings:${child.id}" }) { Text("Ubah aturan") }
            }
            OutlinedButton(onClick = { vm.pairChild(child) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Link, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Hubungkan HP anak") }
            if (child.awaitsTv) TextButton(onClick = { showDiscovery = true }, enabled = !vm.busy) { Text("Pilih TV dari HP ini") }
        }
    }
    if (showDiscovery) TvDiscoveryDialog(vm, onDismiss = { showDiscovery = false }) { tv ->
        showDiscovery = false
        vm.chooseFamilyTv(child, tv)
    }
}

@Composable private fun AddChild(vm: AppViewModel, onCreated: () -> Unit = {}) {
    var name by rememberSaveable { mutableStateOf("") }
    Surface(color = Soft, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Buat profil anak", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Field("Nama panggilan anak", name, { name = it.take(40) })
            Text("Bacaan pertama Al-Ikhlas, diperiksa orang tua. Aturan bisa diubah kapan saja.", color = Muted, fontSize = 13.sp)
            Button(onClick = { vm.addChild(name.trim()) { name = ""; onCreated() } }, enabled = !vm.busy && name.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Buat profil & sambungkan HP") }
        }
    }
}

@Composable private fun SettingsScreen(vm: AppViewModel, data: Dashboard, child: Child) {
    var mode by remember(child.id) { mutableStateOf(child.mode) }
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.screen = "family" }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Kembali") }
            SectionTitle("Aturan untuk ${child.name}")
        }
        Text("Metode pemeriksaan", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Choice("Orang tua", mode == "manual", Modifier.weight(1f)) { mode = "manual" }
            Choice("AI", mode == "ai", Modifier.weight(1f), data.aiAvailable) { mode = "ai" }
        }
        if (!data.aiAvailable) Text("AI belum diaktifkan di server. Pemeriksaan orang tua tetap tersedia.", color = Muted, fontSize = 12.sp)
        if (mode == "ai") {
            Surface(color = Soft, shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Izinkan audio dikirim ke penyedia AI", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                        Switch(checked = consent, onCheckedChange = { consent = it })
                    }
                    Text("AI membantu mengenali bacaan, bukan sertifikasi tajwid. Rekaman yang meragukan diteruskan ke orang tua. AI tidak bisa menjamin suara berasal dari bacaan langsung anak.", color = Muted, fontSize = 12.sp)
                }
            }
        }
        Text("Surah yang dibaca", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        vm.quran.chapter(surah)?.let { selected -> Text("${selected.name} · ${selected.verses} ayat") }
        OutlinedButton(onClick = { showSurahs = true }, enabled = taskSurahs.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text("Pilih surah")
        }
        Column {
            Text("Rekaman minimal ${seconds.roundToInt()} detik", fontWeight = FontWeight.Bold)
            Slider(value = seconds, onValueChange = { seconds = it }, valueRange = 10f..120f, steps = 21)
            Text("Durasi saja tidak membuka izin; bacaan tetap perlu diperiksa.", color = Muted, fontSize = 12.sp)
        }
        Column {
            Text("Izin berlaku ${minutes.roundToInt()} menit setelah disetujui", fontWeight = FontWeight.Bold)
            Slider(value = minutes, onValueChange = { minutes = it }, valueRange = 1f..60f, steps = 58)
            Text("Izin untuk satu kali menekan power. Ini bukan timer otomatis mematikan TV.", color = Muted, fontSize = 12.sp)
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
        Button(onClick = { vm.policy(child, Policy(mode, surah, seconds.roundToInt(), minutes.roundToInt(), brand, consent, transport, mac, platform, host)) }, enabled = !vm.busy && validConnection && (mode != "ai" || consent && data.aiAvailable), modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Simpan aturan") }
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
    if (revoke) AlertDialog(onDismissRequest = { revoke = false }, title = { Text("Putuskan perangkat anak?") }, text = { Text("Semua HP ${child.name} perlu dihubungkan ulang. Izin power yang belum dipakai ikut dicabut.") }, confirmButton = { TextButton(onClick = { revoke = false; vm.revoke(child.id) }) { Text("Putuskan") } }, dismissButton = { TextButton(onClick = { revoke = false }) { Text("Batal") } })
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
    val target = vm.setupTarget ?: child.takeUnless { it.awaitsTv }
    var now by remember { mutableLongStateOf(vm.serverNow()) }
    LaunchedEffect(Unit) { while (true) { now = vm.serverNow(); delay(1000) } }
    val canChoose = child.awaitsTv && (data.tvSetupUntil ?: 0) > now
    var searching by remember { mutableStateOf(false) }
    val needsLink = target?.tvTransport == "wifi" && !vm.tvRemote.paired(target)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ReadingBanner("Siapkan TV", when {
            vm.tvTestSent -> "Periksa hasil tes"
            target == null -> "Pilih TV keluarga"
            needsLink -> "Hubungkan HP ke TV"
            else -> "Periksa sambungan"
        }, "HP ${child.name}")
        when {
            vm.tvTestSent -> {
                Text("Apakah TV menyala?")
                Button(onClick = vm::leaveTvSetup, modifier = Modifier.fillMaxWidth()) { Text("TV menyala") }
                TextButton(onClick = {
                    vm.leaveTvSetup()
                    vm.screen = "family"
                    vm.message = "Cek TV dari Panduan. Jika perlu, minta orang tua menyesuaikan pengaturan TV."
                }) { Text("TV belum menyala") }
            }
            child.awaitsTv && !canChoose -> Text("Minta orang tua memilih TV di menu Keluarga pada HP mereka.")
            target == null -> {
                Text("Nyalakan TV dengan remote, lalu pastikan TV dan HP ini memakai Wi-Fi yang sama.")
                Button(onClick = { searching = true }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Cari TV") }
            }
            needsLink -> {
                Text("Nyalakan TV. Masukkan kode yang muncul atau pilih Izinkan pada layar TV jika diminta.")
                Button(onClick = { vm.pairTv(target) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Hubungkan TV") }
            }
            canChoose -> {
                Text("TV terhubung. Matikan layarnya dengan remote, lalu coba nyalakan dari sini.")
                Button(onClick = vm::testSetupTv, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Tes nyalakan TV") }
            }
            else -> {
                Text("Nyalakan TV untuk mengecek sambungan dari HP ini.")
                Button(onClick = { vm.checkTv(target) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Cek sambungan TV") }
            }
        }
        if (!vm.tvTestSent) {
            if (canChoose && target != null) TextButton(onClick = { searching = true }, enabled = !vm.busy) { Text("Pilih TV lain") }
            TextButton(onClick = vm::leaveTvSetup, enabled = !vm.busy) { Text(if (child.awaitsTv) "Nanti saja" else "Kembali ke bacaan") }
        }
    }
    if (searching && canChoose) TvDiscoveryDialog(vm, onDismiss = { searching = false }) { tv ->
        searching = false
        vm.chooseSetupTv(child, tv)
    }
}

@Composable private fun Guide(vm: AppViewModel, data: Dashboard) {
    ReadingBanner("Panduan", "HP dan TV", "Kelola pemberitahuan dan periksa sambungan ke TV keluarga.")
    NotificationSetting(vm, parent = false)
    val child = data.children.firstOrNull() ?: return
    if (child.awaitsTv && (data.tvSetupUntil ?: 0) <= vm.serverNow()) {
        EmptyCard("TV belum disiapkan", "Minta orang tua memilih TV di menu Keluarga pada HP mereka.")
    } else if (child.tvTransport == "infrared" && !child.awaitsTv && !vm.infrared.available) {
        EmptyCard("HP ini perlu sambungan Wi-Fi", "Minta orang tua memilih Wi-Fi di pengaturan TV.")
    } else {
        OutlinedButton(onClick = { vm.screen = "tv-setup" }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (child.awaitsTv) "Siapkan TV" else "Cek sambungan TV")
        }
        if (child.tvTransport == "wifi" && child.tvPlatform != "wol" && vm.tvRemote.paired(child)) {
            TextButton(onClick = { vm.pairTv(child) }, enabled = !vm.busy) { Text("Hubungkan ulang TV") }
        }
    }
    Text("Setiap bacaan yang disetujui memberi satu kali izin menyalakan TV.", color = Muted)
}

@Composable private fun Field(label: String, value: String, changed: (String) -> Unit, type: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(value = value, onValueChange = changed, label = { Text(label, fontSize = 13.sp) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = type), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
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
