package com.jagabocil.app

import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import org.junit.Assert.*
import org.junit.Test

class LocalTvTrustTest {
    private fun cert(name: String) = javaClass.getResourceAsStream("/$name.pem")!!.use {
        CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
    }
    @Test fun enrollmentPinsIdentityAndRejectsSubstitutionBeforeAndAfterSave() {
        val a = cert("tv-a"); val b = cert("tv-b")
        val enrollment = LocalTvTrust(null)
        enrollment.checkServerTrusted(arrayOf(a), "RSA")
        val fingerprint = enrollment.fingerprint()
        assertTrue(fingerprint.matches(Regex("[a-f0-9]{64}")))
        assertTrue(runCatching { enrollment.checkServerTrusted(arrayOf(b), "RSA") }.isFailure)
        val reconnect = LocalTvTrust(fingerprint)
        reconnect.checkServerTrusted(arrayOf(a), "RSA")
        assertTrue(runCatching { reconnect.checkServerTrusted(arrayOf(b), "RSA") }.isFailure)
    }
    @Test fun missingCertificateIsNeverAnAcceptedTv() {
        assertTrue(runCatching { LocalTvTrust(null).checkServerTrusted(emptyArray(), "RSA") }.isFailure)
        assertTrue(runCatching { LocalTvTrust(null).fingerprint() }.isFailure)
    }
}
