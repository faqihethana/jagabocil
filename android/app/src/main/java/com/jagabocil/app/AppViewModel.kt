package com.jagabocil.app

import android.app.Application
import android.media.MediaPlayer
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SessionStore(application)
    val api = Api(store.token())
    internal val quran = QuranLibrary(application)
    val infrared = Infrared(application)
    val wifiTv = WifiTv(application)
    val tvRemote = TvWifiRemote(application, wifiTv)
    private val tvDiscovery = TvDiscovery(application, wifiTv)
    private val notifications = ReviewNotifications(application)
    var notificationsEnabled by mutableStateOf(notifications.enabled); private set
    var recoveryCode by mutableStateOf<String?>(null); private set
    var tvPairCodeNeeded by mutableStateOf(false); private set
    private var foreground = true
    private val recorder = AudioRecorder(application)
    private var player: MediaPlayer? = null
    private var playbackFile: File? = null
    private var ticker: Job? = null
    private var recordedSurahId: String? = null
    private var clockOffset = 0L
    var dashboard by mutableStateOf<Dashboard?>(null); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)
    var recording by mutableStateOf(false); private set
    var seconds by mutableIntStateOf(0); private set
    var recordedFile by mutableStateOf<File?>(null); private set
    var playing by mutableStateOf<String?>(null); private set
    var pairing by mutableStateOf<PairCode?>(null)
        private set
    var pairingChild by mutableStateOf<Child?>(null)
        private set
    var setupTarget by mutableStateOf<Child?>(null)
        private set
    var tvTestSent by mutableStateOf(false)
        private set
    private var reviewFromNotification: String? = null
    var screen by mutableStateOf("home")
    val loggedIn get() = api.token.isNotEmpty()
    fun serverNow() = System.currentTimeMillis() + clockOffset

    init {
        application.cacheDir.listFiles()?.filter { it.name.startsWith("ngaji-") || it.name.startsWith("listen-") }?.forEach { it.delete() }
        if (loggedIn) {
            notifications.schedule()
            refresh()
        }
    }
    private fun action(onError: ((String) -> Unit)? = null, work: suspend () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try { work() }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is ApiError && e.status == 401 && loggedIn) clearSession()
                val error = when (e) {
                    is ApiError -> e.message ?: "Ada kendala. Coba lagi, ya."
                    is java.io.IOException -> "JagaBocil belum bisa terhubung. Periksa koneksi internet lalu coba lagi."
                    else -> e.message ?: "Ada kendala. Coba lagi, ya."
                }
                if (onError == null) message = error else onError(error)
            } finally { busy = false }
        }
    }
    private fun clearSession() {
        store.clear()
        api.token = ""
        dashboard = null
        recoveryCode = null
        dismissPairing()
        setupTarget = null
        tvTestSent = false
        reviewFromNotification = null
        screen = "home"
        tvRemote.clear()
        tvPairCodeNeeded = false
        notifications.clearSession()
    }
    private suspend fun load() {
        val token = api.token
        val result = api.dashboard()
        clockOffset = result.serverTime - System.currentTimeMillis()
        dashboard = result
        reviewFromNotification?.let { openReview(it) }
        if (notificationsEnabled) withContext(Dispatchers.IO) { notifications.observe(result, token) }
    }
    fun refresh() { if (foreground && loggedIn) action { load() } }
    fun authenticate(username: String, password: String, code: String, child: Boolean, register: Boolean) = action {
        val body = if (child) api.body("code" to code.trim()) else api.body("username" to username.trim(), "password" to password)
        val auth = api.json.decodeFromString<Auth>(api.request(if (child) "/pair" else if (register) "/auth/register" else "/auth/login", "POST", body, authenticated = false))
        api.token = auth.token
        store.save(auth.token)
        screen = "home"
        load()
        if (child) {
            val data = dashboard
            val profile = data?.children?.firstOrNull()
            if ((data?.tvSetupUntil ?: 0) > serverNow() || profile?.tvTransport == "wifi" && !tvRemote.paired(profile)) {
                screen = "tv-setup"
            }
        }
        notifications.schedule()
    }
    fun setNotifications(enabled: Boolean) {
        if (!enabled) {
            notifications.disable()
            notificationsEnabled = false
            return
        }
        val data = dashboard ?: return
        if (!notifications.permitted()) {
            message = "Izinkan notifikasi JagaBocil di pengaturan Android."
            return
        }
        notifications.enable(data, api.token)
        notificationsEnabled = true
    }
    fun createRecoveryCode(password: String, onError: (String) -> Unit, onComplete: () -> Unit) = action(onError) {
        recoveryCode = api.json.decodeFromString<RecoveryCode>(
            api.request("/auth/recovery-code", "POST", api.body("password" to password))
        ).code
        dashboard = dashboard?.copy(recoveryConfigured = true)
        onComplete()
    }
    fun dismissRecoveryCode() { recoveryCode = null }
    fun startCheckout(openBrowser: (String) -> Unit) = action {
        val result = api.json.decodeFromString<BillingResult>(api.request("/billing/checkout", "POST"))
        dashboard = dashboard?.copy(billing = result.billing)
        val url = result.checkoutUrl
        if (url != null) {
            val uri = url.toUri()
            check(uri.scheme == "https" && uri.host == "app.sandbox.midtrans.com" && uri.userInfo == null) {
                "Alamat checkout Midtrans tidak valid."
            }
            openBrowser(url)
        } else message = billingMessage(result.billing)
    }
    fun checkPayment() = action {
        val result = api.json.decodeFromString<BillingResult>(api.request("/billing/sync", "POST"))
        dashboard = dashboard?.copy(billing = result.billing)
        message = billingMessage(result.billing)
    }
    private fun billingMessage(billing: Billing) = when {
        billing.pending -> "Pembayaran belum terkonfirmasi. Cek lagi sebentar."
        (billing.expiresAt ?: 0L) > serverNow() -> "JagaBocil Plus Sandbox aktif."
        else -> "Belum ada pembayaran terkonfirmasi. Mulai checkout jika ingin mencoba lagi."
    }
    fun recover(username: String, code: String, password: String, onError: (String) -> Unit, onComplete: () -> Unit) = action(onError) {
        api.request("/auth/recover", "POST",
            api.body("username" to username, "code" to code, "password" to password), authenticated = false)
        onComplete()
        message = "Kata sandi sudah diganti. Silakan masuk dengan kata sandi baru."
    }
    fun logout() = action {
        stopPlayback(); discardRecording()
        runCatching { api.request("/auth/logout", "POST") }
        clearSession()
    }
    fun addChild(name: String, onCreated: () -> Unit) = action {
        val result = api.json.decodeFromString<CreatedChild>(api.request("/children", "POST", api.body("name" to name)))
        pairingChild = result.child
        pairing = result.pairing
        dashboard = dashboard?.let { it.copy(children = it.children + result.child) }
        onCreated()
        load()
    }
    fun pairChild(child: Child) = action {
        pairing = api.json.decodeFromString<PairResponse>(api.request("/children/${child.id}/pairing", "POST")).pairing
        pairingChild = child
    }
    fun dismissPairing() { pairing = null; pairingChild = null }
    fun policy(child: Child, p: Policy) = action {
        api.request("/children/${child.id}/policy", "PUT", api.json.encodeToString(p).toRequestBody("application/json".toMediaType()))
        load(); message = "Aturan disimpan. Anak perlu merekam ngaji baru."; screen = "family"
    }
    fun revoke(id: String) = action { api.request("/children/$id/devices", "DELETE"); load(); message = "Perangkat anak diputus dan izin dicabut." }
    fun review(id: String, approve: Boolean) = action {
        api.request("/recitations/$id/review", "POST", api.body("decision" to if (approve) "approve" else "reject")); load()
        if (screen == "review:$id") { stopPlayback(); screen = "home" }
        message = if (approve) "Bacaan disetujui. Izin TV sudah tersedia." else "Permintaan untuk membaca ulang sudah dikirim."
    }
    fun openReview(id: String) {
        if (!loggedIn) return
        val data = dashboard
        if (data == null) { reviewFromNotification = id; refresh(); return }
        reviewFromNotification = null
        if (data.role != "parent") return
        screen = "review:$id"
        refresh()
    }
    fun deleteRecording(id: String) = action {
        stopPlayback()
        api.request("/recitations/$id", "DELETE")
        load()
        if (screen == "review:$id") screen = "home"
    }
    fun startRecording() {
        val chapter = dashboard?.children?.firstOrNull()?.surahId?.let(quran::chapter) ?: return
        val maxSeconds = chapter.maxRecordingSeconds
        try {
            stopPlayback()
            recorder.start(maxSeconds)
            recordedSurahId = chapter.id
            recordedFile = null; seconds = 0; recording = true
            val started = SystemClock.elapsedRealtime()
            ticker = viewModelScope.launch {
                while (recording) {
                    delay(200); seconds = ((SystemClock.elapsedRealtime() - started) / 1000).toInt()
                    if (seconds >= maxSeconds - 5) finishRecording()
                }
            }
        } catch (_: Exception) { message = "Mikrofon belum bisa dipakai. Cek izin mikrofon, lalu coba lagi." }
    }
    fun finishRecording() {
        if (!recording) return
        ticker?.cancel(); recording = false
        try { recordedFile = recorder.stop() } catch (_: Exception) { recordedFile = null; message = "Rekaman terlalu singkat atau terputus. Yuk, ulangi." }
    }
    fun discardRecording() { stopPlayback(); ticker?.cancel(); recording = false; recorder.discard(); recordedFile = null; recordedSurahId = null; seconds = 0 }
    fun foregrounded() {
        val wasActive = foreground
        foreground = true
        if (!wasActive) refresh()
    }
    fun backgrounded() { foreground = false; cancelTvPair(); if (recording) { discardRecording(); message = "Rekaman dihentikan saat aplikasi ditutup. Silakan rekam ulang." }; stopPlayback() }
    fun cancelTvPair() { tvRemote.cancelPair(); tvPairCodeNeeded = false }
    internal suspend fun discoverTvs() = tvDiscovery.find()

    internal fun chooseSetupTv(child: Child, tv: DiscoveredTv) {
        if (busy) return
        val target = child.copy(tvTransport = "wifi", tvPlatform = tv.platform.id, tvHost = tv.host, tvMac = "")
        setupTarget = target
        if (!tvRemote.paired(target)) pairTv(target)
    }

    fun leaveTvSetup() {
        cancelTvPair()
        setupTarget = null
        tvTestSent = false
        screen = "home"
    }

    fun testSetupTv() = action {
        val target = setupTarget ?: return@action
        val command = try {
            tvRemote.prepare(target)
        } catch (error: IllegalStateException) {
            throw IllegalStateException("TV belum merespons. Pastikan HP dan TV memakai Wi-Fi yang sama. Coba lagi saat koneksi Wi-Fi TV masih aktif.", error)
        }
        try {
            check(foreground) { "Buka JagaBocil untuk mengetes TV." }
            val result = api.json.decodeFromString<TvSetupResult>(api.request("/tv/setup", "POST", api.body("platform" to target.tvPlatform, "host" to target.tvHost)))
            dashboard = dashboard?.copy(children = listOf(result.child), tvSetupUntil = null)
            setupTarget = null
            val permit = result.permit
            check(permit.authorized && permit.expiresAt > serverNow() && permit.tvTransport == "wifi" && permit.tvPlatform == target.tvPlatform && permit.tvHost == target.tvHost) { "Izin tes TV tidak berlaku. Coba cek TV dari Panduan." }
            check(foreground) { "Tes berhenti saat aplikasi ditutup. Cek TV dari Panduan." }
            try { command.send() }
            catch (error: java.io.IOException) { throw IllegalStateException("Sinyal tes gagal dikirim. Cek TV dari Panduan.", error) }
            tvTestSent = true
        } finally { withContext(Dispatchers.IO) { runCatching { command.close() } } }
    }

    internal fun chooseFamilyTv(child: Child, tv: DiscoveredTv) = policy(child, Policy(
        child.mode, child.surahId, child.minSeconds, child.unlockMinutes, child.tvBrand, child.aiConsent,
        "wifi", "", tv.platform.id, tv.host,
    ))

    fun pairTv(c: Child) = action {
        message = "Nyalakan TV dan setujui JagaBocil pada layar TV jika diminta."
        val paired = try { tvRemote.pair(c) } catch (error: java.io.IOException) { throw IllegalStateException("TV belum bisa dihubungi. Nyalakan TV, pastikan HP dan TV memakai Wi-Fi yang sama, lalu coba lagi.", error) }
        if (!foreground) { cancelTvPair(); return@action }
        tvPairCodeNeeded = !paired
        if (paired) { load(); message = "TV terhubung ke HP ini." }
    }
    fun finishTvPair(code: String) = action {
        try { tvRemote.finishPair(code); load(); message = "Android TV terhubung." }
        finally { tvPairCodeNeeded = false }
    }
    fun checkTv(c: Child) = action {
        if (c.tvTransport == "wifi") {
            tvRemote.checkConnection(c)
            message = if (c.tvPlatform == "wol") {
                "Wi-Fi dan MAC siap untuk Wake-on-LAN. Coba nyalakan TV dari standby setelah mendapat izin."
            } else {
                "Koneksi TV berhasil. Izin power tetap utuh."
            }
        } else {
            infrared.check(c.tvBrand)
            message = "Infrared 38 kHz siap di HP ini. Coba tombol power setelah mendapat izin."
        }
    }
    fun upload() = action {
        val child = dashboard?.children?.firstOrNull() ?: return@action
        val file = recordedFile ?: return@action
        val surahId = recordedSurahId ?: return@action
        require(surahId == child.surahId) { "Surah tugas berubah. Hapus rekaman ini dan mulai lagi." }
        val result = api.json.decodeFromString<UploadedRecitation>(api.upload(file, surahId)).recitation
        discardRecording()
        dashboard = dashboard?.let { it.copy(recitations = listOf(result) + it.recitations.filter { rec -> rec.id != result.id }) }
        screen = "home"
        message = "Rekaman terkirim. Terima kasih sudah mengaji!"
    }
    fun play(rec: Recitation) = action {
        if (playing == rec.id) { stopPlayback(); return@action }
        stopPlayback()
        val file = File.createTempFile("listen-", ".m4a", getApplication<Application>().cacheDir)
        playbackFile = file
        api.audio(rec.id, file)
        player = MediaPlayer().apply {
            setDataSource(file.absolutePath); prepare(); start()
            setOnCompletionListener { stopPlayback() }
            setOnErrorListener { _, _, _ -> stopPlayback(); message = "Rekaman belum bisa diputar."; true }
        }
        playing = rec.id
    }
    fun previewRecording() {
        if (playing == "local") { stopPlayback(); return }
        stopPlayback()
        runCatching {
            val file = recordedFile ?: return
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath); prepare(); start()
                setOnCompletionListener { stopPlayback() }
                setOnErrorListener { _, _, _ -> stopPlayback(); true }
            }
            playing = "local"
        }.onFailure { stopPlayback(); message = "Rekaman belum bisa diputar. Coba rekam ulang." }
    }
    fun stopPlayback() { player?.release(); player = null; playing = null; playbackFile?.delete(); playbackFile = null }
    fun power(rec: Recitation) = action {
        val c = dashboard?.children?.firstOrNull() ?: return@action
        val command = if (c.tvTransport == "wifi") tvRemote.prepare(c) else null
        try {
            check(foreground) { "Permintaan dibatalkan karena aplikasi ditutup." }
            if (command == null) infrared.check(c.tvBrand)
            val result = api.json.decodeFromString<Permit>(api.request("/tv/authorize", "POST", api.body("recitationId" to rec.id, "tvTransport" to c.tvTransport)))
            require(result.authorized && result.expiresAt > serverNow()) { "Izin kedaluwarsa. Minta persetujuan baru." }
            check(foreground) { "Permintaan dibatalkan saat aplikasi ditutup. Izin yang sudah digunakan tidak dikembalikan." }
            require(result.tvTransport == c.tvTransport) { "Metode koneksi TV berubah. Muat ulang pengaturan." }
            if (result.tvTransport == "wifi") {
                require(command != null && c.tvMac == result.tvMac && c.tvPlatform == result.tvPlatform && c.tvHost == result.tvHost) { "Alamat TV berubah. Muat ulang pengaturan." }
                command.send()
                message = "Permintaan nyalakan dikirim lewat Wi-Fi. Cek layar TV; dukungan standby berbeda pada tiap model."
            } else { infrared.power(result.tvBrand); message = "Sinyal power dikirim. Arahkan bagian infrared HP ke TV." }
        }
        finally { withContext(Dispatchers.IO) { runCatching { command?.close() } }; load() }
    }
    override fun onCleared() { cancelTvPair(); discardRecording(); stopPlayback(); super.onCleared() }
}
