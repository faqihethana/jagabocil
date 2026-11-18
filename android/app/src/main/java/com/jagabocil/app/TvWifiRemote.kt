package com.jagabocil.app

import android.content.Context
import android.net.Network
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.math.BigInteger
import java.util.Date
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

interface TvPowerCommand : Closeable { suspend fun send() }

/** Direct LAN clients. Credentials never leave this phone. Pairing never sends a power key. */
class TvWifiRemote(context: Context, private val wifi: WifiTv) {
    private val credentials = TvCredentials(context)
    private var pending: GooglePair? = null
    fun paired(c: Child) = c.tvPlatform == "wol" || credentials.get(c) != null
    fun forget(c: Child) { cancelPair(); credentials.forget(c) }
    fun clear() { cancelPair(); credentials.clear() }
    fun cancelPair() {
        val previous = pending; pending = null
        if (previous != null) kotlin.concurrent.thread(isDaemon = true, name = "tv-pair-close") { runCatching { previous.socket.close() } }
    }

    suspend fun pair(c: Child): Boolean = withContext(Dispatchers.IO) {
        cancelPair()
        val endpoint = wifi.endpoint(c.tvHost)
        when (c.tvPlatform) {
            "androidtv" -> { pending = startGoogle(c, endpoint); false }
            "samsung", "lg" -> {
                val pin = LocalTvTrust(null)
                connectWs(c, endpoint.first, pin, null, true).use { session ->
                    val token = session.second
                    credentials.save(c, JSONObject().put("pin", pin.fingerprint()).put("token", token))
                }
                true
            }
            else -> error("Pilih platform TV yang mendukung pairing.")
        }
    }
    suspend fun finishPair(code: String) = withContext(Dispatchers.IO) {
        val pair = pending ?: error("Sesi pairing habis. Hubungkan TV lagi.")
        try {
            check(SystemClock.elapsedRealtime() < pair.expires) { "Kode pairing kedaluwarsa. Hubungkan TV lagi." }
            val secret = RemoteWire.secret(clientCertificate().publicKey as RSAPublicKey, pair.server.publicKey as RSAPublicKey, code)
            RemoteWire.writeFrame(pair.socket.outputStream, RemoteWire.pairing(40, RemoteWire.bytes(1, secret)))
            val response = RemoteWire.fields(RemoteWire.readFrame(pair.socket.inputStream))
            check(RemoteWire.int(response, 2) == 200 && response.containsKey(41)) { "TV menolak kode pairing. Coba hubungkan ulang." }
            credentials.save(pair.child, JSONObject().put("pin", pair.pin).put("token", ""))
        } finally { cancelPair() }
    }

    suspend fun checkConnection(c: Child) = withContext(Dispatchers.IO) {
        // Open the same paired session used for power and close it without sending the command.
        try {
            prepareConnected(c).use { }
        } catch (error: IOException) {
            throw IllegalStateException("TV belum bisa dihubungi. Pastikan TV menyala dan periksa IP, Wi-Fi, serta pairing.", error)
        }
    }
    suspend fun prepare(c: Child): TvPowerCommand {
        try { return prepareConnected(c) }
        catch (error: Exception) {
            // Wake only on an unreachable service, never after a failed identity or pairing check.
            val causes = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
            val unavailable = causes.any { it is java.net.ConnectException || it is java.net.SocketTimeoutException || it is java.net.NoRouteToHostException }
            val identityFailure = causes.any { it is java.security.cert.CertificateException || it is javax.net.ssl.SSLException }
            if (!unavailable || identityFailure || c.tvPlatform == "wol" || c.tvMac.isBlank() || !paired(c)) {
                if (error is java.io.IOException) throw IllegalStateException("TV belum bisa dihubungi. Periksa Wi-Fi, IP, dan pairing. Untuk TV standby, isi MAC dan aktifkan wake melalui jaringan jika didukung.", error)
                throw error
            }
            val target = wifi.prepare(c.tvMac)
            return object : TvPowerCommand {
                override suspend fun send() = wifi.send(target)
                override fun close() {}
            }
        }
    }
    private suspend fun prepareConnected(c: Child): TvPowerCommand = withContext(Dispatchers.IO) {
        if (c.tvPlatform == "wol") {
            val target = wifi.prepare(c.tvMac)
            return@withContext object : TvPowerCommand {
                override suspend fun send() = wifi.send(target)
                override fun close() {}
            }
        }
        val saved = credentials.get(c) ?: error("Hubungkan TV di HP ini dulu. Nyalakan TV dengan remote fisik untuk pairing.")
        val endpoint = wifi.endpoint(c.tvHost)
        val trust = LocalTvTrust(saved.getString("pin"))
        when (c.tvPlatform) {
            "androidtv" -> {
                val socket = connectTls(endpoint, 6466, trust, true)
                try {
                    var features = 35; var ready = false
                    val deadline = SystemClock.elapsedRealtime() + 15_000
                    for (i in 0 until 32) {
                        check(SystemClock.elapsedRealtime() < deadline) { "Android TV belum siap. Coba lagi." }
                        val response = RemoteWire.fields(RemoteWire.readFrame(socket.inputStream))
                        when {
                            response.containsKey(1) -> {
                                features = RemoteWire.int(RemoteWire.fields(response.getValue(1)), 1) and 35
                                check(features and 2 != 0) { "TV tidak menerima tombol remote." }
                                val device = RemoteWire.number(3, 1) + RemoteWire.text(4, "1") + RemoteWire.text(5, BuildConfig.APPLICATION_ID) + RemoteWire.text(6, BuildConfig.VERSION_NAME)
                                RemoteWire.writeFrame(socket.outputStream, RemoteWire.bytes(1, RemoteWire.number(1, features) + RemoteWire.bytes(2, device)))
                            }
                            response.containsKey(2) -> RemoteWire.writeFrame(socket.outputStream, RemoteWire.bytes(2, RemoteWire.number(1, features)))
                            response.containsKey(8) -> RemoteWire.writeFrame(socket.outputStream, RemoteWire.bytes(9, RemoteWire.number(1, RemoteWire.int(RemoteWire.fields(response.getValue(8)), 1))))
                            response.containsKey(40) -> { ready = true; break }
                            response.containsKey(3) -> error("Android TV menolak koneksi remote.")
                        }
                    }
                    check(ready) { "Android TV belum siap menerima tombol." }
                    object : TvPowerCommand {
                        override suspend fun send() = withContext(Dispatchers.IO) { RemoteWire.writeFrame(socket.outputStream, RemoteWire.powerOn()) }
                        override fun close() = socket.close()
                    }
                } catch (error: Exception) { socket.close(); throw error }
            }
            "samsung", "lg" -> {
                val session = connectWs(c, endpoint.first, trust, saved.optString("token"), false)
                credentials.save(c, JSONObject().put("pin", trust.fingerprint()).put("token", session.second))
                object : TvPowerCommand {
                    override suspend fun send() = withContext(Dispatchers.IO) {
                        session.first.resetReadTimeout()
                        val payload = JSONObject(if (c.tvPlatform == "samsung") TvMessages.samsungPowerOn() else TvMessages.lgScreenOn())
                        session.first.send(payload)
                        if (c.tvPlatform == "lg") {
                            val response = session.first.await { it.optString("id") == "power" }
                            check(response.optString("type") == "response" && response.optJSONObject("payload")?.optBoolean("returnValue", false) == true) { "TV belum menerima permintaan layar menyala." }
                        }
                        session.first.drain()
                    }
                    override fun close() = session.first.close()
                }
            }
            else -> error("Platform TV belum didukung.")
        }
    }

    private data class GooglePair(val child: Child, val socket: SSLSocket, val server: X509Certificate, val pin: String, val expires: Long)
    private fun startGoogle(c: Child, endpoint: Pair<Network, InetAddress>): GooglePair {
        val trust = LocalTvTrust(null)
        val socket = connectTls(endpoint, 6467, trust, true)
        try {
            fun exchange(field: Int, data: ByteArray, expected: Int) {
                RemoteWire.writeFrame(socket.outputStream, RemoteWire.pairing(field, data))
                val response = RemoteWire.fields(RemoteWire.readFrame(socket.inputStream))
                check(RemoteWire.int(response, 2) == 200 && response.containsKey(expected)) { "Android TV menolak langkah pairing. Periksa Android TV Remote Service." }
            }
            val encoding = RemoteWire.number(1, 3) + RemoteWire.number(2, 6)
            exchange(10, RemoteWire.text(1, "atvremote") + RemoteWire.text(2, "JagaBocil"), 11)
            exchange(20, RemoteWire.bytes(1, encoding) + RemoteWire.number(3, 1), 20)
            exchange(30, RemoteWire.bytes(1, encoding) + RemoteWire.number(2, 1), 31)
            return GooglePair(c, socket, socket.session.peerCertificates[0] as X509Certificate, trust.fingerprint(), SystemClock.elapsedRealtime() + 90_000)
        } catch (error: Exception) { socket.close(); throw error }
    }
    private fun clientStore(): KeyStore {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias("jagabocil_google_tv")) {
            KeyPairGenerator.getInstance("RSA", "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder("jagabocil_google_tv", KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                    .setCertificateSubject(X500Principal("CN=JagaBocil"))
                    .setCertificateSerialNumber(BigInteger.ONE).setCertificateNotBefore(Date(System.currentTimeMillis() - 86_400_000))
                    .setCertificateNotAfter(Date(System.currentTimeMillis() + 10L * 365 * 86_400_000)).build())
            }.generateKeyPair()
        }
        return store
    }
    private fun clientCertificate() = clientStore().getCertificate("jagabocil_google_tv") as X509Certificate
    private fun ssl(trust: LocalTvTrust, clientIdentity: Boolean): SSLContext {
        val managers = if (clientIdentity) KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(clientStore(), null) }.keyManagers else null
        return SSLContext.getInstance("TLS").apply { init(managers, arrayOf(trust), null) }
    }
    private fun connectTls(endpoint: Pair<Network, InetAddress>, port: Int, trust: LocalTvTrust, identity: Boolean): SSLSocket {
        val plain = endpoint.first.socketFactory.createSocket()
        try {
            plain.connect(InetSocketAddress(endpoint.second, port), 5000)
            val socket = ssl(trust, identity).socketFactory.createSocket(plain, endpoint.second.hostAddress, port, true) as SSLSocket
            try { socket.soTimeout = 8000; socket.startHandshake(); return socket }
            catch (error: Exception) { socket.close(); throw error }
        } catch (error: Exception) { plain.close(); throw error }
    }
    private fun connectWs(c: Child, network: Network, trust: LocalTvTrust, token: String?, pairing: Boolean): Pair<LocalWs, String> {
        val samsung = c.tvPlatform == "samsung"
        val url = (if (samsung) "https://${c.tvHost}:8002/api/v2/channels/samsung.remote.control" else "https://${c.tvHost}:3001/").toHttpUrl().newBuilder()
        if (samsung) {
            url.addQueryParameter("name", Base64.encodeToString("JagaBocil".toByteArray(), Base64.NO_WRAP))
            if (!token.isNullOrEmpty()) url.addQueryParameter("token", token)
        }
        val client = OkHttpClient.Builder().socketFactory(network.socketFactory).sslSocketFactory(ssl(trust, false).socketFactory, trust)
            .hostnameVerifier { host, _ -> host == c.tvHost }.proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
        val ws = LocalWs(client, Request.Builder().url(url.build()).build(), if (pairing) 45_000 else 8000)
        try {
            if (!samsung) {
                ws.send(JSONObject(TvMessages.lgRegister(token)))
            }
            val response = ws.await { if (samsung) it.optString("event") in listOf("ms.channel.connect", "ms.channel.unauthorized", "ms.channel.timeOut", "ms.error") else it.optString("type") in listOf("registered", "error") }
            val granted = if (samsung) response.optString("event") == "ms.channel.connect" else response.optString("type") == "registered"
            check(granted) { "TV menolak pairing. Setujui JagaBocil pada layar TV, lalu hubungkan ulang." }
            val key = if (samsung) response.optJSONObject("data")?.optString("token").orEmpty() else response.optJSONObject("payload")?.optString("client-key").orEmpty()
            check(key.isNotEmpty() || !token.isNullOrEmpty()) { "TV belum mengirim kunci pairing. Coba hubungkan ulang." }
            return ws to key.ifEmpty { token.orEmpty() }
        } catch (error: Exception) { ws.close(); throw error }
    }
}

/** A TV's self-signed identity is enrolled only after on-screen pairing, then pinned on every connection. */
internal class LocalTvTrust(private val expected: String?) : X509TrustManager {
    @Volatile private var observed: String? = null
    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        if (chain.isEmpty()) throw CertificateException("TV tidak mengirim sertifikat.")
        val pin = MessageDigest.getInstance("SHA-256").digest(chain[0].publicKey.encoded).joinToString("") { "%02x".format(it) }
        val previous = expected ?: observed
        if (previous != null && previous != pin) throw CertificateException("Identitas TV berubah. Hapus pairing lalu hubungkan ulang melalui layar TV.")
        observed = pin
    }
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) { throw CertificateException("Client identity validation is not supported here") }
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    fun fingerprint() = observed ?: error("Identitas TV belum diterima.")
}

private class LocalWs(private val client: OkHttpClient, request: Request, timeout: Long) : Closeable {
    private val messages = ArrayBlockingQueue<Result<JSONObject>>(32)
    private val closed = CountDownLatch(1)
    private var deadline = SystemClock.elapsedRealtime() + timeout
    private val socket = client.newWebSocket(request, object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text.length > 65536 || !messages.offer(runCatching { JSONObject(text) })) {
                webSocket.cancel(); messages.offer(Result.failure(IllegalStateException("Pesan TV terlalu besar.")))
            }
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { messages.offer(Result.failure(IllegalStateException("TV belum dapat dihubungi. Pastikan TV menyala, IP benar, dan satu jaringan Wi-Fi.", t))); closed.countDown() }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { messages.offer(Result.failure(IllegalStateException("Koneksi TV ditutup."))); closed.countDown() }
    })
    fun send(payload: JSONObject) { check(socket.send(payload.toString())) { "Koneksi TV sudah ditutup." } }
    fun resetReadTimeout() { deadline = SystemClock.elapsedRealtime() + 8000 }
    fun await(predicate: (JSONObject) -> Boolean): JSONObject {
        repeat(64) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            check(remaining > 0) { "TV belum menjawab. Setujui permintaan di layar TV lalu coba lagi." }
            val message = messages.poll(remaining, TimeUnit.MILLISECONDS)?.getOrThrow() ?: error("TV belum menjawab. Coba hubungkan ulang.")
            if (predicate(message)) return message
        }
        error("TV mengirim terlalu banyak pesan.")
    }
    fun drain() { socket.close(1000, "done"); closed.await(2, TimeUnit.SECONDS); check(socket.queueSize() == 0L) { "Permintaan power belum berhasil terkirim." } }
    override fun close() { socket.cancel(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
}

private inline fun <R> Pair<LocalWs, String>.use(block: (Pair<LocalWs, String>) -> R): R = try { block(this) } finally { first.close() }
