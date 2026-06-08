package com.zevclip.sender.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BPlistTest {
    @Test
    fun roundTripsAirPlaySetupShape() {
        val payload = BPlist.dict(
            "deviceID" to BPlist.string("AA:BB:CC:DD:EE:FF"),
            "sessionUUID" to BPlist.string("11111111-2222-3333-4444-555555555555"),
            "timingPort" to BPlist.int(49152),
            "isMultiSelectAirPlay" to BPlist.bool(false),
            "streams" to BPlist.array(
                BPlist.dict(
                    "type" to BPlist.int(96),
                    "shk" to BPlist.data(byteArrayOf(1, 2, 3, 4)),
                    "audioMode" to BPlist.string("default")
                )
            )
        )

        val encoded = BPlist.encode(payload)
        assertTrue(encoded.copyOfRange(0, 8).decodeToString() == "bplist00")

        val decoded = BPlist.decode(encoded)
        assertEquals(payload, decoded)
    }

    @Test
    fun encodesAndDecodesExtendedCountsAndUtf16Strings() {
        val entries = (0 until 20).associate { index ->
            "key$index" to BPlist.string("value$index")
        }.toMutableMap()
        entries["emoji"] = BPlist.string("ZevClip 音频")

        val payload = BPlist.Value.DictValue(LinkedHashMap(entries))
        val decoded = BPlist.decode(BPlist.encode(payload))

        assertEquals(payload, decoded)
    }

    @Test
    fun dataUsesByteArrayEquality() {
        val bytes = byteArrayOf(0, 1, 2, 3, 127, -1)
        val decoded = BPlist.decode(BPlist.encode(BPlist.data(bytes)))

        val decodedData = decoded as BPlist.Value.DataValue
        assertArrayEquals(bytes, decodedData.value)
        assertEquals(BPlist.data(bytes), decodedData)
    }
}
