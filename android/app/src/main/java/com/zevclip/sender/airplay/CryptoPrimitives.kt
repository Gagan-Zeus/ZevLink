package com.zevclip.sender.airplay

import java.security.SecureRandom
import java.math.BigInteger
import java.security.MessageDigest
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

    fun concat(vararg chunks: ByteArray): ByteArray {
        val output = ByteArray(chunks.sumOf { it.size })
        var offset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(output, offset)
            offset += chunk.size
        }
        return output
    }

    fun nonce12(tag: String): ByteArray {
        val tagBytes = tag.toByteArray(Charsets.US_ASCII)
        require(tagBytes.size <= CHACHA20_POLY1305_NONCE_SIZE) {
            "AirPlay nonce tag must be at most 12 ASCII bytes."
        }
        return ByteArray(CHACHA20_POLY1305_NONCE_SIZE).also { nonce ->
            tagBytes.copyInto(nonce, CHACHA20_POLY1305_NONCE_SIZE - tagBytes.size)
        }
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

    class Srp6Client(
        private val username: ByteArray = "Pair-Setup".toByteArray(Charsets.US_ASCII),
        private val password: ByteArray
    ) {
        private val privateA: BigInteger = BigInteger(512, secureRandom).mod(SRP_N)
        private val publicAInteger: BigInteger = SRP_G.modPow(privateA, SRP_N)
        private lateinit var clientProof: ByteArray

        lateinit var sessionKey: ByteArray
            private set

        val publicA: ByteArray
            get() = publicAInteger.toMinimalUnsignedBytes()

        fun process(saltBytes: ByteArray, serverPublicBytes: ByteArray): ByteArray {
            val salt = BigInteger(1, saltBytes)
            val serverPublic = BigInteger(1, serverPublicBytes)
            require(serverPublic.mod(SRP_N) != BigInteger.ZERO) { "SRP server public key is invalid." }

            val multiplier = hashInt(padToSrpSize(SRP_N), padToSrpSize(SRP_G))
            val scramblingParameter = hashInt(padToSrpSize(publicAInteger), padToSrpSize(serverPublic))
            val passwordHash = sha512(concat(username, byteArrayOf(':'.code.toByte()), password))
            val privateKey = hashInt(salt.toMinimalUnsignedBytes(), passwordHash)

            val generatorPower = SRP_G.modPow(privateKey, SRP_N)
            val base = serverPublic.subtract(multiplier.multiply(generatorPower)).mod(SRP_N)
            val exponent = privateA.add(scramblingParameter.multiply(privateKey))
            val sharedSecret = base.modPow(exponent, SRP_N)
            sessionKey = sha512(sharedSecret.toMinimalUnsignedBytes())

            val hashedN = BigInteger(1, sha512(SRP_N.toMinimalUnsignedBytes()))
            val hashedG = BigInteger(1, sha512(SRP_G.toMinimalUnsignedBytes()))
            val hashedXor = hashedN.xor(hashedG).toMinimalUnsignedBytes()
            clientProof = sha512(
                concat(
                    hashedXor,
                    sha512(username),
                    salt.toMinimalUnsignedBytes(),
                    publicAInteger.toMinimalUnsignedBytes(),
                    serverPublic.toMinimalUnsignedBytes(),
                    sessionKey
                )
            )
            return clientProof
        }

        fun verifyServerProof(serverProof: ByteArray): Boolean {
            check(::sessionKey.isInitialized) { "SRP session key is not ready." }
            check(::clientProof.isInitialized) { "SRP client proof is not ready." }
            val expected = sha512(concat(publicAInteger.toMinimalUnsignedBytes(), clientProof, sessionKey))
            return MessageDigest.isEqual(expected, serverProof)
        }

        private fun hashInt(vararg chunks: ByteArray): BigInteger {
            return BigInteger(1, sha512(concat(*chunks)))
        }

        private fun padToSrpSize(value: BigInteger): ByteArray {
            val raw = value.toMinimalUnsignedBytes()
            if (raw.size == SRP_PAD_SIZE) return raw
            return ByteArray(SRP_PAD_SIZE).also { output ->
                raw.copyInto(output, destinationOffset = SRP_PAD_SIZE - raw.size)
            }
        }
    }

    fun sha512(input: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-512").digest(input)
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

    private fun BigInteger.toMinimalUnsignedBytes(): ByteArray {
        if (signum() == 0) return ByteArray(0)
        val bytes = toByteArray()
        return if (bytes.size > 1 && bytes[0] == 0.toByte()) {
            bytes.copyOfRange(1, bytes.size)
        } else {
            bytes
        }
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
    private val SRP_N: BigInteger = BigInteger(
        (
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E08" +
                "8A67CC74020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B" +
                "302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9" +
                "A637ED6B0BFF5CB6F406B7EDEE386BFB5A899FA5AE9F24117C4B1FE6" +
                "49286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8" +
                "FD24CF5F83655D23DCA3AD961C62F356208552BB9ED529077096966D" +
                "670C354E4ABC9804F1746C08CA18217C32905E462E36CE3BE39E772C" +
                "180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF695581718" +
                "3995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D" +
                "04507A33A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7D" +
                "B3970F85A6E1E4C7ABF5AE8CDB0933D71E8C94E04A25619DCEE3D226" +
                "1AD2EE6BF12FFA06D98A0864D87602733EC86A64521F2B18177B200C" +
                "BBE117577A615D6C770988C0BAD946E208E24FA074E5AB3143DB5BFC" +
                "E0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF"
            ),
        16
    )
    private val SRP_G: BigInteger = BigInteger.valueOf(5)
    private val SRP_PAD_SIZE: Int = SRP_N.bitLength() / 8
}
