package com.zevclip.sender.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayIdentityTest {
    @Test
    fun generatesStableSignableIdentityFromPrivateKey() {
        val identity = AirPlayIdentity.generate(
            deviceId = "02:11:22:33:44:55",
            senderName = "Pixel Test"
        )
        val restored = AirPlayIdentity.fromPrivateKey(
            pairingId = identity.pairingId,
            privateKey = identity.privateKey,
            deviceId = identity.deviceId,
            senderName = identity.senderName
        )
        val message = "verify persistent identity".toByteArray(Charsets.UTF_8)
        val signature = CryptoPrimitives.ed25519Sign(restored.privateKey, message)

        assertEquals(identity.pairingId, restored.pairingId)
        assertEquals(identity.deviceId, restored.deviceId)
        assertEquals(identity.senderName, restored.senderName)
        assertArrayEquals(identity.privateKey, restored.privateKey)
        assertArrayEquals(identity.publicKey, restored.publicKey)
        assertTrue(CryptoPrimitives.ed25519Verify(identity.publicKey, message, signature))
    }
}
