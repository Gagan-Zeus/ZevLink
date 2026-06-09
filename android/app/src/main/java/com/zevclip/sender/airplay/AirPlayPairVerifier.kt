package com.zevclip.sender.airplay

import java.io.Closeable

class AirPlayPairVerifier(
    private val target: AirPlayTarget,
    private val identity: AirPlayIdentity,
    private val clientFactory: (AirPlayTarget) -> AirPlayRtspClient = { AirPlayRtspClient(it) }
) {
    data class Session(
        val target: AirPlayTarget,
        val accessoryId: String?,
        val accessoryPublicKey: ByteArray?,
        val sharedSecret: ByteArray,
        val encryptionKey: ByteArray
    )

    sealed class Result {
        data class Success(val session: Session) : Result()
        data class Failure(val message: String, val statusCode: Int? = null, val cause: Throwable? = null) : Result()
    }

    data class ConnectedSession(
        val session: Session,
        val client: AirPlayRtspClient,
        val transport: AirPlayEncryptedChannel,
        val localRtspHost: String
    ) : Closeable {
        override fun close() {
            client.close()
        }
    }

    sealed class ConnectedResult {
        data class Success(val connectedSession: ConnectedSession) : ConnectedResult()
        data class Failure(val message: String, val statusCode: Int? = null, val cause: Throwable? = null) : ConnectedResult()
    }

    fun verify(expectedAccessoryPublicKey: ByteArray? = null): Result {
        return runCatching {
            clientFactory(target).use { client ->
                performVerify(client, expectedAccessoryPublicKey)
            }
        }.getOrElse { error ->
            Result.Failure("pair-verify failed: ${error.message ?: "unknown error"}", cause = error)
        }
    }

    fun verifyConnected(expectedAccessoryPublicKey: ByteArray? = null): ConnectedResult {
        val client = clientFactory(target)
        return runCatching {
            when (val result = performVerify(client, expectedAccessoryPublicKey)) {
                is Result.Success -> ConnectedResult.Success(
                    ConnectedSession(
                        session = result.session,
                        client = client,
                        transport = client.encryptedTransport(result.session.sharedSecret),
                        localRtspHost = client.localAddressHost()
                    )
                )
                is Result.Failure -> {
                    client.close()
                    ConnectedResult.Failure(result.message, result.statusCode, result.cause)
                }
            }
        }.getOrElse { error ->
            client.close()
            ConnectedResult.Failure("pair-verify failed: ${error.message ?: "unknown error"}", cause = error)
        }
    }

    private fun performVerify(
        client: AirPlayRtspClient,
        expectedAccessoryPublicKey: ByteArray?
    ): Result {
        val ephemeral = CryptoPrimitives.generateX25519KeyPair()
        val m1 = Tlv8.encode(
            Tlv8.state(STATE_M1),
            Tlv8.Type.PUBLIC_KEY to ephemeral.publicKey
        )
        val responseM2 = client.request(
            method = "POST",
            uri = PAIR_VERIFY_PATH,
            headers = mapOf("X-Apple-HKP" to "3"),
            body = m1,
            contentType = PAIRING_TLV8_CONTENT_TYPE
        )
        if (!responseM2.isSuccessful()) {
            return Result.Failure("pair-verify M2 failed.", responseM2.statusCode)
        }

        val tlvM2 = Tlv8.decode(responseM2.body)
        val accessoryEphemeralPublicKey = tlvM2[Tlv8.Type.PUBLIC_KEY]
            ?: return Result.Failure("pair-verify M2 did not include accessory public key.")
        val encryptedM2 = tlvM2[Tlv8.Type.ENCRYPTED_DATA]
            ?: return Result.Failure("pair-verify M2 did not include encrypted data.")
        val sharedSecret = CryptoPrimitives.x25519SharedSecret(
            ephemeral.privateKey,
            accessoryEphemeralPublicKey
        )
        val encryptionKey = CryptoPrimitives.hkdfSha512(
            inputKeyMaterial = sharedSecret,
            salt = "Pair-Verify-Encrypt-Salt".toByteArray(Charsets.UTF_8),
            info = "Pair-Verify-Encrypt-Info".toByteArray(Charsets.UTF_8),
            outputSize = 32
        )

        val decryptedM2 = CryptoPrimitives.chacha20Poly1305Decrypt(
            key = encryptionKey,
            nonce = CryptoPrimitives.nonce12("PV-Msg02"),
            ciphertextWithTag = encryptedM2
        )
        val subTlvM2 = Tlv8.decode(decryptedM2)
        val accessoryId = subTlvM2[Tlv8.Type.IDENTIFIER]?.toString(Charsets.UTF_8)
        val accessorySignature = subTlvM2[Tlv8.Type.SIGNATURE]
            ?: return Result.Failure("pair-verify M2 did not include accessory signature.")

        if (expectedAccessoryPublicKey != null) {
            val signedAccessoryInfo = CryptoPrimitives.concat(
                accessoryEphemeralPublicKey,
                accessoryId?.toByteArray(Charsets.UTF_8) ?: ByteArray(0),
                ephemeral.publicKey
            )
            if (!CryptoPrimitives.ed25519Verify(expectedAccessoryPublicKey, signedAccessoryInfo, accessorySignature)) {
                return Result.Failure("pair-verify M2 accessory signature was not valid.")
            }
        }

        val controllerIdBytes = identity.pairingId.toByteArray(Charsets.UTF_8)
        val controllerSignature = CryptoPrimitives.ed25519Sign(
            identity.privateKey,
            CryptoPrimitives.concat(ephemeral.publicKey, controllerIdBytes, accessoryEphemeralPublicKey)
        )
        val encryptedM3 = CryptoPrimitives.chacha20Poly1305Encrypt(
            key = encryptionKey,
            nonce = CryptoPrimitives.nonce12("PV-Msg03"),
            plaintext = Tlv8.encode(
                Tlv8.Type.IDENTIFIER to controllerIdBytes,
                Tlv8.Type.SIGNATURE to controllerSignature
            )
        )
        val responseM4 = client.request(
            method = "POST",
            uri = PAIR_VERIFY_PATH,
            headers = mapOf("X-Apple-HKP" to "3"),
            body = Tlv8.encode(
                Tlv8.state(STATE_M3),
                Tlv8.Type.ENCRYPTED_DATA to encryptedM3
            ),
            contentType = PAIRING_TLV8_CONTENT_TYPE
        )
        if (!responseM4.isSuccessful()) {
            return Result.Failure("pair-verify M4 failed.", responseM4.statusCode)
        }
        val tlvM4 = Tlv8.decode(responseM4.body)
        tlvM4[Tlv8.Type.ERROR]?.let { error ->
            return Result.Failure("pair-verify M4 returned error ${error.firstOrNull()?.toInt() ?: -1}.")
        }

        return Result.Success(
            Session(
                target = target,
                accessoryId = accessoryId,
                accessoryPublicKey = expectedAccessoryPublicKey,
                sharedSecret = sharedSecret,
                encryptionKey = encryptionKey
            )
        )
    }

    private companion object {
        const val PAIR_VERIFY_PATH = "/pair-verify"
        const val PAIRING_TLV8_CONTENT_TYPE = "application/pairing+tlv8"
        const val STATE_M1 = 1
        const val STATE_M3 = 3
    }
}
