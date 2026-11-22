package com.jagabocil.app

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PersistableBundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

@Composable internal fun BillingSetting(vm: AppViewModel, billing: Billing) {
    val context = LocalContext.current
    val locale = Locale.forLanguageTag("id-ID")
    val price = NumberFormat.getIntegerInstance(locale).format(billing.amount)
    val activeUntil = billing.expiresAt?.takeIf { it > vm.serverNow() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("JagaBocil Plus Sandbox", style = MaterialTheme.typography.titleMedium)
        Text("Uji checkout Rp$price untuk ${billing.days} hari. Tidak ada uang sungguhan atau perpanjangan otomatis.", style = MaterialTheme.typography.bodySmall)
        if (activeUntil != null) {
            val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(activeUntil))
            Text("Plus Sandbox aktif sampai $date", style = MaterialTheme.typography.bodyMedium)
        }
        if (billing.pending) Text("Menunggu konfirmasi pembayaran", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(enabled = !vm.busy, onClick = {
            vm.startCheckout { context.startActivity(Intent(Intent.ACTION_VIEW, it.toUri())) }
        }) {
            Text(when {
                billing.pending -> "Lanjut bayar"
                activeUntil != null -> "Uji perpanjang Plus"
                else -> "Uji checkout"
            })
        }
        if (billing.pending) TextButton(enabled = !vm.busy, onClick = vm::checkPayment) {
            Text("Cek pembayaran")
        }
    }
}

@Composable internal fun NotificationSetting(vm: AppViewModel, parent: Boolean) {
    val context = LocalContext.current
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) vm.setNotifications(true) else vm.message = "Izinkan notifikasi JagaBocil di pengaturan Android."
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Notifikasi", style = MaterialTheme.typography.titleMedium)
            Text(
                if (parent) "Beri tahu saat ada bacaan baru untuk diperiksa." else "Beri tahu saat bacaan disetujui atau perlu diulang.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = vm.notificationsEnabled, onCheckedChange = { enable ->
            if (enable && Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else vm.setNotifications(enable)
        })
    }
}

@Composable internal fun RecoverySetting(vm: AppViewModel, configured: Boolean) {
    var show by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Pemulihan akun", style = MaterialTheme.typography.titleMedium)
        Text(if (configured) "Kode pemulihan sudah aktif. Membuat kode baru akan membatalkan kode lama."
            else "Siapkan kode pemulihan agar kamu bisa masuk lagi kalau lupa kata sandi.")
        OutlinedButton(onClick = { show = true }, enabled = !vm.busy) {
            Text(if (configured) "Ganti kode pemulihan" else "Buat kode pemulihan")
        }
    }
    if (show) {
        var password by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        val dismiss = { if (!vm.busy) show = false }
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("Buat kode pemulihan") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Masukkan kata sandi saat ini. Simpan kode yang muncul di tempat aman.")
                    PasswordField("Kata sandi saat ini", password, { password = it; error = null })
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.createRecoveryCode(password, onError = { error = it }, onComplete = { show = false }) },
                    enabled = !vm.busy && password.length in 1..128) { Text("Buat kode") }
            },
            dismissButton = { TextButton(onClick = dismiss, enabled = !vm.busy) { Text("Batal") } },
            properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        )
    }
}

@Composable internal fun RecoveryCodeDialog(code: String, dismiss: () -> Unit) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Simpan kode pemulihan") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Simpan kode ini di tempat aman. Kode tampil sekali dan berlaku untuk satu kali mengganti kata sandi.")
                Text(code.split('-').chunked(2).joinToString("\n") { it.joinToString("-") }, style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = {
                    val clip = ClipData.newPlainText("Kode pemulihan JagaBocil", code)
                    if (Build.VERSION.SDK_INT >= 33) {
                        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
                    }
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
                    copied = true
                }) { Text(if (copied) "Kode disalin" else "Salin kode") }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("Selesai") } },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
    )
}

@Composable internal fun RecoverAccountDialog(
    vm: AppViewModel,
    server: String,
    initialUsername: String,
    onDismiss: () -> Unit,
    onRecovered: (String) -> Unit,
) {
    var username by remember { mutableStateOf(initialUsername) }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val dismiss = { if (!vm.busy) onDismiss() }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Pulihkan akun orang tua") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Masukkan kode pemulihan yang pernah kamu simpan.")
                OutlinedTextField(username, { username = it.take(254); error = null }, label = { Text("Username") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.fillMaxWidth())
                PasswordField("Kode pemulihan", code, {
                    code = it.uppercase().filter { char -> char in "0123456789ABCDEF-" }.take(35)
                    error = null
                })
                PasswordField("Kata sandi baru (min. 4 karakter)", password, { password = it; error = null })
                PasswordField("Ulangi kata sandi baru", confirm, { confirm = it; error = null })
                if (confirm.isNotEmpty() && confirm != password) Text("Kata sandi belum sama.", color = MaterialTheme.colorScheme.error)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.recover(server, username.trim(), code, password, onError = { error = it }, onComplete = { onRecovered(username.trim()) }) },
                enabled = !vm.busy && server.isNotBlank() && username.isNotBlank() && code.count { it != '-' } == 32 &&
                    password.length in 4..128 && password == confirm) { Text("Ganti kata sandi") }
        },
        dismissButton = { TextButton(onClick = dismiss, enabled = !vm.busy) { Text("Batal") } },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
    )
}

@Composable private fun PasswordField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true,
        modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = PasswordVisualTransformation())
}
