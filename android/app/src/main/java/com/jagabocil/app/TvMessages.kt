package com.jagabocil.app

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

object TvMessages {
    fun samsungPowerOn() = buildJsonObject {
        put("method", "ms.remote.control")
        putJsonObject("params") { put("Cmd", "Click"); put("DataOfCmd", "KEY_POWERON"); put("Option", "false"); put("TypeOfRemote", "SendRemoteKey") }
    }.toString()
    fun lgScreenOn() = buildJsonObject {
        put("id", "power"); put("type", "request"); put("uri", "ssap://com.webos.service.tvpower/power/turnOnScreen")
    }.toString()
    fun lgRegister(token: String?) = buildJsonObject {
        put("id", "register"); put("type", "register")
        putJsonObject("payload") {
            put("pairingType", "PROMPT"); put("forcePairing", false)
            putJsonObject("manifest") {
                put("manifestVersion", 1); put("appVersion", "0.2.0")
                put("permissions", buildJsonArray { add("CONTROL_POWER"); add("CONTROL_TV_SCREEN") })
            }
            if (!token.isNullOrEmpty()) put("client-key", token)
        }
    }.toString()
}
