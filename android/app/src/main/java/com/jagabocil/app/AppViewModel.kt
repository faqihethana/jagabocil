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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SessionStore(application)
    val api = Api(store.server(), store.token())
    val infrared = Infrared(application)
    private val recorder = AudioRecorder(application)
    private var player: MediaPlayer? = null
    private var playbackFile: File? = null
    private var ticker: Job? = null
    private var clockOffset = 0L
    var dashboard by mutableStateOf<Dashboard?>(null); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)
    var recording by mutableStateOf(false); private set
    var seconds by mutableIntStateOf(0); private set
    var recordedFile by mutableStateOf<File?>(null); private set
    var playing by mutableStateOf<String?>(null); private set
    var pairing by mutableStateOf<PairCode?>(null)
    var screen by mutableStateOf("home")
    val loggedIn get() = api.token.isNotEmpty()
    fun serverNow() = System.currentTimeMillis() + clockOffset

    init {
        application.cacheDir.listFiles()?.filter { it.name.startsWith("ngaji-") || it.name.startsWith("listen-") }?.forEach { it.delete() }
        if (loggedIn) refresh()
    }
    private fun action(work: suspend () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try { work() }
            catch (e: Exception) {
                if (e is ApiError && e.status == 401) { store.clear(); api.token = ""; dashboard = null }
                message = when (e) {
                    is ApiError -> e.message
                    is java.io.IOException -> "Server belum bisa dihubungi. Periksa alamat server dan koneksi internet."
                    else -> e.message ?: "Ada kendala. Coba lagi, ya."
                }
            } finally { busy = false }
        }
    }
    private suspend fun load() { val result = api.dashboard(); clockOffset = result.serverTime - System.currentTimeMillis(); dashboard = result }
    fun refresh() = action { load() }
    fun authenticate(server: String, email: String, password: String, name: String, code: String, child: Boolean, register: Boolean) = action {
        val uri = server.trim().toUri()
        require(uri.scheme == "https" || (BuildConfig.DEBUG && uri.scheme == "http" && uri.host in listOf("10.0.2.2", "localhost", "127.0.0.1"))) { "Gunakan alamat HTTPS. Emulator debug boleh memakai http://10.0.2.2:8787." }
        require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) { "Alamat server belum sesuai." }
        api.baseUrl = server.trim().trimEnd('/'); api.token = ""
        val body = if (child) api.body("code" to code.trim()) else if (register) api.body("email" to email.trim(), "password" to password, "name" to name.trim()) else api.body("email" to email.trim(), "password" to password)
        val auth = api.json.decodeFromString<Auth>(api.request(if (child) "/pair" else if (register) "/auth/register" else "/auth/login", "POST", body))
        api.token = auth.token; store.save(auth.token, api.baseUrl); load(); screen = "home"
    }
    fun logout() = action {
        stopPlayback(); discardRecording()
        runCatching { api.request("/auth/logout", "POST") }
        api.token = ""; store.clear(); dashboard = null; screen = "home"
    }
    fun addChild(name: String) = action {
        val result = api.json.decodeFromString<CreatedChild>(api.request("/children", "POST", api.body("name" to name)))
        pairing = result.pairing; load()
    }
    fun pairChild(id: String) = action {
        pairing = api.json.decodeFromString<PairResponse>(api.request("/children/$id/pairing", "POST")).pairing
    }
    fun policy(child: Child, p: Policy) = action {
        api.request("/children/${child.id}/policy", "PUT", api.json.encodeToString(p).toRequestBody("application/json".toMediaType()))
        load(); message = "Aturan disimpan. Anak perlu merekam ngaji baru."; screen = "home"
    }
    fun revoke(id: String) = action { api.request("/children/$id/devices", "DELETE"); load(); message = "Perangkat anak diputus dan izin dicabut." }
    fun review(id: String, approve: Boolean) = action {
        api.request("/recitations/$id/review", "POST", api.body("decision" to if (approve) "approve" else "reject")); load()
    }
    fun deleteRecording(id: String) = action { stopPlayback(); api.request("/recitations/$id", "DELETE"); load() }
    fun startRecording() {
        try {
            stopPlayback(); recorder.start(); recordedFile = null; seconds = 0; recording = true
            val started = SystemClock.elapsedRealtime()
            ticker = viewModelScope.launch {
                while (recording) {
                    delay(200); seconds = ((SystemClock.elapsedRealtime() - started) / 1000).toInt()
                    if (seconds >= 175) finishRecording()
                }
            }
        } catch (_: Exception) { message = "Mikrofon belum bisa dipakai. Cek izin mikrofon, lalu coba lagi." }
    }
    fun finishRecording() {
        if (!recording) return
        ticker?.cancel(); recording = false
        try { recordedFile = recorder.stop() } catch (_: Exception) { recordedFile = null; message = "Rekaman terlalu singkat atau terputus. Yuk, ulangi." }
    }
    fun discardRecording() { stopPlayback(); ticker?.cancel(); recording = false; recorder.discard(); recordedFile = null; seconds = 0 }
    fun backgrounded() { if (recording) { discardRecording(); message = "Rekaman dihentikan saat aplikasi ditutup. Silakan rekam ulang." }; stopPlayback() }
    fun upload() = action {
        val child = dashboard?.children?.firstOrNull() ?: return@action
        val file = recordedFile ?: return@action
        api.upload(file, child.surahId); discardRecording(); load(); screen = "home"
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
        infrared.check(c.tvBrand)
        val result = api.json.decodeFromString<Permit>(api.request("/tv/authorize", "POST", api.body("recitationId" to rec.id)))
        require(result.authorized && result.expiresAt > serverNow()) { "Izin kedaluwarsa. Minta persetujuan baru." }
        try { infrared.power(result.tvBrand); message = "Sinyal power dikirim. Arahkan bagian infrared HP ke TV." }
        finally { load() }
    }
    override fun onCleared() { discardRecording(); stopPlayback(); super.onCleared() }
}
