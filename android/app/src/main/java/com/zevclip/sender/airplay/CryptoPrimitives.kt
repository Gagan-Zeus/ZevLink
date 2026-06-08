package com.zevclip.sender.airplay

import java.security.SecureRandom
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.math.ec.rfc8032.Ed25519

object CryptoPrimitives {
    private val secureRandom = SecureRandom()

    data class X25519KeyPair(
        val privateKey: ByteArray,
        val publicKey: ByteArray
    )

    data class Ed25519KeyPair(
        val privateKey: ByteArray,
        val publicKey: ByteArray
    )

    fun randomBytes(size: Int): ByteArray {
        require(size >= 0) { "Random byte size must be non-negative." }
        return ByteArray(size).also(secureRandom::nextBytes)
    }

    fun generateX25519KeyPair(): X25519KeyPair {
        val privateKey = X25519PrivateKeyParameters(secureRandom)
        return X25519KeyPair(
            privateKey = privateKey.getEncoded(),
            publicKey = privateKey.generatePublicKey().encoded()
        )
    }

    fun x25519SharedSecret(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        require(privateKey.size == X25519_PRIVATE_KEY_SIZE) { "X25519 private key must be 32 bytes." }
        require(peerPublicKey.size == X25519_PUBLIC_KEY_SIZE) { "X25519 public key must be 32 bytes." }

        val localPrivateKey = X25519PrivateKeyParameters(privateKey, 0)
        val remotePublicKey = X25519PublicKeyParameters(peerPublicKey, 0)
        return ByteArray(X25519_SECRET_SIZE).also { secret ->
            localPrivateKey.generateSecret(remotePublicKey, secret, 0)
        }
    }

    fun generateEd25519KeyPair(): Ed25519KeyPair {
        val privateKey = Ed25519PrivateKeyParameters(secureRandom)
        return Ed25519KeyPair(
            privateKey = privateKey.getEncoded(),
            publicKey = privateKey.generatePublicKey().encoded()
        )
    }

    fun ed25519PublicKey(privateKey: ByteArray): ByteArray {
        require(privateKey.size == ED25519_PRIVATE_KEY_SIZE) { "Ed25519 private key must be 32 bytes." }
        return Ed25519PrivateKeyParameters(privateKey, 0).generatePublicKey().encoded()
    }

    fun ed25519Sign(privateKey: ByteArray, message: ByteArray): ByteArray {
        require(privateKey.size == ED25519_PRIVATE_KEY_SIZE) { "Ed25519 private key must be 32 bytes." }
        val signature = ByteArray(ED25519_SIGNATURE_SIZE)
        Ed25519PrivateKeyParameters(privateKey, 0).sign(
            Ed25519.Algorithm.Ed25519,
            null,
            message,
            0,
            message.size,
            signature,
            0
        )
        return signature
    }

    fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        require(publicKey.size == ED25519_PUBLIC_KEY_SIZE) { "Ed25519 public key must be 32 bytes." }
        require(signature.size == ED25519_SIGNATURE_SIZE) { "Ed25519 signature must be 64 bytes." }
        return Ed25519PublicKeyParameters(publicKey, 0).verify(
            Ed25519.Algorithm.Ed25519,
            null,
            message,
            0,
            message.size,
            signature,
            0
        )
    }

    fun hkdfSha512(
        inputKeyMaterial: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        outputSize: Int
    ): ByteArray {
        require(outputSize > 0) { "HKDF output size must be positive." }
        val generator = HKDFBytesGenerator(SHA512Digest())
        generator.init(HKDFParameters(inputKeyMaterial, salt, info))
        return ByteArray(outputSize).also { output ->
            generator.generateBytes(output, 0, output.size)
        }
    }

    fun chacha20Poly1305Encrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray = ByteArray(0)
    ): ByteArray {
        return runChaCha20Poly1305(true, key, nonce, plaintext, aad)
    }

    fun chacha20Poly1305Decrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertextWithTag: ByteArray,
        aad: ByteArray = ByteArray(0)
    ): ByteArray {
        return runChaCha20Poly1305(false, key, nonce, ciphertextWithTag, aad)
    }

    private fun runChaCha20Poly1305(
        forEncryption: Boolean,
        key: ByteArray,
        nonce: ByteArray,
        input: ByteArray,
        aad: ByteArray
    ): ByteArray {
        require(key.size == CHACHA20_POLY1305_KEY_SIZE) { "ChaCha20-Poly1305 key must be 32 bytes." }
        require(nonce.size == CHACHA20_POLY1305_NONCE_SIZE) { "ChaCha20-Poly1305 nonce must be 12 bytes." }

        val cipher = ChaCha20Poly1305()
        cipher.init(forEncryption, AEADParameters(KeyParameter(key), CHACHA20_POLY1305_MAC_SIZE_BITS, nonce, aad))
        val output = ByteArray(cipher.getOutputSize(input.size))
        val processed = cipher.processBytes(input, 0, input.size, output, 0)
        val finalized = cipher.doFinal(output, processed)
        return output.copyOf(processed + finalized)
    }

    private fun X25519PublicKeyParameters.encoded(): ByteArray {
        return ByteArray(X25519_PUBLIC_KEY_SIZE).also { encode(it, 0) }
    }

    private fun Ed25519PublicKeyParameters.encoded(): ByteArray {
        return ByteArray(ED25519_PUBLIC_KEY_SIZE).also { encode(it, 0) }
    }

    private const val X25519_PRIVATE_KEY_SIZE = 32
    private const val X25519_PUBLIC_KEY_SIZE = 32
    private const val X25519_SECRET_SIZE = 32
    private const val ED25519_PRIVATE_KEY_SIZE = 32
    private const val ED25519_PUBLIC_KEY_SIZE = 32
    private const val ED25519_SIGNATURE_SIZE = 64
    private const val CHACHA20_POLY1305_KEY_SIZE = 32
    private const val CHACHA20_POLY1305_NONCE_SIZE = 12
    private const val CHACHA20_POLY1305_MAC_SIZE_BITS = 128
}
