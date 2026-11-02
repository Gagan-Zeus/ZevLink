package com.zevclip.sender

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class IntentBroadcastResult(
    val requestId: String,
    val status: String,
    val success: Boolean,
    val message: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("type", "intent_broadcast_ack")
        .put("version", 1)
        .put("requestId", requestId)
        .put("status", status)
        .put("success", success)
        .put("message", message)
}

internal data class ParsedIntentBroadcastRequest(
    val requestId: String,
    val action: String,
    val targetPackage: String?,
    val dataUri: String?,
    val mimeType: String?,
    val categories: List<String>,
    val flags: Int,
    val extras: List<ParsedIntentExtra>
)

internal data class ParsedIntentExtra(
    val key: String,
    val type: String,
    val value: Any
)

class IntentBroadcastHandler(private val context: Context) {
    fun handle(body: ByteArray): IntentBroadcastResult {
        val parsed = try {
            parseRequest(body)
        } catch (error: IntentBroadcastValidationException) {
            return IntentBroadcastResult(
                requestId = error.requestId,
                status = "invalid_request",
                success = false,
                message = error.message ?: "Invalid Intent broadcast request."
            )
        } catch (_: Exception) {
            return IntentBroadcastResult(
                requestId = "",
                status = "invalid_request",
                success = false,
                message = "Request body must be valid Intent broadcast JSON."
            )
        }

        return try {
            val intent = buildIntent(parsed)
            context.sendBroadcast(intent)
            IntentBroadcastResult(
                requestId = parsed.requestId,
                status = "broadcast_requested",
                success = true,
                message = "Android invoked sendBroadcast() for ${parsed.action}."
            )
        } catch (error: SecurityException) {
            Log.w(TAG, "Intent broadcast was blocked by Android security policy", error)
            IntentBroadcastResult(
                requestId = parsed.requestId,
                status = "broadcast_failed",
                success = false,
                message = "Android blocked the broadcast: ${error.message ?: "security policy denied it"}."
            )
        } catch (error: Exception) {
            Log.w(TAG, "Intent broadcast failed", error)
            IntentBroadcastResult(
                requestId = parsed.requestId,
                status = "broadcast_failed",
                success = false,
                message = "Android could not broadcast the Intent: ${error.message ?: "unknown error"}."
            )
        }
    }

    internal fun buildIntent(request: ParsedIntentBroadcastRequest): Intent {
        val intent = Intent(request.action)
        request.targetPackage?.let { intent.setPackage(it) }
        when {
            request.dataUri != null && request.mimeType != null ->
                intent.setDataAndType(Uri.parse(request.dataUri), request.mimeType)
            request.dataUri != null -> intent.data = Uri.parse(request.dataUri)
            request.mimeType != null -> intent.type = request.mimeType
        }
        request.categories.forEach(intent::addCategory)
        if (request.flags != 0) {
            intent.addFlags(request.flags)
        }
        request.extras.forEach { extra ->
            when (extra.type) {
                "string" -> intent.putExtra(extra.key, extra.value as String)
                "boolean" -> intent.putExtra(extra.key, extra.value as Boolean)
                "int" -> intent.putExtra(extra.key, extra.value as Int)
                "long" -> intent.putExtra(extra.key, extra.value as Long)
                "float" -> intent.putExtra(extra.key, extra.value as Float)
                "double" -> intent.putExtra(extra.key, extra.value as Double)
                "string_array" -> intent.putExtra(
                    extra.key,
                    (extra.value as Array<*>).filterIsInstance<String>().toTypedArray()
                )
                else -> error("Unsupported extra type ${extra.type}")
            }
        }
        return intent
    }

    internal fun parseRequest(body: ByteArray): ParsedIntentBroadcastRequest {
        if (body.size > MAX_BODY_BYTES) {
            throw IntentBroadcastValidationException("", "Intent request is too large.")
        }
        val json = try {
            JSONObject(String(body, Charsets.UTF_8))
        } catch (_: Exception) {
            throw IntentBroadcastValidationException("", "Request body must be valid Intent broadcast JSON.")
        }

        val requestId = requiredString(json, "requestId", MAX_REQUEST_ID_LENGTH)
        val type = requiredString(json, "type", 64)
        if (type != "intent_broadcast") {
            throw IntentBroadcastValidationException(requestId, "type must be intent_broadcast.")
        }
        if (json.optInt("version", -1) != 1) {
            throw IntentBroadcastValidationException(requestId, "Unsupported Intent broadcast protocol version.")
        }

        val action = requiredString(json, "action", MAX_ACTION_LENGTH)
        if (action != action.trim() || action.any { it.isISOControl() }) {
            throw IntentBroadcastValidationException(requestId, "action must be an exact non-control string.")
        }

        val targetPackage = optionalString(json, "targetPackage", MAX_PACKAGE_LENGTH)
        if (targetPackage != null && !PACKAGE_PATTERN.matches(targetPackage)) {
            throw IntentBroadcastValidationException(requestId, "targetPackage is not a valid Android package name.")
        }
        val dataUri = optionalString(json, "dataUri", MAX_URI_LENGTH)
        val mimeType = optionalString(json, "mimeType", MAX_MIME_LENGTH)
        if (mimeType != null && !MIME_PATTERN.matches(mimeType)) {
            throw IntentBroadcastValidationException(requestId, "mimeType must look like type/subtype.")
        }

        val categories = parseCategories(json.optJSONArray("categories"), requestId)
        val flagsLong = if (json.isNull("flags") || !json.has("flags")) 0L else json.optLong("flags", Long.MIN_VALUE)
        if (flagsLong < 0 || flagsLong > Int.MAX_VALUE) {
            throw IntentBroadcastValidationException(requestId, "flags must fit in a non-negative Android Int.")
        }
        val flags = flagsLong.toInt()
        if (flags and SAFE_FLAGS.inv() != 0) {
            throw IntentBroadcastValidationException(requestId, "Only safe broadcast flags are supported.")
        }

        val extrasObject = json.optJSONObject("extras")
            ?: throw IntentBroadcastValidationException(requestId, "extras must be a JSON object.")
        if (extrasObject.length() > MAX_EXTRAS) {
            throw IntentBroadcastValidationException(requestId, "An Intent may contain at most $MAX_EXTRAS extras.")
        }

        val extras = mutableListOf<ParsedIntentExtra>()
        val keys = extrasObject.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            validateKey(key, requestId)
            val extraObject = extrasObject.optJSONObject(key)
                ?: throw IntentBroadcastValidationException(requestId, "Extra $key must be an object.")
            val extraType = requiredString(extraObject, "type", MAX_TYPE_LENGTH).lowercase(Locale.US)
            val rawValue = if (extraObject.has("value")) extraObject.get("value") else JSONObject.NULL
            extras += parseExtra(key, extraType, rawValue, requestId)
        }

        val parsedRequest = ParsedIntentBroadcastRequest(
            requestId = requestId,
            action = action,
            targetPackage = targetPackage,
            dataUri = dataUri,
            mimeType = mimeType,
            categories = categories,
            flags = flags,
            extras = extras
        )
        IntentBroadcastRequestRules.validate(parsedRequest)?.let { message ->
            throw IntentBroadcastValidationException(requestId, message)
        }
        return parsedRequest
    }

    private fun parseCategories(array: JSONArray?, requestId: String): List<String> {
        if (array == null) return emptyList()
        if (array.length() > MAX_CATEGORIES) {
            throw IntentBroadcastValidationException(requestId, "An Intent may contain at most $MAX_CATEGORIES categories.")
        }
        val result = mutableListOf<String>()
        for (index in 0 until array.length()) {
            val value = array.opt(index)
            if (value !is String || value.isBlank() || value.length > MAX_CATEGORY_LENGTH || value.any { it.isISOControl() }) {
                throw IntentBroadcastValidationException(requestId, "Every category must be a non-control string.")
            }
            if (result.contains(value)) {
                throw IntentBroadcastValidationException(requestId, "Categories must be unique.")
            }
            result += value
        }
        return result
    }

    private fun parseExtra(key: String, type: String, value: Any, requestId: String): ParsedIntentExtra {
        return when (type) {
            "string" -> ParsedIntentExtra(key, type, requireStringValue(key, value, requestId))
            "boolean" -> if (value is Boolean) ParsedIntentExtra(key, type, value) else invalidValue(key, requestId)
            "int" -> ParsedIntentExtra(key, type, requireIntegral(key, value, Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong(), requestId).toInt())
            "long" -> ParsedIntentExtra(key, type, requireIntegral(key, value, Long.MIN_VALUE, Long.MAX_VALUE, requestId))
            "float" -> ParsedIntentExtra(key, type, requireFloating(key, value, requestId).toFloat())
            "double" -> ParsedIntentExtra(key, type, requireFloating(key, value, requestId))
            "string_array" -> {
                if (value !is JSONArray || value.length() > MAX_ARRAY_VALUES) invalidValue(key, requestId)
                val values = Array(value.length()) { index ->
                    val item = value.opt(index)
                    if (item !is String || item.length > MAX_STRING_LENGTH || item.any { it.isISOControl() }) {
                        invalidValue(key, requestId)
                    }
                    item
                }
                ParsedIntentExtra(key, type, values)
            }
            else -> throw IntentBroadcastValidationException(requestId, "Unsupported extra type for $key.")
        }
    }

    private fun requireStringValue(key: String, value: Any, requestId: String): String {
        if (value !is String || value.length > MAX_STRING_LENGTH || value.any { it.isISOControl() }) {
            invalidValue(key, requestId)
        }
        return value
    }

    private fun requireIntegral(key: String, value: Any, minimum: Long, maximum: Long, requestId: String): Long {
        if (value !is Number || value is Float || value is Double) invalidValue(key, requestId)
        val parsed = value.toLong()
        if (parsed < minimum || parsed > maximum || value.toString() != parsed.toString()) invalidValue(key, requestId)
        return parsed
    }

    private fun requireFloating(key: String, value: Any, requestId: String): Double {
        if (value !is Number) invalidValue(key, requestId)
        val parsed = value.toDouble()
        if (!parsed.isFinite()) invalidValue(key, requestId)
        return parsed
    }

    private fun requiredString(json: JSONObject, name: String, maxLength: Int): String {
        val value = json.opt(name)
        if (value !is String || value.isEmpty() || value.length > maxLength) {
            throw IntentBroadcastValidationException("", "$name is required.")
        }
        return value
    }

    private fun optionalString(json: JSONObject, name: String, maxLength: Int): String? {
        if (!json.has(name) || json.isNull(name)) return null
        val value = json.opt(name)
        if (value !is String || value.isEmpty() || value.length > maxLength) {
            throw IntentBroadcastValidationException("", "$name must be a non-empty string.")
        }
        return value
    }

    private fun validateKey(key: String, requestId: String) {
        if (key.isEmpty() || key.length > MAX_KEY_LENGTH || key.any { it.isISOControl() }) {
            throw IntentBroadcastValidationException(requestId, "Extra keys must be non-empty, bounded strings.")
        }
    }

    private fun invalidValue(key: String, requestId: String): Nothing {
        throw IntentBroadcastValidationException(requestId, "Extra $key has an invalid value for its declared type.")
    }

    private class IntentBroadcastValidationException(
        val requestId: String,
        message: String
    ) : Exception(message)

    companion object {
        private const val TAG = "ZevClipIntentRelay"
        private const val MAX_BODY_BYTES = 256 * 1024
        private const val MAX_REQUEST_ID_LENGTH = 128
        private const val MAX_ACTION_LENGTH = 256
        private const val MAX_PACKAGE_LENGTH = 255
        private const val MAX_URI_LENGTH = 4_096
        private const val MAX_MIME_LENGTH = 255
        private const val MAX_CATEGORY_LENGTH = 128
        private const val MAX_TYPE_LENGTH = 32
        private const val MAX_KEY_LENGTH = 128
        private const val MAX_STRING_LENGTH = 8_192
        private const val MAX_ARRAY_VALUES = 128
        private const val MAX_CATEGORIES = 32
        private const val MAX_EXTRAS = 64
        private val PACKAGE_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)*$")
        private val MIME_PATTERN = Regex("^[^\\s/]+/[^\\s/]+$")
        private val SAFE_FLAGS = Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND

        internal fun safeFlags(): Int = SAFE_FLAGS
    }
}
