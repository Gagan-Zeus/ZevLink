package com.zevclip.sender.airplay

import java.io.ByteArrayOutputStream
import java.util.LinkedHashMap

object Tlv8 {
    object Type {
        const val METHOD = 0x00
        const val IDENTIFIER = 0x01
        const val SALT = 0x02
        const val PUBLIC_KEY = 0x03
        const val PROOF = 0x04
        const val ENCRYPTED_DATA = 0x05
        const val STATE = 0x06
        const val ERROR = 0x07
        const val SIGNATURE = 0x0A
        const val FLAGS = 0x13
    }

    fun encode(vararg entries: Pair<Int, ByteArray>): ByteArray {
        return encode(entries.toList())
    }

    fun encode(entries: List<Pair<Int, ByteArray>>): ByteArray {
        val output = ByteArrayOutputStream()
        entries.forEach { (type, value) ->
            require(type in 0..0xFF) { "TLV8 type must fit in one byte." }
            if (value.isEmpty()) {
                output.write(type)
                output.write(0)
                return@forEach
            }

            var offset = 0
            while (offset < value.size) {
                val chunkSize = minOf(MAX_VALUE_CHUNK_SIZE, value.size - offset)
                output.write(type)
                output.write(chunkSize)
                output.write(value, offset, chunkSize)
                offset += chunkSize
            }
        }
        return output.toByteArray()
    }

    fun decode(data: ByteArray): LinkedHashMap<Int, ByteArray> {
        val output = LinkedHashMap<Int, ByteArrayOutputStream>()
        var offset = 0

        while (offset < data.size) {
            require(offset + 2 <= data.size) { "TLV8 record is missing type or length." }
            val type = data[offset].toInt() and 0xFF
            val length = data[offset + 1].toInt() and 0xFF
            val valueOffset = offset + 2
            val nextOffset = valueOffset + length
            require(nextOffset <= data.size) { "TLV8 record value is truncated." }

            val bucket = output.getOrPut(type) { ByteArrayOutputStream() }
            bucket.write(data, valueOffset, length)
            offset = nextOffset
        }

        return LinkedHashMap<Int, ByteArray>().apply {
            output.forEach { (type, value) -> put(type, value.toByteArray()) }
        }
    }

    fun state(value: Int): Pair<Int, ByteArray> {
        return Type.STATE to byteArrayOf(value.toByte())
    }

    fun method(value: Int): Pair<Int, ByteArray> {
        return Type.METHOD to byteArrayOf(value.toByte())
    }

    fun flags(value: Int): Pair<Int, ByteArray> {
        return Type.FLAGS to byteArrayOf(
            ((value ushr 24) and 0xFF).toByte(),
            ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte()
        )
    }

    private const val MAX_VALUE_CHUNK_SIZE = 255
}
