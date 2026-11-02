package com.zevclip.sender

internal object IntentBroadcastRequestRules {
    fun validate(request: ParsedIntentBroadcastRequest): String? {
        if (request.requestId.isBlank() || request.requestId.length > 128) return "requestId is invalid."
        if (request.action.isBlank() || request.action.length > 256 || request.action != request.action.trim()) {
            return "action is invalid."
        }
        if (request.targetPackage != null && !Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)*$").matches(request.targetPackage)) {
            return "targetPackage is invalid."
        }
        if (request.categories.size > 32 || request.categories.toSet().size != request.categories.size) {
            return "categories are invalid."
        }
        if (request.flags < 0 || request.flags and IntentBroadcastHandler.safeFlags().inv() != 0) {
            return "flags are invalid."
        }
        if (request.extras.size > 64) return "too many extras."
        request.extras.forEach { extra ->
            if (extra.key.isBlank() || extra.key.length > 128) return "extra key is invalid."
            when (extra.type) {
                "string" -> if (extra.value !is String) return "string extra is invalid."
                "boolean" -> if (extra.value !is Boolean) return "boolean extra is invalid."
                "int" -> if (extra.value !is Int) return "int extra is invalid."
                "long" -> if (extra.value !is Long) return "long extra is invalid."
                "float" -> if (extra.value !is Float || !(extra.value as Float).isFinite()) return "float extra is invalid."
                "double" -> if (extra.value !is Double || !(extra.value as Double).isFinite()) return "double extra is invalid."
                "string_array" -> if (extra.value !is Array<*> || (extra.value as Array<*>).any { it !is String }) return "string array extra is invalid."
                else -> return "unsupported extra type."
            }
        }
        return null
    }
}
