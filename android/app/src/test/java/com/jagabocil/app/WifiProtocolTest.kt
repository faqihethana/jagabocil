package com.jagabocil.app

import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.security.interfaces.RSAPublicKey

class WifiProtocolTest {
    @Test fun magicPacketHasSixFFBytesAndSixteenMacRepeats() {
        val packet = WakePacket.encode("34:12:98:AB:CD:EF")
        assertEquals(102, packet.size)
        assertTrue(packet.take(6).all { it == 0xff.toByte() })
        val mac = byteArrayOf(0x34, 0x12, 0x98.toByte(), 0xab.toByte(), 0xcd.toByte(), 0xef.toByte())
        repeat(16) { assertArrayEquals(mac, packet.copyOfRange(6 + it * 6, 12 + it * 6)) }
    }
    @Test fun invalidMacNeverBecomesAWakePacket() {
        listOf("", "00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF", "01:00:5E:00:00:01", "192.168.1.1", "34:12:98:AB:CD:ZZ").forEach { assertTrue(it, runCatching { WakePacket.encode(it) }.isFailure) }
    }
    @Test fun broadcastUsesActualSubnetRatherThanHardcodedSlash24() {
        assertArrayEquals(byteArrayOf(10, 20, -1, -1), WakePacket.broadcast(byteArrayOf(10, 20, 7, 3), 16))
        assertArrayEquals(byteArrayOf(-64, -88, 1, -1), WakePacket.broadcast(byteArrayOf(-64, -88, 1, 3), 24))
        assertTrue(runCatching { WakePacket.broadcast(byteArrayOf(8, 8, 8, 8), 24) }.isFailure)
        assertTrue(runCatching { WakePacket.broadcast(byteArrayOf(10, 0, 0, 1), 31) }.isFailure)
    }
    @Test fun onlyLiteralPrivateAddressesOnTheSameSubnetAreAccepted() {
        listOf("8.8.8.8", "127.0.0.1", "169.254.169.254", "example.com", "192.168.01.2", "192.168.1.300", "192.168.1.2:8002").forEach { assertTrue(it, runCatching { LanAddress.parse(it) }.isFailure) }
        val local = LanAddress.parse("192.168.1.10")
        assertTrue(LanAddress.sameSubnet(local, LanAddress.parse("192.168.1.20"), 24))
        listOf("192.168.2.20", "192.168.1.255", "192.168.1.0", "192.168.1.10").forEach { assertFalse(LanAddress.sameSubnet(local, LanAddress.parse(it), 24)) }
    }
    @Test fun androidWakeKeyMatchesRemoteV2WireContract() {
        assertArrayEquals(byteArrayOf(0x52, 5, 8, 0xe0.toByte(), 1, 0x10, 3), RemoteWire.powerOn())
        val key = RemoteWire.fields(RemoteWire.fields(RemoteWire.powerOn()).getValue(10))
        assertEquals(224, RemoteWire.int(key, 1))
        assertEquals(3, RemoteWire.int(key, 2))
    }
    @Test fun framingRejectsTruncationOversizeAndMalformedInput() {
        listOf(byteArrayOf(5, 1), byteArrayOf(0), byteArrayOf(-1, -1, -1, -1, -1)).forEach {
            assertTrue(runCatching { RemoteWire.readFrame(it.inputStream()) }.isFailure)
        }
        assertTrue(runCatching { RemoteWire.fields(byteArrayOf(0x0a, 0x7f)) }.isFailure)
        val output = java.io.ByteArrayOutputStream()
        RemoteWire.writeFrame(output, RemoteWire.powerOn())
        assertArrayEquals(RemoteWire.powerOn(), RemoteWire.readFrame(output.toByteArray().inputStream()))
    }
    @Test fun pairingSecretBindsUnsignedRsaKeysToTheOnScreenCode() {
        fun publicKey(modulusHex: String) = object : RSAPublicKey {
            override fun getModulus() = BigInteger(modulusHex, 16)
            override fun getPublicExponent() = BigInteger.valueOf(65537)
            override fun getAlgorithm() = "RSA"
            override fun getFormat() = "X.509"
            override fun getEncoded() = byteArrayOf()
        }
        val result = RemoteWire.secret(publicKey("80a1"), publicKey("80b2"), "B100AB")
        assertEquals("b1d80b33be3ef3164c6f5b1acf4ef81c7f0f6f0c1c1a5dcc0263ff4b200d7221", result.joinToString("") { "%02x".format(it) })
        assertTrue(runCatching { RemoteWire.secret(publicKey("80a1"), publicKey("80b2"), "0000AB") }.isFailure)
        assertTrue(runCatching { RemoteWire.secret(publicKey("80a1"), publicKey("80b2"), "ZZZZZZ") }.isFailure)
    }
}
