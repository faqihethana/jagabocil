package com.jagabocil.app

object WakePacket {
    fun macBytes(mac: String): ByteArray {
        require(Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$").matches(mac)) { "Masukkan MAC Wi-Fi TV, contoh 34:12:98:AB:CD:EF." }
        val bytes = mac.split(':').map { it.toInt(16).toByte() }.toByteArray()
        require(bytes[0].toInt() and 1 == 0 && bytes.any { it != 0.toByte() }) { "MAC TV harus alamat perangkat unicast yang valid." }
        return bytes
    }
    fun encode(mac: String): ByteArray {
        val address = macBytes(mac)
        return ByteArray(102) { index -> if (index < 6) 0xff.toByte() else address[(index - 6) % 6] }
    }
    fun broadcast(address: ByteArray, prefix: Int): ByteArray {
        require(address.size == 4 && prefix in 1..30) { "Jaringan Wi-Fi perlu subnet IPv4 yang mendukung broadcast." }
        val first = address[0].toInt() and 255
        val second = address[1].toInt() and 255
        require(first == 10 || first == 192 && second == 168 || first == 172 && second in 16..31) { "Gunakan jaringan Wi-Fi lokal rumah untuk menghubungkan TV." }
        return ByteArray(4) { index ->
            val bits = (prefix - index * 8).coerceIn(0, 8)
            val host = 255 ushr bits
            (address[index].toInt() or host).toByte()
        }
    }
}
