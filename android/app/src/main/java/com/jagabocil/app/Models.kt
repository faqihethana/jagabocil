package com.jagabocil.app

import kotlinx.serialization.Serializable

@Serializable data class Auth(val token: String, val role: String, val name: String)
@Serializable data class Surah(val id: String, val name: String, val verses: Int, val arabic: String)
@Serializable data class Child(
    val id: String, val name: String, val mode: String, val surahId: String,
    val minSeconds: Int, val unlockMinutes: Int, val tvBrand: String, val aiConsent: Boolean,
    val tvTransport: String = "infrared", val tvMac: String = "",
    val tvPlatform: String = "wol", val tvHost: String = "",
)
@Serializable data class Recitation(
    val id: String, val childId: String, val surahId: String, val status: String,
    val duration: Double, val createdAt: Long, val expiresAt: Long? = null,
    val usedAt: Long? = null, val feedback: String, val reviewer: String? = null,
    val readyAt: Long? = null, val reviewedAt: Long? = null,
) {
    fun canUse(now: Long) = status == "approved" && usedAt == null && (expiresAt ?: 0L) > now
}
@Serializable data class Dashboard(
    val role: String, val name: String, val aiAvailable: Boolean, val children: List<Child>,
    val recitations: List<Recitation>, val surahs: List<Surah>, val serverTime: Long,
    val recoveryConfigured: Boolean = false, val juz30Tasks: Boolean = false,
)
@Serializable data class RecoveryCode(val code: String)
@Serializable data class PairCode(val code: String, val expiresAt: Long)
@Serializable data class PairResponse(val pairing: PairCode)
@Serializable data class CreatedChild(val child: Child, val pairing: PairCode)
@Serializable data class Health(val ok: Boolean, val aiAvailable: Boolean, val surahs: List<Surah>)
@Serializable data class Policy(
    val mode: String, val surahId: String, val minSeconds: Int, val unlockMinutes: Int,
    val tvBrand: String, val aiConsent: Boolean,
    val tvTransport: String = "infrared", val tvMac: String = "",
    val tvPlatform: String = "wol", val tvHost: String = "",
)
@Serializable data class Permit(val authorized: Boolean, val tvBrand: String, val expiresAt: Long,
    val tvTransport: String = "infrared", val tvMac: String = "",
    val tvPlatform: String = "wol", val tvHost: String = "")
