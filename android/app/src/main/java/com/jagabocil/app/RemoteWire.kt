package com.jagabocil.app

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey

/** Bounded protobuf subset used by Android TV Remote Service v2 (varint and nested messages). */
object RemoteWire {
    fun varint(value: Int): ByteArray {
        require(value >= 0)
        var n = value
        return ByteArrayOutputStream().apply {
            do { val b = n and 127; n = n ushr 7; write(b or if (n != 0) 128 else 0) } while (n != 0)
        }.toByteArray()
    }
    fun number(field: Int, value: Int) = varint(field shl 3) + varint(value)
    fun bytes(field: Int, data: ByteArray) = varint((field shl 3) or 2) + varint(data.size) + data
    fun text(field: Int, value: String) = bytes(field, value.toByteArray())
    fun readInt(input: InputStream): Int {
        var n = 0
        for (i in 0..4) {
            val b = input.read(); require(b >= 0) { "Koneksi TV terputus." }
            require(i < 4 || b < 8) { "Frame TV tidak valid." }
            n = n or ((b and 127) shl (7 * i))
            if (b and 128 == 0) return n
        }
        error("Varint TV terlalu panjang.")
    }
    fun fields(message: ByteArray): Map<Int, ByteArray> {
        val input = message.inputStream(); val fields = mutableMapOf<Int, ByteArray>()
        while (input.available() > 0) {
            val tag = readInt(input); require(tag ushr 3 > 0)
            when (tag and 7) {
                0 -> fields[tag ushr 3] = varint(readInt(input))
                2 -> { val size = readInt(input); require(size <= input.available()); val data = ByteArray(size); require(input.read(data) == size || size == 0); fields[tag ushr 3] = data }
                1 -> require(input.skip(8) == 8L)
                5 -> require(input.skip(4) == 4L)
                else -> error("Tipe frame TV tidak didukung.")
            }
        }
        return fields
    }
    fun int(fields: Map<Int, ByteArray>, field: Int) = fields[field]?.let { readInt(it.inputStream()) } ?: 0
    fun readFrame(input: InputStream): ByteArray {
        val length = readInt(input); require(length in 1..65536) { "Ukuran frame TV tidak valid." }
        val data = ByteArray(length); var offset = 0
        while (offset < length) { val size = input.read(data, offset, length - offset); require(size > 0) { "Koneksi TV terputus." }; offset += size }
        return data
    }
    fun writeFrame(output: OutputStream, bytes: ByteArray) { output.write(varint(bytes.size)); output.write(bytes); output.flush() }
    fun pairing(field: Int, content: ByteArray) = number(1, 2) + number(2, 200) + bytes(field, content)
    fun powerOn() = bytes(10, number(1, 224) + number(2, 3)) // KEYCODE_WAKEUP, SHORT
    fun secret(client: RSAPublicKey, server: RSAPublicKey, code: String): ByteArray {
        require(Regex("^[0-9a-fA-F]{6}$").matches(code)) { "Masukkan enam karakter kode dari layar Android TV." }
        fun unsigned(value: java.math.BigInteger) = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        val tail = code.drop(2).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(unsigned(client.modulus) + unsigned(client.publicExponent) + unsigned(server.modulus) + unsigned(server.publicExponent) + tail)
        require(hash[0].toInt() and 255 == code.take(2).toInt(16)) { "Kode pairing tidak cocok dengan TV ini. Coba kode pada layar TV." }
        return hash
    }
}
