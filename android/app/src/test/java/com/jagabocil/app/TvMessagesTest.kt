package com.jagabocil.app

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TvMessagesTest {
    @Test fun samsungSendsDiscretePowerOnRatherThanToggle() {
        val message = Json.parseToJsonElement(TvMessages.samsungPowerOn()).jsonObject
        assertEquals("ms.remote.control", message.getValue("method").jsonPrimitive.content)
        val params = message.getValue("params").jsonObject
        assertEquals("KEY_POWERON", params.getValue("DataOfCmd").jsonPrimitive.content)
        assertEquals("Click", params.getValue("Cmd").jsonPrimitive.content)
        assertEquals("SendRemoteKey", params.getValue("TypeOfRemote").jsonPrimitive.content)
    }
    @Test fun lgRequestsScreenOnAndChecksPairingThroughRegistration() {
        val command = Json.parseToJsonElement(TvMessages.lgScreenOn()).jsonObject
        assertEquals("request", command.getValue("type").jsonPrimitive.content)
        assertEquals("ssap://com.webos.service.tvpower/power/turnOnScreen", command.getValue("uri").jsonPrimitive.content)
        val fresh = Json.parseToJsonElement(TvMessages.lgRegister(null)).jsonObject.getValue("payload").jsonObject
        assertFalse(fresh.containsKey("client-key"))
        assertEquals("PROMPT", fresh.getValue("pairingType").jsonPrimitive.content)
        val saved = Json.parseToJsonElement(TvMessages.lgRegister("test-local-key")).jsonObject.getValue("payload").jsonObject
        assertEquals("test-local-key", saved.getValue("client-key").jsonPrimitive.content)
        assertEquals(setOf("CONTROL_POWER", "CONTROL_TV_SCREEN"), saved.getValue("manifest").jsonObject.getValue("permissions").jsonArray.map { it.jsonPrimitive.content }.toSet())
    }
}
