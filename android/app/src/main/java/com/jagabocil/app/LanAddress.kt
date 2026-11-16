package com.jagabocil.app

object LanAddress {
    fun parse(host: String): ByteArray {
        require(Regex("^(0|[1-9][0-9]{0,2})(\\.(0|[1-9][0-9]{0,2})){3}$").matches(host)) { "Masukkan alamat IPv4 lokal TV, contoh 192.168.1.20." }
        val parts = host.split('.').map { it.toInt() }
        require(parts.all { it in 0..255 }) { "Alamat IP TV tidak valid." }
        val bytes = parts.map { it.toByte() }.toByteArray()
        WakePacket.broadcast(bytes, 24) // Require a private home-network address, without DNS resolution.
        return bytes
    }
    fun sameSubnet(local: ByteArray, remote: ByteArray, prefix: Int): Boolean {
        if (local.size != 4 || remote.size != 4 || prefix !in 1..30 || local.contentEquals(remote)) return false
        val broadcast = runCatching { WakePacket.broadcast(local, prefix) }.getOrNull() ?: return false
        if (remote.contentEquals(broadcast)) return false
        var hostBits = false
        for (i in 0..3) {
            val mask = (255 shl (8 - (prefix - i * 8).coerceIn(0, 8))) and 255
            if ((local[i].toInt() and mask) != (remote[i].toInt() and mask)) return false
            if (remote[i].toInt() and (mask xor 255) != 0) hostBits = true
        }
        return hostBits
    }
}
