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
    data class Target(val network: Network, val local: InetAddress, val broadcast: InetAddress, val mac: String)
    fun endpoint(host: String): Pair<Network, InetAddress> {
        val bytes = LanAddress.parse(host)
        for (network in connectivity.allNetworks) {
            val caps = connectivity.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            for (link in connectivity.getLinkProperties(network)?.linkAddresses.orEmpty()) {
                if (link.address is Inet4Address && LanAddress.sameSubnet(link.address.address, bytes, link.prefixLength)) return network to InetAddress.getByAddress(bytes)
            }
        }
        error("Alamat TV belum berada di subnet Wi-Fi HP ini. Hubungkan ke jaringan rumah yang sama dan periksa IP TV.")
    }
    fun prepare(mac: String): Target {
        WakePacket.macBytes(mac)
        val networks = connectivity.allNetworks.filter { network ->
            val caps = connectivity.getNetworkCapabilities(network)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        for (network in networks) {
            val addresses = connectivity.getLinkProperties(network)?.linkAddresses.orEmpty()
            for (link in addresses) {
                if (link.address !is Inet4Address) continue
                val broadcast = runCatching { WakePacket.broadcast(link.address.address, link.prefixLength) }.getOrNull() ?: continue
                return Target(network, link.address, InetAddress.getByAddress(broadcast), mac)
            }
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
