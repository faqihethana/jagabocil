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
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SessionStore(application)
    val api = Api(store.token())
    internal val quran = QuranLibrary(application)
    internal val bookTemplates: List<Story> by lazy {
        application.assets.open("book-catalog.json").bufferedReader().use { api.json.decodeFromString(it.readText()) }
    }
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
    private var refreshJob: Job? = null
    private var lastLoadedAt = 0L
    private var recordedSurahId: String? = null
    private var recordedRevision: Int? = null
    private var recordedBookId: String? = null
    private var recordedTask: Pair<ReadingTask, Int>? = null
    private var loadedStoryOwner: String? = null
    private var clockOffset = 0L
    var dashboard by mutableStateOf<Dashboard?>(null); private set
    var busy by mutableStateOf(false); private set
    var checkingReadingResult by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)
    var recording by mutableStateOf(false); private set
    var seconds by mutableIntStateOf(0); private set
    var recordedFile by mutableStateOf<File?>(null); private set
    var playing by mutableStateOf<String?>(null); private set
    var selectedSurahId by mutableStateOf<String?>(null); private set
    var readingBook by mutableStateOf(false); private set
    internal var selectedBook by mutableStateOf<Story?>(null); private set
    private var selectedTaskId by mutableStateOf<String?>(null)
    internal var generatedStories by mutableStateOf<Map<String, Story>>(emptyMap()); private set
    var tvPaired by mutableStateOf(false); private set
    private var tvReturnScreen = "home"
    private var accountReturnScreen = "home"
    private var booksReturnScreen = "home"
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
        refreshJob?.cancel()
        refreshJob = null
        busy = true
        viewModelScope.launch {
            try { work() }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is ApiError && e.status == 401 && loggedIn) clearSession()
                val error = if (e is java.io.IOException) "JagaBocil belum bisa terhubung. Periksa koneksi internet lalu coba lagi."
                    else e.message ?: "Ada kendala. Coba lagi, ya."
                if (onError == null) message = error else onError(error)
            } finally { busy = false }
        }
    }
    private fun clearSession() {
        store.clear()
        api.token = ""
        dashboard = null
        recoveryCode = null
        selectedSurahId = null
        readingBook = false
        selectedBook = null
        selectedTaskId = null
        generatedStories = emptyMap()
        loadedStoryOwner = null
        tvPaired = false
        tvReturnScreen = "home"
        accountReturnScreen = "home"
        booksReturnScreen = "home"
        lastLoadedAt = 0L
        reviewFromNotification = null
        screen = "home"
        discardRecording()
        tvRemote.clear()
        tvPairCodeNeeded = false
        notifications.clearSession()
    }
    private suspend fun load() {
        val token = api.token
        val result = api.dashboard()
        coroutineContext.ensureActive()
        clockOffset = result.serverTime - System.currentTimeMillis()
        lastLoadedAt = SystemClock.elapsedRealtime()
        val previous = dashboard
        if (previous == null || previous != result.copy(serverTime = previous.serverTime)) dashboard = result
        if (result.role == "child") {
            val child = result.children.firstOrNull()
            tvPaired = child?.let(tvRemote::paired) == true
            if (child?.readingAssignment != "free") {
                selectedSurahId = null
                selectedBook = null
                readingBook = false
            }
        }
        reviewFromNotification?.let { id ->
            reviewFromNotification = null
            if (result.role == "parent") screen = "review:$id"
        }
        if (notificationsEnabled) withContext(Dispatchers.IO) { notifications.observe(result, token) }
    }
    fun refresh() { if (foreground && loggedIn) action { load() } }
    fun checkReadingResult(recitationId: String) {
        if (!foreground || !loggedIn || dashboard?.role != "child") return
        action {
            checkingReadingResult = true
            try {
                load()
                val recitation = dashboard?.recitations?.firstOrNull { it.id == recitationId }
                when (recitation?.status) {
                    "pending" -> message = "Belum ada keputusan. Bacaanmu masih menunggu diperiksa orang tua."
                    null -> message = "Rekaman ini sudah tidak tersedia."
                    else -> Unit
                }
            } finally {
                checkingReadingResult = false
            }
        }
    }
    private fun refreshQuietly() {
        if (!foreground || !loggedIn || busy || recording || recordedFile != null || refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            try {
                load()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (error is ApiError && error.status == 401) {
                    clearSession()
                    message = error.message
                }
            }
        }
    }
    fun pollUpdates() {
        val data = dashboard ?: return
        if (SystemClock.elapsedRealtime() - lastLoadedAt < 15_000) return
        val readingScreen = screen in listOf("home", "history", "child-reading")
        if (data.role == "parent" && (readingScreen || screen.startsWith("review:")) ||
            data.role == "child" && (readingScreen && (data.readingAssignments || data.recitations.any { it.status == "pending" }) ||
                screen == "books" && data.readingAssignments)) {
            refreshQuietly()
        }
    }
    fun authenticate(username: String, password: String, child: Boolean, register: Boolean) = action {
        val path = if (child) "/auth/child/login" else if (register) "/auth/register" else "/auth/login"
        val auth = api.json.decodeFromString<Auth>(api.request(path, "POST",
            api.body("username" to username.trim(), "password" to password), authenticated = false))
        api.token = auth.token
        store.save(auth.token)
        screen = "home"
        load()
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
        notifications.enable(data.copy(serverTime = serverNow()), api.token)
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
    fun addChild(name: String, username: String, password: String, onCreated: () -> Unit) = action {
        val child = api.json.decodeFromString<UpdatedChild>(api.request("/children/accounts", "POST",
            api.body("name" to name, "username" to username, "password" to password))).child
        dashboard = dashboard?.let { it.copy(children = (it.children + child).sortedBy { profile -> profile.name }) }
        onCreated()
        message = "Akun ${child.name} siap. Masuk di HP anak dengan username dan kata sandinya."
    }
    fun saveChildAccount(child: Child, username: String, password: String, onError: (String) -> Unit, onSaved: () -> Unit) = action(onError) {
        val updated = api.json.decodeFromString<UpdatedChild>(api.request("/children/${child.id}/account", "PUT",
            api.body("username" to username, "password" to password))).child
        dashboard = dashboard?.let { data -> data.copy(children = data.children.map { if (it.id == updated.id) updated else it }) }
        onSaved()
        message = "Login ${updated.name} sudah disimpan."
    }
    fun policy(child: Child, p: Policy) = action {
        api.request("/children/${child.id}/policy", "PUT", api.json.encodeToString(p).toRequestBody("application/json".toMediaType()))
        load()
        message = "Aturan disimpan. Anak perlu merekam bacaan baru."
        if (screen == "settings:${child.id}") screen = "child-profile:${child.id}"
    }
    internal fun saveReadingTask(child: Child, fields: List<Pair<String, String>>, onSaved: () -> Unit) = action {
        val updated = api.json.decodeFromString<UpdatedChild>(api.request("/children/${child.id}/reading-task", "PUT",
            api.body(*fields.toTypedArray()))).child
        dashboard = dashboard?.let { data -> data.copy(children = data.children.map { if (it.id == updated.id) updated else it }) }
        onSaved()
        message = "Tugas bacaan ${child.name} disimpan."
        load()
    }
    internal fun saveReadingTasks(child: Child, tasks: List<ReadingTask>, onSaved: () -> Unit) = action {
        val body = api.json.encodeToString(ReadingTaskPlan(tasks)).toRequestBody("application/json".toMediaType())
        val updated = api.json.decodeFromString<UpdatedChild>(api.request("/children/${child.id}/reading-tasks", "PUT", body)).child
        dashboard = dashboard?.let { data -> data.copy(children = data.children.map { if (it.id == updated.id) updated else it }) }
        onSaved()
        message = "Tugas bacaan ${child.name} disimpan."
        load()
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
        if (data.recitations.none { it.id == id }) refreshQuietly()
    }
    fun deleteRecording(id: String) = action {
        stopPlayback()
        api.request("/recitations/$id", "DELETE")
        load()
        if (screen == "review:$id") screen = "home"
    }
    fun openAccountSettings() {
        accountReturnScreen = screen
        screen = "account"
    }
    fun closeAccountSettings() { screen = accountReturnScreen }
    fun openBooks(returnTo: String = "home") {
        booksReturnScreen = returnTo
        screen = "books"
    }
    fun closeBooks() { screen = booksReturnScreen }
    fun selectSurah(id: String) {
        if (recording || recordedFile != null || busy ||
            dashboard?.children?.firstOrNull()?.readingAssignment != "free" || quran.chapter(id)?.ayahs == null) return
        selectedSurahId = id
        readingBook = false
        screen = "child-reading"
    }
    internal fun selectBook(book: Story) {
        if (recording || recordedFile != null || busy || dashboard?.role != "child" ||
            dashboard?.children?.firstOrNull()?.readingAssignment != "free") return
        if (readingStep(false, false, dashboard?.recitations.orEmpty(), serverNow()) != ReadingStep.Read) {
            message = "Bacaan untuk izin TV masih aktif. Cek statusnya di Beranda."
            return
        }
        selectedBook = book
        readingBook = true
        screen = "child-reading"
    }
    internal fun selectedTask(child: Child) = child.readingTasks.find { it.id == selectedTaskId }
    internal fun openTask(task: ReadingTask) {
        if (busy || recording || recordedFile != null) return
        val data = dashboard ?: return
        if (data.children.firstOrNull()?.readingTasks?.any { it.id == task.id } != true ||
            data.activeTaskRecitations.any { it.taskId == task.id }) return
        selectedTaskId = task.id
        screen = "child-reading"
    }
    internal suspend fun loadGeneratedStories(childId: String) {
        if (loadedStoryOwner == childId) return
        val session = api.token
        val books = api.generatedStories()
        coroutineContext.ensureActive()
        if (session != api.token || dashboard?.children?.firstOrNull()?.id != childId) return
        generatedStories = books.mapNotNull { book -> book.templateId?.let { it to book } }.toMap() + generatedStories
        loadedStoryOwner = childId
    }
    internal suspend fun generateStory(templateId: String) {
        val session = api.token
        val book = api.writeStory(templateId)
        coroutineContext.ensureActive()
        if (session == api.token) generatedStories = generatedStories + (templateId to book)
    }
    fun startRecording() {
        if (busy || recording || recordedFile != null) return
        val data = dashboard ?: return
        if (data.role != "child") return
        val child = data.children.firstOrNull() ?: return
        val assigned = child.readingAssignment != "free"
        val task = selectedTask(child)
        if (child.readingAssignment == "plan" &&
            (task == null || data.activeTaskRecitations.any { it.taskId == task.id })) return
        val useBook = task?.kind == "book" || child.readingAssignment == "book" || (!assigned && data.bookLibrary && readingBook)
        val book = if (useBook && !assigned) selectedBook ?: return else null
        if (useBook && assigned && task?.text == null && child.bookReading == null) return
        val revision = if (useBook || data.readingAssignments) child.tvRevision ?: return else null
        val surahId = task?.surahId ?: if (assigned) child.surahId else selectedSurahId ?: child.surahId
        val chapter = if (useBook) null else quran.chapter(surahId)?.takeIf { it.ayahs != null } ?: return
        val maxSeconds = chapter?.maxRecordingSeconds ?: 180
        try {
            stopPlayback()
            recorder.start(maxSeconds)
            recordedSurahId = chapter?.id
            recordedRevision = revision
            recordedBookId = book?.id
            recordedTask = task?.let { it to child.readingCycle }
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
    fun discardRecording() {
        stopPlayback(); ticker?.cancel(); recording = false; recorder.discard()
        recordedFile = null; recordedSurahId = null; recordedRevision = null; recordedBookId = null; recordedTask = null; seconds = 0
    }
    fun foregrounded() {
        val wasActive = foreground
        foreground = true
        if (!wasActive && SystemClock.elapsedRealtime() - lastLoadedAt >= 15_000) {
            if (dashboard == null) refresh() else refreshQuietly()
        }
    }
    fun backgrounded() { foreground = false; refreshJob?.cancel(); cancelTvPair(); if (recording) { discardRecording(); message = "Rekaman dihentikan saat aplikasi ditutup. Silakan rekam ulang." }; stopPlayback() }
    fun cancelTvPair() { tvRemote.cancelPair(); tvPairCodeNeeded = false }
    internal suspend fun discoverTvs() = tvDiscovery.find()

    fun openTvSetup() {
        if (busy || recording || recordedFile != null) return
        tvReturnScreen = screen
        screen = "tv-setup"
    }

    fun leaveTvSetup() {
        cancelTvPair()
        screen = tvReturnScreen
    }

    internal fun saveTv(settings: TvSettings, connect: Boolean = false) = action {
        cancelTvPair()
        val child = api.json.decodeFromString<UpdatedChild>(api.request("/child/tv", "PUT",
            api.json.encodeToString(settings).toRequestBody("application/json".toMediaType()))).child
        dashboard = dashboard?.copy(children = listOf(child))
        tvPaired = tvRemote.paired(child)
        if (connect && foreground && !tvPaired) connectTv(child)
        else message = "Pilihan TV disimpan."
    }

    fun pairTv(c: Child) = action { connectTv(c) }
    private suspend fun connectTv(c: Child) {
        message = "Nyalakan TV dan setujui JagaBocil pada layar TV jika diminta."
        val paired = try { tvRemote.pair(c) } catch (error: java.io.IOException) { throw IllegalStateException("TV belum bisa dihubungi. Nyalakan TV, pastikan HP dan TV memakai Wi-Fi yang sama, lalu coba lagi.", error) }
        if (!foreground) { cancelTvPair(); return }
        tvPairCodeNeeded = !paired
        if (paired) { tvPaired = true; message = "TV terhubung ke HP ini." }
    }
    fun finishTvPair(code: String) = action {
        try { tvRemote.finishPair(code); tvPaired = true; message = "Android TV terhubung." }
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
        val file = recordedFile ?: return@action
        val result = try {
            api.json.decodeFromString<UploadedRecitation>(
                api.upload(file, recordedSurahId, recordedRevision, recordedBookId, recordedTask?.first?.id, recordedTask?.second),
            ).recitation
        } catch (error: ApiError) {
            if (error.status == 409) {
                discardRecording()
                load()
            }
            throw error
        }
        discardRecording()
        dashboard = dashboard?.let { data ->
            data.copy(
                recitations = listOf(result) + data.recitations.filter { it.id != result.id },
                activeTaskRecitations = if (result.taskId == null) data.activeTaskRecitations
                    else listOf(result) + data.activeTaskRecitations.filter { it.taskId != result.taskId },
            )
        }
        screen = "home"
        message = "Rekaman bacaan terkirim."
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
    fun power(recitationId: String) = action {
        val c = dashboard?.children?.firstOrNull() ?: return@action
        val command = if (c.tvTransport == "wifi") tvRemote.prepare(c) else null
        try {
            check(foreground) { "Permintaan dibatalkan karena aplikasi ditutup." }
            if (command == null) infrared.check(c.tvBrand)
            val result = api.json.decodeFromString<Permit>(api.request("/tv/authorize", "POST",
                api.json.encodeToString(PowerRequest(recitationId, c.tvTransport, c.tvRevision)).toRequestBody("application/json".toMediaType())))
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
