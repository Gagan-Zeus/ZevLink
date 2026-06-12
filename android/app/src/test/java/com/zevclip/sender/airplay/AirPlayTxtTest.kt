package com.zevclip.sender.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayTxtTest {
    @Test
    fun parsesRawTxtRecordsAndBuildsTarget() {
        val txt = AirPlayTxt.parseRawTxt(
            listOf(
                "deviceid=AA:BB:CC:DD:EE:FF".toByteArray(Charsets.UTF_8),
                "am=Mac16,12".toByteArray(Charsets.UTF_8),
                "ft=0x4A7FCFD5,0x38174FDE".toByteArray(Charsets.UTF_8),
                "pw=1".toByteArray(Charsets.UTF_8)
            )
        )

        val target = AirPlayTxt.targetFromTxt(
            host = "2409:40f2::1",
            port = 7000,
            name = "M4",
            txt = txt
        )

        assertEquals("AA:BB:CC:DD:EE:FF", target.deviceId)
        assertEquals("Mac16,12", target.model)
        assertEquals("0x4A7FCFD5,0x38174FDE", target.features)
        assertEquals("M4", target.name)
        assertTrue(target.requiresPassword == true)
    }

    @Test
    fun ignoresMalformedRawTxtRecords() {
        val txt = AirPlayTxt.parseRawTxt(
            listOf(
                "am=Mac16,12".toByteArray(Charsets.UTF_8),
                "bad-record".toByteArray(Charsets.UTF_8)
            )
        )

        assertEquals(1, txt.size)
        assertTrue(txt.containsKey("am"))
        assertFalse(txt.containsKey("bad-record"))
    }
}
