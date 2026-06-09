package com.zevclip.sender.airplay

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayPairVerifierTest {
    @Test
    fun completesPairVerifyAgainstLocalReceiver() {
        val controllerIdentity = AirPlayIdentity.generate()
        val accessoryIdentity = CryptoPrimitives.generateEd25519KeyPair()

        PairVerifyServer(
            controllerIdentity = controllerIdentity,
            accessoryPrivateKey = accessoryIdentity.privateKey,
            accessoryPublicKey = accessoryIdentity.publicKey,
            accessoryId = "Mac-AirPlay"
        ).use { server ->
            val target = AirPlayTarget("127.0.0.1", server.port, name = "Fake Mac")
            val result = AirPlayPairVerifier(target, controllerIdentity)
                .verify(expectedAccessoryPublicKey = accessoryIdentity.publicKey)

            assertTrue(result is AirPlayPairVerifier.Result.Success)
            val session = (result as AirPlayPairVerifier.Result.Success).session
            assertEquals("Mac-AirPlay", session.accessoryId)
            assertEquals(32, session.sharedSecret.size)
            assertEquals(32, session.encryptionKey.size)
            assertEquals(2, server.awaitRequestCount())
        }
    }

    @Test
    fun keepsVerifiedConnectionAvailableForEncryptedControl() {
        val controllerIdentity = AirPlayIdentity.generate()
        val accessoryIdentity = CryptoPrimitives.generateEd25519KeyPair()

        PairVerifyServer(
            controllerIdentity = controllerIdentity,
            accessoryPrivateKey = accessoryIdentity.privateKey,
            accessoryPublicKey = accessoryIdentity.publicKey,
            accessoryId = "Mac-AirPlay"
        ).use { server ->
            val target = AirPlayTarget("127.0.0.1", server.port, name = "Fake Mac")
            val result = AirPlayPairVerifier(target, controllerIdentity)
                .verifyConnected(expectedAccessoryPublicKey = accessoryIdentity.publicKey)

            assertTrue(result is AirPlayPairVerifier.ConnectedResult.Success)
            val connected = (result as AirPlayPairVerifier.ConnectedResult.Success).connectedSession
            connected.use {
                assertEquals("Mac-AirPlay", it.session.accessoryId)
                assertEquals("127.0.0.1", it.localRtspHost)
                assertEquals(32, it.session.sharedSecret.size)
                assertEquals(2, server.awaitRequestCount())
            }
        }
    }

    private class PairVerifyServer(
        private val controllerIdentity: AirPlayIdentity,
        private val accessoryPrivateKey: ByteArray,
        private val accessoryPublicKey: ByteArray,
        private val accessoryId: String
    ) : AutoCloseable {
        private val serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private var requestCount = 0
        private var failure: Throwable? = null
        private val worker = thread(start = true, name = "pair-verify-test-server") {
            runCatching {
                serverSocket.accept().use { socket ->
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()

                    val requestM1 = readRequest(input)
                    requestCount++
                    check(requestM1.statusLine == "POST /pair-verify RTSP/1.0")
                    val tlvM1 = Tlv8.decode(requestM1.body)
                    val controllerEphemeralPublicKey = requireNotNull(tlvM1[Tlv8.Type.PUBLIC_KEY])
                    val accessoryEphemeral = CryptoPrimitives.generateX25519KeyPair()
                    val sharedSecret = CryptoPrimitives.x25519SharedSecret(
                        accessoryEphemeral.privateKey,
                        controllerEphemeralPublicKey
                    )
                    val encryptionKey = CryptoPrimitives.hkdfSha512(
                        inputKeyMaterial = sharedSecret,
                        salt = "Pair-Verify-Encrypt-Salt".toByteArray(Charsets.UTF_8),
                        info = "Pair-Verify-Encrypt-Info".toByteArray(Charsets.UTF_8),
                        outputSize = 32
                    )
                    val accessoryIdBytes = accessoryId.toByteArray(Charsets.UTF_8)
                    val accessorySignature = CryptoPrimitives.ed25519Sign(
                        accessoryPrivateKey,
                        CryptoPrimitives.concat(
                            accessoryEphemeral.publicKey,
                            accessoryIdBytes,
                            controllerEphemeralPublicKey
                        )
                    )
                    val encryptedM2 = CryptoPrimitives.chacha20Poly1305Encrypt(
                        key = encryptionKey,
                        nonce = CryptoPrimitives.nonce12("PV-Msg02"),
                        plaintext = Tlv8.encode(
                            Tlv8.Type.IDENTIFIER to accessoryIdBytes,
                            Tlv8.Type.SIGNATURE to accessorySignature
                        )
                    )
                    output.write(
                        response(
                            Tlv8.encode(
                                Tlv8.state(2),
                                Tlv8.Type.PUBLIC_KEY to accessoryEphemeral.publicKey,
                                Tlv8.Type.ENCRYPTED_DATA to encryptedM2
                            )
                        )
                    )
                    output.flush()

                    val requestM3 = readRequest(input)
                    requestCount++
                    val tlvM3 = Tlv8.decode(requestM3.body)
                    val encryptedM3 = requireNotNull(tlvM3[Tlv8.Type.ENCRYPTED_DATA])
                    val subTlvM3 = Tlv8.decode(
                        CryptoPrimitives.chacha20Poly1305Decrypt(
                            key = encryptionKey,
                            nonce = CryptoPrimitives.nonce12("PV-Msg03"),
                            ciphertextWithTag = encryptedM3
                        )
                    )
                    val controllerId = requireNotNull(subTlvM3[Tlv8.Type.IDENTIFIER])
                    val controllerSignature = requireNotNull(subTlvM3[Tlv8.Type.SIGNATURE])
                    check(controllerId.toString(Charsets.UTF_8) == controllerIdentity.pairingId)
                    check(
                        CryptoPrimitives.ed25519Verify(
                            controllerIdentity.publicKey,
                            CryptoPrimitives.concat(
                                controllerEphemeralPublicKey,
                                controllerId,
                                accessoryEphemeral.publicKey
                            ),
                            controllerSignature
                        )
                    )
                    check(accessoryPublicKey.size == 32)
                    output.write(response(Tlv8.encode(Tlv8.state(4))))
                    output.flush()
                }
            }.onFailure { failure = it }
        }

        val port: Int
            get() = serverSocket.localPort

        fun awaitRequestCount(): Int {
            worker.join(3_000)
            failure?.let { throw AssertionError("Pair verify server failed.", it) }
            return requestCount
        }

        override fun close() {
            runCatching { serverSocket.close() }
            worker.join(1_000)
        }

        private fun readRequest(input: InputStream): CapturedRequest {
            val headerBytes = ByteArrayOutputStream()
            val delimiter = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
            var state = 0
            while (state < delimiter.size) {
                val next = input.read()
                check(next >= 0) { "Request ended before headers completed." }
                headerBytes.write(next)
                state = if (next.toByte() == delimiter[state]) {
                    state + 1
                } else if (next.toByte() == delimiter[0]) {
                    1
                } else {
                    0
                }
            }

            val lines = headerBytes.toString(Charsets.US_ASCII.name()).split("\r\n")
            val headers = linkedMapOf<String, String>()
            lines.drop(1).forEach { line ->
                if (line.isEmpty()) return@forEach
                val separator = line.indexOf(':')
                headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
            }
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val body = ByteArray(contentLength)
            var offset = 0
            while (offset < contentLength) {
                val read = input.read(body, offset, contentLength - offset)
                check(read >= 0) { "Request body ended early." }
                offset += read
            }
            return CapturedRequest(lines.first(), body)
        }

        private fun response(body: ByteArray): ByteArray {
            return buildString {
                append("RTSP/1.0 200 OK\r\n")
                append("Content-Type: application/pairing+tlv8\r\n")
                append("Content-Length: ${body.size}\r\n")
                append("\r\n")
            }.toByteArray(Charsets.US_ASCII) + body
        }
    }

    private data class CapturedRequest(
        val statusLine: String,
        val body: ByteArray
    )
}
