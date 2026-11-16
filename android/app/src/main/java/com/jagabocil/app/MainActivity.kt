package com.jagabocil.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val Forest = Color(0xFF173F35)
private val Cream = Color(0xFFF7F8F2)
private val Lime = Color(0xFFD9F194)
private val Muted = Color(0xFF63776E)
private val Soft = Color(0xFFEAF0E8)
private val Amber = Color(0xFF946025)
private val AppColors = lightColorScheme(primary = Forest, onPrimary = Color.White, background = Cream,
    surface = Cream, onSurface = Forest, secondaryContainer = Lime, onSecondaryContainer = Forest, outline = Color(0xFFCED8CF))

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MaterialTheme(colorScheme = AppColors) { JagaBocil() } }
    }
}

@Composable private fun JagaBocil(vm: AppViewModel = viewModel()) {
    val snackbar = remember { SnackbarHostState() }
    val owner = LocalLifecycleOwner.current
    val context = LocalContext.current
    androidx.activity.compose.BackHandler(enabled = vm.dashboard != null && vm.screen != "home" && vm.screen != "record") {
        vm.screen = if (vm.screen.startsWith("settings:")) "family" else "home"
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
    LaunchedEffect(vm.message) { vm.message?.let { val text = it; vm.message = null; snackbar.showSnackbar(text) } }
    LaunchedEffect(vm.dashboard?.role) {
        while (vm.dashboard != null) { delay(10_000); if (!vm.recording && vm.screen != "record") vm.refresh() }
    }
    Scaffold(containerColor = Cream, snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (vm.dashboard != null && !vm.screen.startsWith("settings") && vm.screen != "record") {
                val parent = vm.dashboard?.role == "parent"
                NavigationBar(containerColor = Cream, tonalElevation = 0.dp) {
                    NavigationBarItem(selected = vm.screen == "home", onClick = { vm.screen = "home" }, icon = { Icon(Icons.Outlined.Home, null) }, label = { Text("Beranda") })
                    NavigationBarItem(selected = vm.screen == "history", onClick = { vm.screen = "history" }, icon = { Icon(Icons.Outlined.History, null) }, label = { Text("Riwayat") })
                    NavigationBarItem(selected = vm.screen == "family", onClick = { vm.screen = "family" }, icon = { Icon(if (parent) Icons.Outlined.People else Icons.Outlined.Info, null) }, label = { Text(if (parent) "Keluarga" else "Panduan") })
                }
            }
        }) { insets ->
        Column(Modifier.padding(insets).fillMaxSize()) {
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Forest, trackColor = Lime)
            val data = vm.dashboard
            when {
                data == null && !vm.loggedIn -> Welcome(vm)
                data == null -> Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Menyambungkan keluarga…", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = vm::refresh, enabled = !vm.busy) { Text("Coba lagi") }
                    TextButton(onClick = vm::logout, enabled = !vm.busy) { Text("Kembali ke halaman masuk") }
                }
                vm.screen == "record" -> RecorderScreen(vm, data)
                vm.screen.startsWith("settings:") -> data.children.find { it.id == vm.screen.substringAfter(':') }?.let { SettingsScreen(vm, data, it) }
                else -> {
                    Header(vm, data)
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        when (vm.screen) {
                            "history" -> History(vm, data)
                            "family" -> if (data.role == "parent") Family(vm, data) else Guide(vm)
                            else -> if (data.role == "parent") ParentHome(vm, data) else ChildHome(vm, data)
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
    vm.pairing?.let { code ->
        var now by remember { mutableLongStateOf(vm.serverNow()) }
        LaunchedEffect(code) { while (now < code.expiresAt) { delay(1000); now = vm.serverNow() } }
        AlertDialog(onDismissRequest = { vm.pairing = null }, icon = { Icon(Icons.Outlined.Link, null) },
            title = { Text("Hubungkan HP anak") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Di HP anak, pilih “Aku anak”, lalu masukkan kode ini. Gunakan alamat server yang sama.")
                    Text(if (now < code.expiresAt) code.code.chunked(3).joinToString(" ") else "Kedaluwarsa", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Forest)
                    Text("Berlaku 10 menit dan hanya sekali pakai.", style = MaterialTheme.typography.bodySmall)
                }
            }, confirmButton = { TextButton(onClick = { vm.pairing = null }) { Text("Selesai") } })
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
    var child by remember { mutableStateOf(false) }
    var register by remember { mutableStateOf(false) }
    var server by remember { mutableStateOf(vm.api.baseUrl) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Brand()
        Surface(color = Forest, shape = RoundedCornerShape(28.dp)) {
            Column(Modifier.fillMaxWidth().padding(26.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("KEBIASAAN KECIL, PAHALA BESAR", color = Lime, fontSize = 10.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold)
                Text("Ngaji dulu.\nNonton kemudian.", color = Color.White, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.MenuBook, null, tint = Lime)
                    Text("Satu bacaan baik sebelum\npetualangan di layar.", color = Color(0xFFD6E4D8), fontSize = 14.sp)
                    Icon(Icons.Outlined.East, null, tint = Lime)
                    Icon(Icons.Outlined.Tv, null, tint = Lime)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Choice("Orang tua", !child, Modifier.weight(1f)) { child = false }
            Choice("Aku anak", child, Modifier.weight(1f)) { child = true }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (child) "Halo, teman kecil!" else if (register) "Mulai keluarga barumu" else "Selamat datang kembali", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(if (child) "Minta kode penghubung dari akun orang tuamu." else "Temani kebiasaan baik, satu bacaan setiap hari.", color = Muted, fontSize = 14.sp)
            if (child) Field("Kode dari orang tua", code, { code = it.filter(Char::isDigit).take(6) }, KeyboardType.Number)
            else {
                if (register) Field("Nama orang tua", name, { name = it })
                Field("Email", email, { email = it }, KeyboardType.Email)
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Kata sandi · minimal 10 karakter") }, visualTransformation = PasswordVisualTransformation(), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
            }
            Field("Alamat server keluarga", server, { server = it }, KeyboardType.Uri)
            Button(onClick = { vm.authenticate(server, email, password, name, code, child, register) }, enabled = !vm.busy && server.isNotBlank() && if (child) code.length == 6 else email.isNotBlank() && password.length >= 10 && (!register || name.isNotBlank()), modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) {
                Text(if (child) "Hubungkan perangkat" else if (register) "Buat akun orang tua" else "Masuk", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(10.dp)); Icon(Icons.Outlined.East, null, Modifier.size(18.dp))
            }
            if (!child) TextButton(onClick = { register = !register }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(if (register) "Sudah punya akun? Masuk" else "Belum punya akun? Daftar") }
        }
        Text("Rekaman hanya tersedia untuk keluargamu. Mode AI perlu persetujuan orang tua.", color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@Composable private fun Header(vm: AppViewModel, data: Dashboard) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Brand(); Spacer(Modifier.weight(1f))
        IconButton(onClick = vm::refresh, enabled = !vm.busy) { Icon(Icons.Outlined.Refresh, "Perbarui") }
        IconButton(onClick = vm::logout, enabled = !vm.busy) { Icon(Icons.AutoMirrored.Outlined.Logout, "Keluar dari akun ${data.name}") }
    }
}

@Composable private fun ChildHome(vm: AppViewModel, data: Dashboard) {
    val child = data.children.firstOrNull() ?: return
    val surah = data.surahs.first { it.id == child.surahId }
    var now by remember { mutableLongStateOf(vm.serverNow()) }
    LaunchedEffect(Unit) { while (true) { now = vm.serverNow(); delay(1000) } }
    val approved = data.recitations.firstOrNull { it.canUse(now) }
    val pending = data.recitations.any { it.status == "pending" }
    val wifi = child.tvTransport == "wifi"
    val connectionProblem = if (wifi) runCatching {
        if (child.tvPlatform == "wol") vm.wifiTv.prepare(child.tvMac) else vm.wifiTv.endpoint(child.tvHost)
    }.exceptionOrNull()?.message else if (!vm.infrared.available) "HP ini belum punya IR blaster" else if (child.tvBrand == "none") "Minta orang tua memilih profil TV" else null
    val paired = !wifi || vm.tvRemote.paired(child)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Assalamu’alaikum, ${data.name} ☀", color = Muted, fontSize = 14.sp)
        Text("Mulai dengan\nsatu kebaikan.", fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp)
    }
    Surface(color = Forest, shape = RoundedCornerShape(26.dp)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(if (approved != null) "IZIN TERBUKA" else "MISI NGAJI", Lime, Forest)
                Spacer(Modifier.weight(1f)); Icon(Icons.Outlined.AutoAwesome, null, tint = Lime)
            }
            Text(if (approved != null) "Alhamdulillah,\nbacaanmu disetujui!" else "Baca ${surah.name},\nlalu buka waktu nonton.", color = Color.White, fontSize = 25.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold)
            Text(if (approved != null) "Izin satu kali power. Berlaku sampai ${time(approved.expiresAt ?: now)}." else "${surah.verses} ayat  •  Minimal ${child.minSeconds} detik\nDiperiksa ${if (child.mode == "ai") "AI, dengan bantuan orang tua" else "orang tua"}", color = Color(0xFFD6E4D8), lineHeight = 22.sp, fontSize = 14.sp)
            if (approved == null) Button(onClick = { vm.screen = "record" }, modifier = Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Forest), shape = RoundedCornerShape(14.dp), enabled = !vm.busy) {
                Icon(Icons.Outlined.Mic, null); Spacer(Modifier.width(8.dp)); Text("Yuk, mulai ngaji", fontWeight = FontWeight.Bold)
            }
        }
    }
    Surface(color = Color.White, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Tv, null); Spacer(Modifier.width(10.dp)); Text(if (wifi) "TV lewat Wi-Fi" else "Remote infrared", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.weight(1f)); Icon(if (approved != null) Icons.Outlined.LockOpen else Icons.Outlined.Lock, null, tint = Muted)
            }
            FilledIconButton(onClick = { approved?.let(vm::power) }, enabled = approved != null && connectionProblem == null && paired && !vm.busy,
                modifier = Modifier.size(88.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Forest, disabledContainerColor = Soft)) {
                Icon(Icons.Outlined.PowerSettingsNew, if (wifi) "Nyalakan TV lewat Wi-Fi" else "Kirim power TV", Modifier.size(38.dp))
            }
            Text(connectionProblem ?: when { !paired -> "Hubungkan TV di HP ini terlebih dahulu"; approved != null -> if (wifi) "HP dan TV harus di jaringan Wi-Fi yang sama. Tekan untuk mengirim permintaan nyalakan." else "Arahkan infrared HP ke TV, lalu tekan power"; pending -> "Menunggu bacaan diperiksa"; else -> "Terbuka setelah bacaan disetujui" }, color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
            if (wifi && child.tvPlatform != "wol") {
                OutlinedButton(onClick = { vm.pairTv(child) }, enabled = !vm.busy && connectionProblem == null) { Text(if (paired) "Hubungkan ulang TV" else "Hubungkan TV") }
                Text("Nyalakan TV saat pairing. Android/Google TV menampilkan kode; Samsung/LG meminta persetujuan di layar. Pairing tidak membuka izin power.", color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
            }
            Text(if (wifi) "Menyalakan dari standby memerlukan dukungan jaringan TV. Jika tersedia, isi MAC untuk Wake-on-LAN. Pengiriman perintah belum menjamin TV menyala." else "Kontrol infrared perlu HP dengan IR blaster dan profil TV yang cocok.", color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }
    Text("LANGKAH KECILMU", color = Muted, fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Step("01", "Rekam ngaji", true, Modifier.weight(1f))
        Step("02", "Diperiksa", pending || approved != null, Modifier.weight(1f))
        Step("03", "Power TV", approved != null, Modifier.weight(1f))
    }
    data.recitations.firstOrNull()?.let { r -> Text(r.feedback, color = Muted, fontSize = 13.sp) }
}

@Composable private fun ParentHome(vm: AppViewModel, data: Dashboard) {
    val pending = data.recitations.filter { it.status == "pending" }
    Text("Assalamu’alaikum, ${data.name}", color = Muted, fontSize = 14.sp)
    Text("Temani tumbuhnya\nkebiasaan baik.", fontSize = 31.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold)
    Surface(color = Forest, shape = RoundedCornerShape(24.dp)) {
        Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("RUANG ORANG TUA", color = Lime, fontSize = 10.sp, letterSpacing = 1.5.sp)
                Text("${pending.size} bacaan\nmenunggu didengar", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                Text("Persetujuanmu membuka satu kali power TV.", color = Color(0xFFD6E4D8), fontSize = 12.sp)
            }
            Icon(Icons.Outlined.Headphones, null, tint = Lime, modifier = Modifier.size(48.dp))
        }
    }
    if (data.children.isEmpty()) {
        EmptyCard("Awali dari keluarga", "Buat profil anak, lalu hubungkan HP-nya menggunakan kode enam angka.")
        AddChild(vm)
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Perlu diperiksa", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f)); Pill("${pending.size} bacaan", Soft, Forest)
        }
        if (pending.isEmpty()) EmptyCard("Semua sudah didengar", "Rekaman baru dari anak akan muncul di sini. Halaman diperbarui otomatis.")
        pending.forEach { RecordingCard(vm, data, it, true) }
        Text("KELUARGAMU", color = Muted, fontSize = 11.sp, letterSpacing = 1.5.sp)
        data.children.forEach { ChildCard(vm, data, it) }
    }
}

@Composable private fun RecorderScreen(vm: AppViewModel, data: Dashboard) {
    val context = LocalContext.current
    val c = data.children.first()
    val surah = data.surahs.first { it.id == c.surahId }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startRecording() else vm.message = "Izinkan mikrofon untuk merekam bacaanmu."
    }
    androidx.activity.compose.BackHandler { if (vm.recording || vm.recordedFile != null) vm.message = "Selesaikan atau hapus rekaman dulu, ya." else vm.screen = "home" }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (vm.recording || vm.recordedFile != null) vm.message = "Selesaikan atau hapus rekaman dulu, ya." else vm.screen = "home" }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Kembali") }
            Text("Waktunya mengaji", fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        Pill("BACA PELAN, DENGAN HATI", Soft, Forest)
        Text(surah.name, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text("${surah.verses} ayat · Minimal ${c.minSeconds} detik", color = Muted)
        Surface(color = Color.White, shape = RoundedCornerShape(26.dp)) {
            Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("بِسْمِ اللَّهِ الرَّحْمَٰنِ الرَّحِيمِ", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 24.sp, lineHeight = 40.sp)
                Text(surah.arabic, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 28.sp, lineHeight = 55.sp)
            }
        }
        Text("%02d:%02d".format(vm.seconds / 60, vm.seconds % 60), fontSize = 38.sp, fontWeight = FontWeight.Light, color = if (vm.recording) Forest else Muted)
        Text(when { vm.recording -> "● Merekam… lanjutkan sampai semua ayat selesai"; vm.recordedFile != null -> "Bacaanmu siap dikirim"; else -> "Cari tempat tenang, lalu tekan mulai" }, color = Muted, textAlign = TextAlign.Center, fontSize = 13.sp)
        if (vm.recordedFile != null) {
            OutlinedButton(onClick = vm::previewRecording, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                Icon(if (vm.playing == "local") Icons.Outlined.StopCircle else Icons.Outlined.PlayCircle, null)
                Spacer(Modifier.width(8.dp)); Text(if (vm.playing == "local") "Hentikan pratinjau" else "Dengarkan dulu")
            }
            Button(onClick = vm::upload, enabled = !vm.busy && vm.seconds >= c.minSeconds, modifier = Modifier.fillMaxWidth().height(54.dp)) { Icon(Icons.AutoMirrored.Outlined.Send, null); Spacer(Modifier.width(8.dp)); Text("Kirim bacaan") }
            TextButton(onClick = vm::discardRecording, enabled = !vm.busy) { Text("Hapus dan rekam ulang") }
            if (vm.seconds < c.minSeconds) Text("Rekaman perlu minimal ${c.minSeconds} detik. Coba baca ulang dengan tenang.", color = Amber, textAlign = TextAlign.Center)
        } else {
            Button(onClick = {
                if (vm.recording) vm.finishRecording()
                else if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.startRecording()
                else permission.launch(Manifest.permission.RECORD_AUDIO)
            }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp)) {
                Icon(if (vm.recording) Icons.Outlined.Stop else Icons.Outlined.Mic, null); Spacer(Modifier.width(10.dp)); Text(if (vm.recording) "Selesai merekam" else "Mulai merekam")
            }
            if (vm.recording) TextButton(onClick = vm::discardRecording) { Text("Batalkan rekaman") }
        }
        Text(if (c.mode == "ai") "Audio dikirim ke penyedia AI untuk pemeriksaan. Jika ragu, orang tua akan mendengarkan. AI belum menilai tajwid secara menyeluruh." else "Salinan rekaman akan masuk ke akun orang tua untuk didengarkan dan disetujui.", color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

@Composable private fun History(vm: AppViewModel, data: Dashboard) {
    Text("Jejak bacaan baik", fontSize = 28.sp, fontWeight = FontWeight.Bold)
    Text("Rekaman disimpan selama 7 hari. Setiap izin hanya bisa dipakai sekali.", color = Muted, fontSize = 14.sp)
    if (data.recitations.isEmpty()) EmptyCard("Belum ada bacaan", "Perjalanan kecilmu dimulai dari satu rekaman ngaji.")
    data.recitations.forEach { RecordingCard(vm, data, it, data.role == "parent") }
}

@Composable private fun RecordingCard(vm: AppViewModel, data: Dashboard, r: Recitation, parent: Boolean) {
    var confirmDelete by remember { mutableStateOf(false) }
    Surface(color = Color.White, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).background(Soft, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Outlined.MenuBook, null) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(data.surahs.first { it.id == r.surahId }.name, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text("${data.children.find { it.id == r.childId }?.name.orEmpty()} · ${r.duration.roundToInt()} dtk · ${time(r.createdAt)}", color = Muted, fontSize = 12.sp)
                }
                IconButton(onClick = { vm.play(r) }, enabled = !vm.busy) { Icon(if (vm.playing == r.id) Icons.Outlined.StopCircle else Icons.Outlined.PlayCircle, if (vm.playing == r.id) "Hentikan" else "Dengarkan rekaman", tint = Forest, modifier = Modifier.size(32.dp)) }
            }
            val label = when { r.usedAt != null -> "Izin sudah dipakai"; r.status == "pending" -> "Menunggu diperiksa"; r.status == "rejected" -> "Coba lagi"; (r.expiresAt ?: 0) <= vm.serverNow() -> "Izin kedaluwarsa"; else -> "Disetujui ${if (r.reviewer == "ai") "AI" else "orang tua"}" }
            Pill(label, if (r.status == "approved") Soft else Color(0xFFFFF0D8), if (r.status == "approved") Forest else Amber)
            Text(r.feedback, fontSize = 13.sp, color = Muted)
            if (parent && r.status == "pending") {
                Text("Dengarkan bacaan, lalu beri keputusan.", fontSize = 12.sp, color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { vm.review(r.id, false) }, enabled = !vm.busy, modifier = Modifier.weight(1f)) { Text("Ulangi") }
                    Button(onClick = { vm.review(r.id, true) }, enabled = !vm.busy, modifier = Modifier.weight(1f)) { Text("Setujui") }
                }
            }
            if (parent) TextButton(onClick = { confirmDelete = true }, enabled = !vm.busy, contentPadding = PaddingValues(0.dp)) { Text("Hapus rekaman", color = Muted, fontSize = 12.sp) }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Hapus rekaman?") }, text = { Text("Audio dan izin dari bacaan ini akan dihapus.") }, confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteRecording(r.id) }) { Text("Hapus") } }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Batal") } })
}

@Composable private fun Family(vm: AppViewModel, data: Dashboard) {
    Text("Ruang keluarga", fontSize = 28.sp, fontWeight = FontWeight.Bold)
    Text("Atur bacaan, metode pemeriksaan, dan profil TV untuk masing-masing anak.", color = Muted)
    data.children.forEach { ChildCard(vm, data, it) }
    AddChild(vm)
    Text("Kode penghubung hanya untuk HP anak. Jangan bagikan kata sandi akun orang tua.", fontSize = 12.sp, color = Muted)
}

@Composable private fun ChildCard(vm: AppViewModel, data: Dashboard, child: Child) {
    Surface(color = Color.White, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).background(Lime, CircleShape), contentAlignment = Alignment.Center) { Text(child.name.take(1).uppercase(), fontWeight = FontWeight.Bold, fontSize = 20.sp) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(child.name, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text("${data.surahs.first { it.id == child.surahId }.name} · ${if (child.mode == "ai") "Verifikasi AI" else "Diperiksa orang tua"}", color = Muted, fontSize = 12.sp)
                }
                IconButton(onClick = { vm.screen = "settings:${child.id}" }) { Icon(Icons.Outlined.Tune, "Atur ${child.name}") }
            }
            OutlinedButton(onClick = { vm.pairChild(child.id) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Link, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Buat kode hubungkan HP") }
        }
    }
}

@Composable private fun AddChild(vm: AppViewModel) {
    var name by remember { mutableStateOf("") }
    Surface(color = Soft, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Tambah teman kecil", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Field("Nama panggilan anak", name, { name = it.take(40) })
            Button(onClick = { vm.addChild(name.trim()); name = "" }, enabled = !vm.busy && name.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Add, null); Text("Buat profil anak") }
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.screen = "family" }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Kembali") }
            Text("Aturan untuk ${child.name}", fontSize = 22.sp, fontWeight = FontWeight.Bold)
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
        data.surahs.forEach { s -> Choice("${s.name} · ${s.verses} ayat", surah == s.id, Modifier.fillMaxWidth()) { surah = s.id } }
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
        val validConnection = transport != "wifi" || (if (platform == "wol") runCatching { WakePacket.macBytes(mac) }.isSuccess else runCatching { LanAddress.parse(host) }.isSuccess && (mac.isBlank() || runCatching { WakePacket.macBytes(mac) }.isSuccess))
        Button(onClick = { vm.policy(child, Policy(mode, surah, seconds.roundToInt(), minutes.roundToInt(), brand, consent, transport, mac, platform, host)) }, enabled = !vm.busy && validConnection && (mode != "ai" || consent && data.aiAvailable), modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Simpan aturan") }
        Text("Menyimpan aturan membatalkan izin lama agar tugas baru tidak terlewati.", color = Muted, fontSize = 12.sp)
        OutlinedButton(onClick = { revoke = true }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Putuskan semua HP anak") }
    }
    if (revoke) AlertDialog(onDismissRequest = { revoke = false }, title = { Text("Putuskan perangkat anak?") }, text = { Text("Semua HP ${child.name} perlu dihubungkan ulang. Izin power yang belum dipakai ikut dicabut.") }, confirmButton = { TextButton(onClick = { revoke = false; vm.revoke(child.id) }) { Text("Putuskan") } }, dismissButton = { TextButton(onClick = { revoke = false }) { Text("Batal") } })
}

@Composable private fun Guide(vm: AppViewModel) {
    Text("Ngaji jadi kebiasaan", fontSize = 28.sp, fontWeight = FontWeight.Bold)
    EmptyCard("01 · Baca dan rekam", "Baca semua ayat dengan tenang. Mikrofon hanya aktif saat kamu menekan mulai merekam.")
    EmptyCard("02 · Tunggu diperiksa", "Orang tua mendengar rekamanmu. Jika memakai AI, bacaan yang meragukan tetap diperiksa orang tua.")
    EmptyCard("03 · Arahkan, lalu power", "Setelah disetujui, gunakan infrared atau kirim permintaan nyalakan melalui Wi-Fi sesuai pengaturan orang tua. Satu bacaan membuka satu kali tombol.")
    Text(if (vm.infrared.available) "Infrared tersedia di HP ini. Minta orang tua memilih profil TV di pengaturan keluarga." else "HP ini tidak memiliki IR blaster. Gunakan mode Wi-Fi untuk TV yang mendukung remote jaringan atau bangun dari standby.", color = Muted)
    Text("Aplikasi mengatur remote di JagaBocil. Tombol fisik TV dan remote lain tetap bisa digunakan.", color = Muted, fontSize = 13.sp)
}

@Composable private fun Field(label: String, value: String, changed: (String) -> Unit, type: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(value = value, onValueChange = changed, label = { Text(label, fontSize = 13.sp) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = type), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
}
@Composable private fun Choice(text: String, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, click: () -> Unit) {
    OutlinedButton(onClick = click, modifier = modifier, enabled = enabled, shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp), colors = ButtonDefaults.outlinedButtonColors(containerColor = if (selected) Forest else Color.Transparent, contentColor = if (selected) Color.White else Forest)) { Text(text, fontSize = 13.sp, textAlign = TextAlign.Center) }
}
@Composable private fun Pill(text: String, background: Color, color: Color) {
    Text(text, modifier = Modifier.background(background, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp), color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp)
}
@Composable private fun Step(number: String, title: String, active: Boolean, modifier: Modifier) {
    Column(modifier.background(if (active) Soft else Color.White, RoundedCornerShape(16.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(number, color = Muted, fontSize = 12.sp)
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
    }
}
@Composable private fun EmptyCard(title: String, body: String) {
    Surface(color = Soft, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(body, color = Muted, fontSize = 14.sp, lineHeight = 22.sp)
        }
    }
}
private fun time(millis: Long) = SimpleDateFormat("dd MMM, HH:mm", Locale.forLanguageTag("id-ID")).format(Date(millis))
