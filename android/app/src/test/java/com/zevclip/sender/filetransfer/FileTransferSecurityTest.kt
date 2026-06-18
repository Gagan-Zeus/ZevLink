package com.zevclip.sender.filetransfer

import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileTransferSecurityTest {
    @Test
    fun buildsAndVerifiesTransferAuthenticationHeaders() {
        val body = """{"hello":"world"}""".toByteArray(Charsets.UTF_8)
        val headers = FileTransferAuthenticator.headers(
            pairingToken = "paired-secret",
            deviceId = "android-device",
            method = "POST",
            path = "/zevlink/transfer/v1/offers",
            body = body,
            now = Instant.ofEpochSecond(1_800_000_000),
            nonce = ByteArray(16) { it.toByte() }
        )

        assertTrue(
            FileTransferAuthenticator.verify(
                pairingToken = "paired-secret",
                method = "POST",
                path = "/zevlink/transfer/v1/offers",
                headers = headers.asMap()
            )
        )
        assertFalse(
            FileTransferAuthenticator.verify(
                pairingToken = "wrong-secret",
                method = "POST",
                path = "/zevlink/transfer/v1/offers",
                headers = headers.asMap()
            )
        )
        assertFalse(
            FileTransferAuthenticator.verify(
                pairingToken = "paired-secret",
                method = "PUT",
                path = "/zevlink/transfer/v1/offers",
                headers = headers.asMap()
            )
        )
    }

    @Test
    fun validatesTransferCertificateFingerprints() {
        assertTrue(FileTransferCertificates.isValidFingerprint("a".repeat(64)))
        assertTrue(FileTransferCertificates.isValidFingerprint("aa:".repeat(31) + "aa"))
        assertFalse(FileTransferCertificates.isValidFingerprint("g".repeat(64)))
        assertFalse(FileTransferCertificates.isValidFingerprint("a".repeat(63)))
    }
}

