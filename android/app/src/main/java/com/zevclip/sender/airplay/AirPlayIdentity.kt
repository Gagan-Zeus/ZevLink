package com.zevclip.sender.airplay

import java.util.Locale
import java.util.UUID

data class AirPlayIdentity(
    val pairingId: String,
    val privateKey: ByteArray,
    val publicKey: ByteArray
) {
    init {
        require(pairingId.isNotBlank()) { "AirPlay pairing id must not be blank." }
        require(privateKey.size == ED25519_KEY_SIZE) { "AirPlay Ed25519 private key must be 32 bytes." }
        require(publicKey.size == ED25519_KEY_SIZE) { "AirPlay Ed25519 public key must be 32 bytes." }
    }

    override fun equals(other: Any?): Boolean {
        return other is AirPlayIdentity &&
            pairingId == other.pairingId &&
            privateKey.contentEquals(other.privateKey) &&
            publicKey.contentEquals(other.publicKey)
    }

    override fun hashCode(): Int {
        var result = pairingId.hashCode()
        result = 31 * result + privateKey.contentHashCode()
        result = 31 * result + publicKey.contentHashCode()
        return result
    }

    companion object {
        private const val ED25519_KEY_SIZE = 32

        fun generate(): AirPlayIdentity {
            val keyPair = CryptoPrimitives.generateEd25519KeyPair()
            return AirPlayIdentity(
                pairingId = UUID.randomUUID().toString().lowercase(Locale.US),
                privateKey = keyPair.privateKey,
                publicKey = keyPair.publicKey
            )
        }

        fun fromPrivateKey(pairingId: String, privateKey: ByteArray): AirPlayIdentity {
            return AirPlayIdentity(
                pairingId = pairingId,
                privateKey = privateKey,
                publicKey = CryptoPrimitives.ed25519PublicKey(privateKey)
            )
        }
    }
}
