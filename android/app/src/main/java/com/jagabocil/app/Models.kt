package com.jagabocil.app

import kotlinx.serialization.Serializable

@Serializable data class Auth(val token: String, val role: String, val name: String)
@Serializable data class Surah(val id: String, val name: String, val verses: Int, val arabic: String)
@Serializable data class BookReading(val title: String, val text: String)
@Serializable data class Child(
    val id: String, val name: String, val mode: String, val surahId: String,
    val minSeconds: Int, val unlockMinutes: Int, val tvBrand: String, val aiConsent: Boolean,
    val tvTransport: String = "infrared", val tvMac: String = "",
    val tvPlatform: String = "wol", val tvHost: String = "",
    val username: String? = null, val tvRevision: Int? = null,
    val bookReading: BookReading? = null,
) {
    val awaitsTv get() = tvTransport == "infrared" && tvBrand == "none"
}
@Serializable data class Recitation(
    val id: String, val childId: String, val surahId: String, val status: String,
    val duration: Double, val createdAt: Long, val expiresAt: Long? = null,
    val usedAt: Long? = null, val feedback: String, val reviewer: String? = null,
    val readyAt: Long? = null, val reviewedAt: Long? = null,
    val readingKind: String = "quran", val bookTitle: String? = null, val hasTranscript: Boolean = false,
) {
    fun canUse(now: Long) = status == "approved" && usedAt == null && (expiresAt ?: 0L) > now
}

internal fun Recitation.readingTitle(quran: QuranLibrary) =
    if (readingKind == "book") bookTitle ?: "Bacaan buku" else quran.name(surahId)

@Serializable internal data class ReadingDetails(
    val transcript: String? = null, val referenceText: String? = null,
    val matchScore: Int? = null, val matchThreshold: Int,
)
@Serializable data class Dashboard(
    val role: String, val name: String, val aiAvailable: Boolean, val children: List<Child>,
    val recitations: List<Recitation>, val surahs: List<Surah>, val serverTime: Long,
    val recoveryConfigured: Boolean = false, val juz30Tasks: Boolean = false,
    val billing: Billing? = null,
    val readingActivities: Boolean = false,
)
@Serializable data class UploadedRecitation(val recitation: Recitation)
@Serializable data class Billing(val amount: Int, val days: Int, val expiresAt: Long?, val pending: Boolean)
@Serializable data class BillingResult(val billing: Billing, val checkoutUrl: String? = null)
@Serializable data class RecoveryCode(val code: String)
@Serializable internal data class UpdatedChild(val child: Child)
@Serializable internal data class TvSettings(
    val tvBrand: String,
    val tvTransport: String,
    val tvPlatform: String,
    val tvHost: String,
    val tvMac: String,
)
@Serializable internal data class PowerRequest(val recitationId: String, val tvTransport: String, val tvRevision: Int? = null)
@Serializable data class Health(val ok: Boolean, val aiAvailable: Boolean, val surahs: List<Surah>)
@Serializable data class Policy(
    val mode: String, val surahId: String, val minSeconds: Int, val unlockMinutes: Int,
    val tvBrand: String, val aiConsent: Boolean,
    val tvTransport: String = "infrared", val tvMac: String = "",
    val tvPlatform: String = "wol", val tvHost: String = "",
    val transcriptConsent: Boolean = false, val bookReading: BookReading? = null,
)
@Serializable data class Permit(val authorized: Boolean, val tvBrand: String, val expiresAt: Long,
    val tvTransport: String = "infrared", val tvMac: String = "",
    val tvPlatform: String = "wol", val tvHost: String = "")
