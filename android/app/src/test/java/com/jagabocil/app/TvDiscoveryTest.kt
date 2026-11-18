package com.jagabocil.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvDiscoveryTest {
    @Test fun searchUsesOnlyTheSupportedVendorTargets() {
        val expected = setOf(
            "urn:samsung.com:device:RemoteControlReceiver:1",
            "urn:samsung.com:service:MainTVAgent2:1",
            "urn:lge-com:service:webos-second-screen:1",
        )
        assertEquals(expected, SsdpTvDiscovery.searchTargets)
        assertTrue(SsdpTvDiscovery.request(expected.first()).decodeToString().endsWith("ST: ${expected.first()}\r\n\r\n"))
    }

    @Test fun vendorAdvertisementsAreRecognizedWithoutUsingTheirLocation() {
        val samsung = "HTTP/1.1 200 OK\r\nst: urn:samsung.com:device:RemoteControlReceiver:1\r\nLOCATION: http://example.test/device.xml\r\n\r\n"
        val lg = "HTTP/1.1 200 OK\r\nST: urn:lge-com:service:webos-second-screen:1\r\n\r\n"
        assertEquals(DiscoveryPlatform.SAMSUNG, SsdpTvDiscovery.platform(samsung))
        assertEquals(DiscoveryPlatform.LG, SsdpTvDiscovery.platform(lg))
    }

    @Test fun ignoresGenericDevicesAndMalformedAdvertisements() {
        val target = "ST: urn:lge-com:service:webos-second-screen:1"
        listOf(
            "HTTP/1.1 200 OK\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1",
            "HTTP/1.1 404 Not Found\r\n$target",
            "NOTIFY * HTTP/1.1\r\n$target",
            "HTTP/1.1 200 OK\r\n\r\n$target",
            "HTTP/1.1 200 OK\r\n$target\r\n$target",
        ).forEach { assertNull(SsdpTvDiscovery.platform(it)) }
    }
}
