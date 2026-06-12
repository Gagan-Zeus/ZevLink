package com.zevclip.sender.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Tlv8Test {
    @Test
    fun encodesAndDecodesPairingFields() {
        val identifier = "ZevClip".toByteArray(Charsets.UTF_8)
        val publicKey = ByteArray(32) { index -> index.toByte() }
        val encoded = Tlv8.encode(
            Tlv8.method(0),
            Tlv8.state(1),
            Tlv8.Type.IDENTIFIER to identifier,
            Tlv8.Type.PUBLIC_KEY to publicKey,
            Tlv8.flags(0x00000010)
        )

        val decoded = Tlv8.decode(encoded)

        assertArrayEquals(byteArrayOf(0), decoded[Tlv8.Type.METHOD])
        assertArrayEquals(byteArrayOf(1), decoded[Tlv8.Type.STATE])
        assertArrayEquals(identifier, decoded[Tlv8.Type.IDENTIFIER])
        assertArrayEquals(publicKey, decoded[Tlv8.Type.PUBLIC_KEY])
        assertArrayEquals(byteArrayOf(0, 0, 0, 0x10), decoded[Tlv8.Type.FLAGS])
    }

    @Test
    fun chunksValuesLongerThan255Bytes() {
        val proof = ByteArray(700) { index -> (index % 251).toByte() }
        val encoded = Tlv8.encode(Tlv8.Type.PROOF to proof)

        val chunkHeaders = encoded.indices
            .filter { index -> encoded[index].toInt() and 0xFF == Tlv8.Type.PROOF }
        assertTrue(chunkHeaders.size >= 3)

        val decoded = Tlv8.decode(encoded)
        assertArrayEquals(proof, decoded[Tlv8.Type.PROOF])
    }

    @Test
    fun concatenatesRepeatedTypesWhenDecoding() {
        val data = byteArrayOf(
            Tlv8.Type.ENCRYPTED_DATA.toByte(), 3, 1, 2, 3,
            Tlv8.Type.ENCRYPTED_DATA.toByte(), 2, 4, 5
        )

        val decoded = Tlv8.decode(data)

        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), decoded[Tlv8.Type.ENCRYPTED_DATA])
    }

    @Test
    fun preservesEmptyValues() {
        val decoded = Tlv8.decode(Tlv8.encode(Tlv8.Type.SALT to ByteArray(0)))

        assertEquals(0, decoded[Tlv8.Type.SALT]?.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTruncatedRecords() {
        Tlv8.decode(byteArrayOf(Tlv8.Type.PUBLIC_KEY.toByte(), 4, 1, 2))
    }
}
