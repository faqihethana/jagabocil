package com.jagabocil.app

import org.junit.Assert.*
import org.junit.Test

class AccessTest {
    private fun rec(status: String = "approved", used: Long? = null, expires: Long? = 2000) = Recitation("id", "child", "112", status, 15.0, 1000, expires, used, "")
    @Test fun permissionRequiresApprovalExpiryAndUnusedGrant() {
        assertTrue(rec().canUse(1500))
        assertFalse(rec(status = "pending").canUse(1500))
        assertFalse(rec(status = "rejected").canUse(1500))
        assertFalse(rec(used = 1400).canUse(1500))
        assertFalse(rec().canUse(2000))
        assertFalse(rec(expires = null).canUse(1500))
    }
    @Test fun tvCodesUseCorrectHeaderAnd32BitPayload() {
        val lg = IrCodes.pattern("lg")
        val samsung = IrCodes.pattern("samsung")
        assertEquals(67, lg.size)
        assertEquals(9000, lg[0])
        assertEquals(4500, samsung[0])
        assertEquals(4500, lg[1])
        assertEquals(560, lg.last())
        assertTrue(lg.all { it > 0 })
        assertTrue(lg.sum() < 2_000_000)
        // LG 0x20DF10EF starts 0010, most significant bit first on the wire.
        assertEquals(listOf(560, 560, 1690, 560), listOf(lg[3], lg[5], lg[7], lg[9]))
    }
    @Test(expected = IllegalStateException::class) fun noImplicitTvProfile() { IrCodes.pattern("none") }
}
