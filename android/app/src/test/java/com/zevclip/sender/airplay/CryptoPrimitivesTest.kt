package com.zevclip.sender.airplay

import org.bouncycastle.crypto.InvalidCipherTextException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoPrimitivesTest {
    @Test
    fun x25519SharedSecretMatchesOnBothSides() {
        val alice = CryptoPrimitives.generateX25519KeyPair()
        val bob = CryptoPrimitives.generateX25519KeyPair()

        val aliceSecret = CryptoPrimitives.x25519SharedSecret(alice.privateKey, bob.publicKey)
        val bobSecret = CryptoPrimitives.x25519SharedSecret(bob.privateKey, alice.publicKey)

        assertEquals(32, alice.privateKey.size)
        assertEquals(32, alice.publicKey.size)
        assertEquals(32, aliceSecret.size)
        assertArrayEquals(aliceSecret, bobSecret)
    }

    @Test
    fun ed25519SignsAndVerifiesMessages() {
        val keyPair = CryptoPrimitives.generateEd25519KeyPair()
        val message = "pair-setup proof".toByteArray(Charsets.UTF_8)
        val signature = CryptoPrimitives.ed25519Sign(keyPair.privateKey, message)

        assertEquals(32, keyPair.privateKey.size)
        assertEquals(32, keyPair.publicKey.size)
        assertEquals(64, signature.size)
        assertArrayEquals(keyPair.publicKey, CryptoPrimitives.ed25519PublicKey(keyPair.privateKey))
        assertTrue(CryptoPrimitives.ed25519Verify(keyPair.publicKey, message, signature))

        val tampered = message.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        assertFalse(CryptoPrimitives.ed25519Verify(keyPair.publicKey, tampered, signature))
    }

    @Test
    fun hkdfSha512IsDeterministicAndSeparatesInfo() {
        val input = "shared-secret".toByteArray(Charsets.UTF_8)
        val salt = "Pair-Setup-Encrypt-Salt".toByteArray(Charsets.UTF_8)
        val info = "Pair-Setup-Encrypt-Info".toByteArray(Charsets.UTF_8)

        val first = CryptoPrimitives.hkdfSha512(input, salt, info, 32)
        val second = CryptoPrimitives.hkdfSha512(input, salt, info, 32)
        val otherInfo = CryptoPrimitives.hkdfSha512(input, salt, "other-info".toByteArray(Charsets.UTF_8), 32)

        assertEquals(32, first.size)
        assertArrayEquals(first, second)
        assertFalse(first.contentEquals(otherInfo))
    }

    @Test
    fun chacha20Poly1305RoundTripsWithAad() {
        val key = ByteArray(32) { index -> (index + 1).toByte() }
        val nonce = ByteArray(12) { index -> (index + 11).toByte() }
        val aad = "rtsp-session".toByteArray(Charsets.UTF_8)
        val plaintext = "hello encrypted AirPlay".toByteArray(Charsets.UTF_8)

        val encrypted = CryptoPrimitives.chacha20Poly1305Encrypt(key, nonce, plaintext, aad)
        val decrypted = CryptoPrimitives.chacha20Poly1305Decrypt(key, nonce, encrypted, aad)

        assertEquals(plaintext.size + 16, encrypted.size)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test(expected = InvalidCipherTextException::class)
    fun chacha20Poly1305RejectsTamperedCiphertext() {
        val key = ByteArray(32) { index -> (index + 1).toByte() }
        val nonce = ByteArray(12) { index -> (index + 11).toByte() }
        val plaintext = "do not accept modified bytes".toByteArray(Charsets.UTF_8)
        val encrypted = CryptoPrimitives.chacha20Poly1305Encrypt(key, nonce, plaintext)
        val tampered = encrypted.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 0x01).toByte() }

        CryptoPrimitives.chacha20Poly1305Decrypt(key, nonce, tampered)
    }
}
