package com.zevclip.sender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IntentBroadcastRequestRulesTest {
    @Test
    fun acceptsTypedExtrasAndPreservesTheirRuntimeTypes() {
        val request = ParsedIntentBroadcastRequest(
            requestId = "request-1",
            action = "com.zevlink.CUSTOM_COMMAND",
            targetPackage = null,
            dataUri = null,
            mimeType = null,
            categories = emptyList(),
            flags = 0,
            extras = listOf(
                ParsedIntentExtra("command", "string", "set_brightness"),
                ParsedIntentExtra("value", "int", 150),
                ParsedIntentExtra("enabled", "boolean", true),
                ParsedIntentExtra("longValue", "long", 1234567890123L),
                ParsedIntentExtra("floatValue", "float", 1.5f),
                ParsedIntentExtra("doubleValue", "double", 2.25),
                ParsedIntentExtra("labels", "string_array", arrayOf("one", "two"))
            )
        )

        assertNull(IntentBroadcastRequestRules.validate(request))
        assertEquals(150, request.extras.first { it.key == "value" }.value)
        assertEquals(true, request.extras.first { it.key == "enabled" }.value)
    }

    @Test
    fun rejectsMalformedRequestsAndUnsupportedFlags() {
        val malformed = ParsedIntentBroadcastRequest(
            requestId = "request-2",
            action = " ",
            targetPackage = null,
            dataUri = null,
            mimeType = null,
            categories = emptyList(),
            flags = Int.MAX_VALUE,
            extras = listOf(ParsedIntentExtra("value", "int", "150"))
        )

        assertEquals("action is invalid.", IntentBroadcastRequestRules.validate(malformed))
    }
}
