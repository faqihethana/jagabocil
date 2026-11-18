package com.jagabocil.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal enum class DiscoveryPlatform(val id: String, val label: String) {
    ANDROID("androidtv", "Android TV / Google TV"),
    SAMSUNG("samsung", "Samsung Tizen"),
    LG("lg", "LG webOS"),
}

internal data class DiscoveredTv(val name: String, val host: String, val platform: DiscoveryPlatform)

internal object SsdpTvDiscovery {
    private val platforms = mapOf(
        "urn:samsung.com:device:RemoteControlReceiver:1" to DiscoveryPlatform.SAMSUNG,
        "urn:samsung.com:service:MainTVAgent2:1" to DiscoveryPlatform.SAMSUNG,
        "urn:lge-com:service:webos-second-screen:1" to DiscoveryPlatform.LG,
    )
    private val successLine = Regex("HTTP/1\\.[01] 200(?: .*)?", RegexOption.IGNORE_CASE)
    val searchTargets get() = platforms.keys

    fun request(target: String): ByteArray {
        require(target in searchTargets)
        return (
            "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 2\r\n" +
                "ST: $target\r\n\r\n"
            ).toByteArray(Charsets.US_ASCII)
    }

    fun platform(response: String): DiscoveryPlatform? {
        val lines = response.lineSequence().iterator()
        if (!lines.hasNext() || !successLine.matches(lines.next().trim())) return null
        var target: String? = null
        for (line in lines) {
            if (line.isBlank()) break
            if (!line.substringBefore(':').trim().equals("ST", ignoreCase = true) || ':' !in line) continue
            if (target != null) return null
            target = line.substringAfter(':').trim()
        }
        return platforms.entries.firstOrNull { it.key.equals(target, ignoreCase = true) }?.value
    }
}

internal class TvDiscovery(context: Context, private val wifi: WifiTv) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
    private val callbackExecutor = Executor { it.run() }

    suspend fun find(): List<DiscoveredTv> = coroutineScope {
        val link = wifi.discoveryLink()
        val lock = wifiManager?.createMulticastLock("jagabocil-tv-discovery")
        lock?.setReferenceCounted(false)
        lock?.acquire()
        try {
            val google = async { findAndroidTv(link) }
            val vendors = async { findSamsungAndLg(link) }
            (google.await() + vendors.await())
                .distinctBy { it.host }
                .sortedWith(compareBy({ it.name.lowercase() }, { it.host }))
                .take(12)
        } finally {
            if (lock?.isHeld == true) lock.release()
        }
    }

    private fun WifiTv.Link.localHost(peer: InetAddress?): String? {
        if (peer !is Inet4Address || !LanAddress.sameSubnet(address.address, peer.address, prefixLength)) return null
        val host = peer.hostAddress ?: return null
        return host.takeIf { runCatching { LanAddress.parse(it) }.isSuccess }
    }

    private suspend fun findAndroidTv(link: WifiTv.Link): List<DiscoveredTv> {
        val serviceType = "_androidtvremote2._tcp"
        val announcements = Channel<NsdServiceInfo>(16)
        val found = linkedMapOf<String, DiscoveredTv>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) { announcements.close() }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { announcements.close() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                val type = info.serviceType.trimEnd('.')
                if (type.equals(serviceType, true) || type.equals("$serviceType.local", true)) announcements.trySend(info)
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, link.network, callbackExecutor, listener)
            } else {
                nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            }
            withTimeoutOrNull(5_000) {
                val seenNames = mutableSetOf<String>()
                // Older Android versions allow only one outstanding resolve request.
                for (announcement in announcements) {
                    if (!seenNames.add(announcement.serviceName)) continue
                    val service = resolve(announcement) ?: continue
                    if (service.port != 6466) continue
                    val host = localAddress(link, service) ?: continue
                    val name = service.serviceName.filterNot(Char::isISOControl).trim().take(64)
                    found[host] = DiscoveredTv(name.ifBlank { DiscoveryPlatform.ANDROID.label }, host, DiscoveryPlatform.ANDROID)
                    if (found.size == 12) break
                }
            }
        } catch (_: IllegalArgumentException) {
            // SSDP results remain available when the local DNS-SD service is unavailable.
        } finally {
            announcements.cancel()
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
        return found.values.toList()
    }

    @Suppress("DEPRECATION")
    private fun localAddress(link: WifiTv.Link, service: NsdServiceInfo): String? =
        if (Build.VERSION.SDK_INT >= 34) service.hostAddresses.firstNotNullOfOrNull { link.localHost(it) }
        else link.localHost(service.host)

    @Suppress("DEPRECATION")
    private suspend fun resolve(info: NsdServiceInfo): NsdServiceInfo? = suspendCancellableCoroutine { continuation ->
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                if (continuation.isActive) continuation.resume(null)
            }
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                if (continuation.isActive) continuation.resume(serviceInfo)
            }
        }
        nsd.resolveService(info, listener)
        continuation.invokeOnCancellation {
            if (Build.VERSION.SDK_INT >= 34) runCatching { nsd.stopServiceResolution(listener) }
        }
    }

    private suspend fun findSamsungAndLg(link: WifiTv.Link): List<DiscoveredTv> = withContext(Dispatchers.IO) {
        val found = linkedMapOf<String, DiscoveredTv>()
        try {
            DatagramSocket(null).use { socket ->
                socket.bind(InetSocketAddress(link.address, 0))
                link.network.bindSocket(socket)
                socket.soTimeout = 200
                val destination = InetSocketAddress("239.255.255.250", 1900)
                for (target in SsdpTvDiscovery.searchTargets) {
                    val bytes = SsdpTvDiscovery.request(target)
                    socket.send(DatagramPacket(bytes, bytes.size, destination))
                }
                val deadline = SystemClock.elapsedRealtime() + 3_200
                val buffer = ByteArray(4096)
                while (SystemClock.elapsedRealtime() < deadline && found.size < 12) {
                    currentCoroutineContext().ensureActive()
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    if (packet.length == buffer.size) continue
                    val host = link.localHost(packet.address) ?: continue
                    val platform = SsdpTvDiscovery.platform(String(buffer, packet.offset, packet.length, Charsets.US_ASCII)) ?: continue
                    // Use the sender address. SSDP LOCATION is untrusted and is never requested.
                    found[host] = DiscoveredTv(platform.label, host, platform)
                }
            }
        } catch (_: IOException) {
            // Android TV results remain available if the network blocks SSDP.
        }
        found.values.toList()
    }
}
