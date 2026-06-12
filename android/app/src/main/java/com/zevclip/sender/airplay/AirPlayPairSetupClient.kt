package com.zevclip.sender.airplay

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale

class AirPlayPairSetupClient(
    private val target: AirPlayTarget,
    private val identity: AirPlayIdentity,
    private val connectTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val socketFactory: () -> Socket = { Socket() }
) : Closeable {
    data class PersistentResult(
        val accessoryId: String?,
        val accessoryPublicKey: ByteArray?
    )

    data class TransientSession(
        val session: AirPlayPairVerifier.Session,
        val transport: AirPlayEncryptedChannel,
        val localRtspHost: String,
        private val owner: AirPlayPairSetupClient
    ) : Closeable {
        override fun close() {
            owner.close()
        }
    }

    sealed class Result {
        data class PersistentSuccess(val result: PersistentResult) : Result()
        data class TransientSuccess(val session: TransientSession) : Result()
        data class Failure(val message: String, val statusCode: Int? = null, val cause: Throwable? = null) : Result()
    }

    private var socket: Socket? = null

    fun pairPinStart(): Boolean {
        return runCatching {
            post("/pair-pin-start", ByteArray(0), extraHeaders = mapOf("X-Apple-HKP" to "3")).isSuccessful()
        }.getOrDefault(false)
    }

    fun pairSetup(
        passcode: String,
        transient: Boolean,
        oneTime: Boolean = false,
        preferredHkpHeader: String? = null
    ): Result {
        val normalizedPasscode = passcode.trim()
        if (normalizedPasscode.isEmpty()) {
            return Result.Failure("AirPlay passcode is empty.")
        }

        return runCatching {
            var hkpHeader = preferredHkpHeader ?: if (transient) "4" else "3"
            var responseM2 = post(
                path = "/pair-setup",
                body = Tlv8.encode(
                    buildList {
                        add(Tlv8.state(STATE_M1))
                        add(Tlv8.method(0))
                        if (transient) add(Tlv8.flags(TRANSIENT_PAIRING_FLAG))
                    }
                ),
                extraHeaders = mapOf("X-Apple-HKP" to hkpHeader)
            )
            if (responseM2.statusCode == 470 && hkpHeader != "3") {
                hkpHeader = "3"
                responseM2 = post(
                    path = "/pair-setup",
                    body = Tlv8.encode(
                        buildList {
                            add(Tlv8.state(STATE_M1))
                            add(Tlv8.method(0))
                            if (transient) add(Tlv8.flags(TRANSIENT_PAIRING_FLAG))
                        }
                    ),
                    extraHeaders = mapOf("X-Apple-HKP" to hkpHeader)
                )
            }
            if (!responseM2.isSuccessful()) {
                return Result.Failure(
                    "pair-setup M2 failed: ${responseM2.statusCode} ${responseM2.reasonPhrase}.",
                    responseM2.statusCode
                )
            }

            val tlvM2 = Tlv8.decode(responseM2.body)
            tlvM2[Tlv8.Type.ERROR]?.let { error ->
                return Result.Failure("pair-setup M2 returned AirPlay error ${error.firstOrNull()?.toInt() ?: -1}.")
            }
            val salt = tlvM2[Tlv8.Type.SALT]
                ?: return Result.Failure("pair-setup M2 did not include salt.")
            val serverPublicKey = tlvM2[Tlv8.Type.PUBLIC_KEY]
                ?: return Result.Failure("pair-setup M2 did not include server public key.")

            val srp = CryptoPrimitives.Srp6Client(
                password = normalizedPasscode.toByteArray(Charsets.US_ASCII)
            )
            val clientProof = srp.process(salt, serverPublicKey)
            val responseM4 = post(
                path = "/pair-setup",
                body = Tlv8.encode(
                    Tlv8.state(STATE_M3),
                    Tlv8.Type.PUBLIC_KEY to srp.publicA,
                    Tlv8.Type.PROOF to clientProof
                ),
                extraHeaders = mapOf("X-Apple-HKP" to hkpHeader)
            )
            if (!responseM4.isSuccessful()) {
                return Result.Failure(
                    "pair-setup M4 failed: ${responseM4.statusCode} ${responseM4.reasonPhrase}.",
                    responseM4.statusCode
                )
            }
            val tlvM4 = Tlv8.decode(responseM4.body)
            tlvM4[Tlv8.Type.ERROR]?.let { error ->
                return Result.Failure("pair-setup M4 returned AirPlay error ${error.firstOrNull()?.toInt() ?: -1}.")
            }
            val serverProof = tlvM4[Tlv8.Type.PROOF]
                ?: return Result.Failure("pair-setup M4 did not include server proof.")
            if (!srp.verifyServerProof(serverProof)) {
                return Result.Failure("pair-setup M4 server proof was not valid.")
            }

            if (transient || oneTime) {
                val sessionKey = srp.sessionKey
                return Result.TransientSuccess(
                    TransientSession(
                        session = AirPlayPairVerifier.Session(
                            target = target,
                            accessoryId = null,
                            accessoryPublicKey = null,
                            sharedSecret = sessionKey,
                            encryptionKey = sessionKey
                        ),
                        transport = encryptedTransport(sessionKey),
                        localRtspHost = localAddressHost(),
                        owner = this
                    )
                )
            }

            completePersistentPairSetup(srp, hkpHeader)
        }.getOrElse { error ->
            close()
            Result.Failure("pair-setup failed: ${error.message ?: "unknown error"}", cause = error)
        }
    }

    private fun completePersistentPairSetup(srp: CryptoPrimitives.Srp6Client, hkpHeader: String): Result {
        val encryptionKey = CryptoPrimitives.hkdfSha512(
            inputKeyMaterial = srp.sessionKey,
            salt = "Pair-Setup-Encrypt-Salt".toByteArray(Charsets.US_ASCII),
            info = "Pair-Setup-Encrypt-Info".toByteArray(Charsets.US_ASCII),
            outputSize = 32
        )
        val controllerIdBytes = identity.pairingId.toByteArray(Charsets.US_ASCII)
        val controllerX = CryptoPrimitives.hkdfSha512(
            inputKeyMaterial = srp.sessionKey,
            salt = "Pair-Setup-Controller-Sign-Salt".toByteArray(Charsets.US_ASCII),
            info = "Pair-Setup-Controller-Sign-Info".toByteArray(Charsets.US_ASCII),
            outputSize = 32
        )
        val controllerSignature = CryptoPrimitives.ed25519Sign(
            identity.privateKey,
            CryptoPrimitives.concat(controllerX, controllerIdBytes, identity.publicKey)
        )
        val encryptedM5 = CryptoPrimitives.chacha20Poly1305Encrypt(
            key = encryptionKey,
            nonce = CryptoPrimitives.nonce12("PS-Msg05"),
            plaintext = Tlv8.encode(
                Tlv8.Type.IDENTIFIER to controllerIdBytes,
                Tlv8.Type.PUBLIC_KEY to identity.publicKey,
                Tlv8.Type.SIGNATURE to controllerSignature
            )
        )
        val responseM6 = post(
            path = "/pair-setup",
            body = Tlv8.encode(
                Tlv8.state(STATE_M5),
                Tlv8.Type.ENCRYPTED_DATA to encryptedM5
            ),
            extraHeaders = mapOf("X-Apple-HKP" to hkpHeader)
        )
        if (!responseM6.isSuccessful()) {
            return Result.Failure(
                "pair-setup M6 failed: ${responseM6.statusCode} ${responseM6.reasonPhrase}.",
                responseM6.statusCode
            )
        }
        val tlvM6 = Tlv8.decode(responseM6.body)
        tlvM6[Tlv8.Type.ERROR]?.let { error ->
            return Result.Failure("pair-setup M6 returned AirPlay error ${error.firstOrNull()?.toInt() ?: -1}.")
        }
        val encryptedM6 = tlvM6[Tlv8.Type.ENCRYPTED_DATA]
            ?: return Result.Failure("pair-setup M6 did not include encrypted accessory identity.")
        val decryptedM6 = CryptoPrimitives.chacha20Poly1305Decrypt(
            key = encryptionKey,
            nonce = CryptoPrimitives.nonce12("PS-Msg06"),
            ciphertextWithTag = encryptedM6
        )
        val accessory = Tlv8.decode(decryptedM6)
        val accessoryId = accessory[Tlv8.Type.IDENTIFIER]?.toString(Charsets.UTF_8)
        val accessoryPublicKey = accessory[Tlv8.Type.PUBLIC_KEY]
            ?: return Result.Failure("pair-setup M6 did not include accessory public key.")
        val accessorySignature = accessory[Tlv8.Type.SIGNATURE]
            ?: return Result.Failure("pair-setup M6 did not include accessory signature.")
        val accessoryX = CryptoPrimitives.hkdfSha512(
            inputKeyMaterial = srp.sessionKey,
            salt = "Pair-Setup-Accessory-Sign-Salt".toByteArray(Charsets.US_ASCII),
            info = "Pair-Setup-Accessory-Sign-Info".toByteArray(Charsets.US_ASCII),
            outputSize = 32
        )
        val signedAccessoryInfo = CryptoPrimitives.concat(
            accessoryX,
            accessory[Tlv8.Type.IDENTIFIER] ?: ByteArray(0),
            accessoryPublicKey
        )
        if (!CryptoPrimitives.ed25519Verify(accessoryPublicKey, signedAccessoryInfo, accessorySignature)) {
            return Result.Failure("pair-setup M6 accessory signature was not valid.")
        }
        return Result.PersistentSuccess(PersistentResult(accessoryId, accessoryPublicKey))
    }

    private fun post(
        path: String,
        body: ByteArray,
        extraHeaders: Map<String, String>
    ): AirPlayRtspClient.Response {
        return runCatching {
            postOnce(path, body, extraHeaders)
        }.getOrElse { firstError ->
            close()
            runCatching {
                postOnce(path, body, extraHeaders)
            }.getOrElse {
                throw firstError
            }
        }
    }

    private fun postOnce(
        path: String,
        body: ByteArray,
        extraHeaders: Map<String, String>
    ): AirPlayRtspClient.Response {
        connect()
        val activeSocket = socket ?: error("Pair setup socket is not connected.")
        val output = activeSocket.getOutputStream()
        val requestText = buildString {
            append("POST ")
            append(path)
            append(" HTTP/1.1\r\n")
            append("Host: ")
            append(target.host)
            append("\r\n")
            append("Content-Type: application/octet-stream\r\n")
            append("Content-Length: ")
            append(body.size)
            append("\r\n")
            append("User-Agent: AirPlay/${AirPlaySenderInfo.SOURCE_VERSION} ZevLink\r\n")
            append("X-Apple-Device-ID: ")
            append(identity.deviceId)
            append("\r\n")
            append("X-Apple-Client-Name: ")
            append(identity.senderName.asciiHeaderValue())
            append("\r\n")
            append("Client-Instance: ")
            append(identity.clientInstanceId())
            append("\r\n")
            extraHeaders.forEach { (name, value) ->
                append(name)
                append(": ")
                append(value)
                append("\r\n")
            }
            append("Connection: keep-alive\r\n")
            append("\r\n")
        }
        output.write(requestText.toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
        return readResponse(activeSocket.getInputStream())
    }

    private fun connect() {
        if (socket?.isConnected == true && socket?.isClosed == false) return
        val nextSocket = socketFactory()
        nextSocket.connect(InetSocketAddress(target.host, target.port), connectTimeoutMs)
        nextSocket.soTimeout = readTimeoutMs
        nextSocket.tcpNoDelay = true
        socket = nextSocket
    }

    private fun localAddressHost(): String {
        return socket?.localAddress?.hostAddress ?: target.host
    }

    private fun encryptedTransport(sessionKey: ByteArray): AirPlayEncryptedChannel {
        val activeSocket = socket ?: error("Pair setup socket is not connected.")
        return AirPlayEncryptedChannel(
            input = activeSocket.getInputStream(),
            output = activeSocket.getOutputStream(),
            keys = AirPlayEncryptedChannel.controllerKeys(sessionKey)
        )
    }

    private fun readResponse(input: InputStream): AirPlayRtspClient.Response {
        val headerBytes = ByteArrayOutputStream()
        var state = 0
        while (state < HEADER_END.size) {
            val nextByte = input.read()
            require(nextByte >= 0) { "Pair setup response ended before headers completed." }
            headerBytes.write(nextByte)
            state = if (nextByte.toByte() == HEADER_END[state]) {
                state + 1
            } else if (nextByte.toByte() == HEADER_END[0]) {
                1
            } else {
                0
            }
        }

        val headerText = headerBytes.toString(Charsets.US_ASCII.name())
        val lines = headerText.split("\r\n")
        val statusMatch = STATUS_LINE_REGEX.matchEntire(lines.firstOrNull().orEmpty())
        require(statusMatch != null) { "Pair setup response status line is invalid." }

        val headers = LinkedHashMap<String, String>()
        lines.drop(1).forEach { line ->
            if (line.isEmpty()) return@forEach
            val separator = line.indexOf(':')
            require(separator > 0) { "Pair setup response header is invalid." }
            headers[line.substring(0, separator).trim().lowercase(Locale.US)] =
                line.substring(separator + 1).trim()
        }

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(contentLength)
        var offset = 0
        while (offset < contentLength) {
            val read = input.read(body, offset, contentLength - offset)
            require(read >= 0) { "Pair setup response body ended early." }
            offset += read
        }
        return AirPlayRtspClient.Response(
            protocol = statusMatch.groupValues[1],
            statusCode = statusMatch.groupValues[2].toInt(),
            reasonPhrase = statusMatch.groupValues[3],
            headers = headers,
            body = body
        )
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun AirPlayIdentity.clientInstanceId(): String {
        return deviceId.filter { it.isLetterOrDigit() }.take(16).uppercase(Locale.US)
    }

    private fun String.asciiHeaderValue(): String {
        return map { char -> if (char.code in 0x20..0x7E) char else '?' }
            .joinToString("")
            .trim()
            .ifBlank { "Android" }
    }

    private companion object {
        const val STATE_M1 = 1
        const val STATE_M3 = 3
        const val STATE_M5 = 5
        const val TRANSIENT_PAIRING_FLAG = 0x10
        const val DEFAULT_TIMEOUT_MS = 5_000
        val HEADER_END = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        val STATUS_LINE_REGEX = Regex("(HTTP/1\\.[01]|RTSP/1\\.0)\\s+(\\d{3})(?:\\s+(.*))?")
    }
}
