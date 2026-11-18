package com.jagabocil.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Wake-on-LAN over the phone's Wi-Fi interface. No claim of TV discovery or delivery acknowledgement. */
class WifiTv(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    internal data class Link(val network: Network, val address: Inet4Address, val prefixLength: Int)
    data class Target(val network: Network, val local: InetAddress, val broadcast: InetAddress, val mac: String)

    private fun links(): Sequence<Link> = connectivity.allNetworks.asSequence().flatMap { network ->
        val caps = connectivity.getNetworkCapabilities(network)
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            emptySequence()
        } else {
            connectivity.getLinkProperties(network)?.linkAddresses.orEmpty().asSequence().mapNotNull { link ->
                (link.address as? Inet4Address)?.let { Link(network, it, link.prefixLength) }
            }
        }
    }

    fun endpoint(host: String): Pair<Network, InetAddress> {
        val bytes = LanAddress.parse(host)
        val link = links().firstOrNull { LanAddress.sameSubnet(it.address.address, bytes, it.prefixLength) }
        if (link != null) return link.network to InetAddress.getByAddress(bytes)
        error("Alamat TV belum berada di subnet Wi-Fi HP ini. Hubungkan ke jaringan rumah yang sama dan periksa IP TV.")
    }

    internal fun discoveryLink(): Link {
        val active = connectivity.activeNetwork
        val homeLinks = links().filter {
            runCatching { WakePacket.broadcast(it.address.address, it.prefixLength) }.isSuccess
        }.toList()
        return homeLinks.firstOrNull { it.network == active } ?: homeLinks.firstOrNull()
            ?: error("Hubungkan HP ke Wi-Fi rumah yang sama dengan TV. Pencarian membutuhkan IPv4 lokal.")
    }

    fun prepare(mac: String): Target {
        WakePacket.macBytes(mac)
        for (link in links()) {
            val broadcast = runCatching { WakePacket.broadcast(link.address.address, link.prefixLength) }.getOrNull() ?: continue
            return Target(link.network, link.address, InetAddress.getByAddress(broadcast), mac)
        }
        error("Hubungkan HP ke Wi-Fi rumah yang sama dengan TV. Jaringan harus mendukung IPv4 lokal dan broadcast.")
    }
    suspend fun send(target: Target) = withContext(Dispatchers.IO) {
        check(prepare(target.mac) == target) { "Koneksi Wi-Fi berubah. Permintaan belum dikirim; minta izin baru." }
        val bytes = WakePacket.encode(target.mac)
        DatagramSocket(null).use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(target.local, 0))
            target.network.bindSocket(socket)
            socket.broadcast = true
            socket.send(DatagramPacket(bytes, bytes.size, target.broadcast, 9))
        }
    }
}
