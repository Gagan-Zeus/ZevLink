package com.zevclip.sender.filetransfer

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.zevclip.sender.ZevClipPreferences
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Date
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

data class FileTransferDeviceIdentity(
    val deviceId: String,
    val keyAlias: String,
    val certificate: X509Certificate,
    val certificateSha256: String
)

object FileTransferIdentityStore {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "zevlink-transfer-identity-v1"
    private const val CERTIFICATE_COMMON_NAME = "ZevLink Android Transfer"
    private const val CERTIFICATE_VALID_YEARS = 20L

    fun loadOrCreate(context: Context): FileTransferDeviceIdentity {
        val appContext = context.applicationContext
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            createIdentityKey(appContext)
            keyStore.load(null)
        }

        val certificate = keyStore.getCertificate(KEY_ALIAS) as? X509Certificate
            ?: error("Transfer identity certificate is missing from Android Keystore.")
        return FileTransferDeviceIdentity(
            deviceId = ZevClipPreferences.androidDeviceId(appContext),
            keyAlias = KEY_ALIAS,
            certificate = certificate,
            certificateSha256 = FileTransferCertificates.sha256Hex(certificate)
        )
    }

    fun keyManagers(context: Context): Array<KeyManager> {
        loadOrCreate(context)
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(keyStore, null)
        return keyManagerFactory.keyManagers
    }

    private fun createIdentityKey(context: Context) {
        val now = System.currentTimeMillis()
        val notBefore = Date(now - ONE_HOUR_MS)
        val notAfter = Date(now + CERTIFICATE_VALID_YEARS * 365L * 24L * 60L * 60L * 1000L)
        val serialNumber = BigInteger(128, SecureRandom()).abs().let {
            if (it == BigInteger.ZERO) BigInteger.ONE else it
        }
        val subject = X500Principal("CN=$CERTIFICATE_COMMON_NAME ${ZevClipPreferences.androidDeviceId(context)}")
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(
                KeyProperties.DIGEST_SHA256,
                KeyProperties.DIGEST_SHA384,
                KeyProperties.DIGEST_SHA512
            )
            .setCertificateSubject(subject)
            .setCertificateSerialNumber(serialNumber)
            .setCertificateNotBefore(notBefore)
            .setCertificateNotAfter(notAfter)
            .build()

        KeyPairGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
            .apply { initialize(spec) }
            .generateKeyPair()
    }

    private const val ONE_HOUR_MS = 60L * 60L * 1000L
}

object FileTransferPeerPinStore {
    private const val KEY_MAC_TRANSFER_CERT_SHA256 = "mac_transfer_cert_sha256"

    fun pinnedMacCertificateSha256(context: Context): String? {
        return ZevClipPreferences.preferences(context)
            .getString(KEY_MAC_TRANSFER_CERT_SHA256, null)
            ?.normalizedFingerprint()
    }

    fun savePinnedMacCertificateSha256(context: Context, fingerprint: String?) {
        val normalized = fingerprint.normalizedFingerprint()
        val editor = ZevClipPreferences.preferences(context).edit()
        if (normalized == null) {
            editor.remove(KEY_MAC_TRANSFER_CERT_SHA256)
        } else {
            editor.putString(KEY_MAC_TRANSFER_CERT_SHA256, normalized)
        }
        editor.apply()
    }
}

object FileTransferCertificates {
    fun sha256Hex(certificate: X509Certificate): String {
        return sha256Hex(certificate.encoded)
    }

    fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .toHex()
    }

    fun isValidFingerprint(value: String?): Boolean {
        return value.normalizedFingerprint() != null
    }

    internal fun String?.normalizedFingerprint(): String? {
        val normalized = this
            ?.trim()
            ?.lowercase()
            ?.replace(":", "")
            ?.replace(" ", "")
            ?: return null
        return normalized.takeIf {
            it.length == 64 && it.all { character -> character in '0'..'9' || character in 'a'..'f' }
        }
    }
}

class FileTransferPinnedTrustManager(
    expectedCertificateSha256: String
) : X509TrustManager {
    private val expectedCertificateSha256 = expectedCertificateSha256
        .normalizedFingerprint()
        ?: error("Pinned certificate fingerprint must be lowercase SHA-256 hex.")

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        checkPinned(chain)
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        checkPinned(chain)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private fun checkPinned(chain: Array<out X509Certificate>?) {
        val certificate = chain?.firstOrNull()
            ?: throw java.security.cert.CertificateException("Peer certificate chain is empty.")
        val actualFingerprint = FileTransferCertificates.sha256Hex(certificate)
        if (!MessageDigest.isEqual(
                actualFingerprint.toByteArray(Charsets.US_ASCII),
                expectedCertificateSha256.toByteArray(Charsets.US_ASCII)
            )
        ) {
            throw java.security.cert.CertificateException("Peer certificate fingerprint did not match the pinned ZevLink identity.")
        }
    }
}

object FileTransferTls {
    fun clientContext(context: Context, pinnedPeerCertificateSha256: String): SSLContext {
        return context(
            keyManagers = FileTransferIdentityStore.keyManagers(context),
            trustManagers = arrayOf(FileTransferPinnedTrustManager(pinnedPeerCertificateSha256))
        )
    }

    fun serverContext(context: Context, pinnedPeerCertificateSha256: String): SSLContext {
        return context(
            keyManagers = FileTransferIdentityStore.keyManagers(context),
            trustManagers = arrayOf(FileTransferPinnedTrustManager(pinnedPeerCertificateSha256))
        )
    }

    fun configure(socket: SSLSocket) {
        socket.enabledProtocols = socket.supportedProtocols
            .filter { it == TLS_VERSION }
            .takeIf { it.isNotEmpty() }
            ?.toTypedArray()
            ?: arrayOf(TLS_VERSION)
        socket.useClientMode = socket.useClientMode
    }

    private fun context(
        keyManagers: Array<KeyManager>,
        trustManagers: Array<TrustManager>
    ): SSLContext {
        return SSLContext.getInstance(TLS_VERSION).apply {
            init(keyManagers, trustManagers, SecureRandom())
        }
    }

    private const val TLS_VERSION = "TLSv1.3"
}

data class FileTransferAuthHeaders(
    val protocolVersion: String,
    val deviceId: String,
    val timestamp: String,
    val nonce: String,
    val bodySha256: String,
    val authorization: String
) {
    fun asMap(): Map<String, String> = mapOf(
        "ZevLink-Protocol-Version" to protocolVersion,
        "ZevLink-Device-ID" to deviceId,
        "ZevLink-Transfer-Timestamp" to timestamp,
        "ZevLink-Transfer-Nonce" to nonce,
        "ZevLink-Body-SHA256" to bodySha256,
        "ZevLink-Transfer-Authorization" to authorization
    )
}

object FileTransferAuthenticator {
    private const val AUTH_SCHEME = "hmac-sha256"

    fun headers(
        pairingToken: String,
        deviceId: String,
        method: String,
        path: String,
        body: ByteArray,
        now: Instant = Instant.now(),
        nonce: ByteArray = SecureRandom().generateSeed(16)
    ): FileTransferAuthHeaders {
        require(pairingToken.isNotBlank()) { "Pairing token is required for transfer authentication." }
        require(deviceId.isNotBlank()) { "Device ID is required for transfer authentication." }
        require(method.isNotBlank()) { "HTTP method is required for transfer authentication." }
        require(path.startsWith("/")) { "HTTP path must be absolute." }
        require(nonce.size >= 16) { "Transfer authentication nonce must be at least 128 bits." }

        val timestamp = now.epochSecond.toString()
        val nonceHex = nonce.toHex()
        val bodySha256 = FileTransferCertificates.sha256Hex(body)
        val canonical = canonicalString(
            method = method,
            path = path,
            protocolVersion = ZevLinkTransferProtocol.VERSION.toString(),
            deviceId = deviceId.trim(),
            timestamp = timestamp,
            nonce = nonceHex,
            bodySha256 = bodySha256
        )
        val signature = hmacSha256(pairingToken.trim(), canonical)
        return FileTransferAuthHeaders(
            protocolVersion = ZevLinkTransferProtocol.VERSION.toString(),
            deviceId = deviceId.trim(),
            timestamp = timestamp,
            nonce = nonceHex,
            bodySha256 = bodySha256,
            authorization = "$AUTH_SCHEME=$signature"
        )
    }

    fun verify(
        pairingToken: String,
        method: String,
        path: String,
        headers: Map<String, String>
    ): Boolean {
        val protocolVersion = headers.caseInsensitiveValue("ZevLink-Protocol-Version") ?: return false
        val deviceId = headers.caseInsensitiveValue("ZevLink-Device-ID") ?: return false
        val timestamp = headers.caseInsensitiveValue("ZevLink-Transfer-Timestamp") ?: return false
        val nonce = headers.caseInsensitiveValue("ZevLink-Transfer-Nonce") ?: return false
        val bodySha256 = headers.caseInsensitiveValue("ZevLink-Body-SHA256") ?: return false
        val authorization = headers.caseInsensitiveValue("ZevLink-Transfer-Authorization") ?: return false
        if (protocolVersion != ZevLinkTransferProtocol.VERSION.toString()) {
            return false
        }
        val expected = "$AUTH_SCHEME=" + hmacSha256(
            pairingToken.trim(),
            canonicalString(method, path, protocolVersion, deviceId, timestamp, nonce, bodySha256)
        )
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.US_ASCII),
            authorization.toByteArray(Charsets.US_ASCII)
        )
    }

    private fun canonicalString(
        method: String,
        path: String,
        protocolVersion: String,
        deviceId: String,
        timestamp: String,
        nonce: String,
        bodySha256: String
    ): String {
        return listOf(
            method.uppercase(),
            path,
            protocolVersion,
            deviceId,
            timestamp,
            nonce,
            bodySha256
        ).joinToString("\n")
    }

    private fun hmacSha256(token: String, canonical: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(canonical.toByteArray(Charsets.UTF_8)).toHex()
    }
}

private fun Map<String, String>.caseInsensitiveValue(name: String): String? {
    return entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

private fun ByteArray.toHex(): String {
    return joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private fun SecureRandom.generateSeed(size: Int): ByteArray {
    return ByteArray(size).also(::nextBytes)
}

private fun String?.normalizedFingerprint(): String? {
    return FileTransferCertificates.run { this@normalizedFingerprint.normalizedFingerprint() }
}

