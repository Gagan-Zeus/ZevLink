package com.zevclip.sender.airplay

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap

object BPlist {
    private val HEADER = "bplist00".toByteArray(StandardCharsets.US_ASCII)
    private const val TRAILER_SIZE = 32

    sealed class Value {
        data class BoolValue(val value: Boolean) : Value()
        data class IntValue(val value: Long) : Value()
        data class RealValue(val value: Double) : Value()
        data class DataValue(val value: ByteArray) : Value() {
            override fun equals(other: Any?): Boolean {
                return other is DataValue && value.contentEquals(other.value)
            }

            override fun hashCode(): Int {
                return value.contentHashCode()
            }
        }
        data class StringValue(val value: String) : Value()
        data class ArrayValue(val values: List<Value>) : Value()
        data class DictValue(val values: LinkedHashMap<String, Value>) : Value()
    }

    private sealed class ObjectValue {
        data class BoolObject(val value: Boolean) : ObjectValue()
        data class IntObject(val value: Long) : ObjectValue()
        data class RealObject(val value: Double) : ObjectValue()
        data class DataObject(val value: ByteArray) : ObjectValue()
        data class StringObject(val value: String) : ObjectValue()
        data class ArrayObject(val refs: List<Int>) : ObjectValue()
        data class DictObject(val keyRefs: List<Int>, val valueRefs: List<Int>) : ObjectValue()
    }

    fun encode(value: Value): ByteArray {
        val objects = mutableListOf<ObjectValue>()

        fun add(nextValue: Value): Int {
            val index = objects.size
            objects += ObjectValue.BoolObject(false)
            objects[index] = when (nextValue) {
                is Value.BoolValue -> ObjectValue.BoolObject(nextValue.value)
                is Value.IntValue -> ObjectValue.IntObject(nextValue.value)
                is Value.RealValue -> ObjectValue.RealObject(nextValue.value)
                is Value.DataValue -> ObjectValue.DataObject(nextValue.value)
                is Value.StringValue -> ObjectValue.StringObject(nextValue.value)
                is Value.ArrayValue -> ObjectValue.ArrayObject(nextValue.values.map { add(it) })
                is Value.DictValue -> {
                    val keyRefs = mutableListOf<Int>()
                    val valueRefs = mutableListOf<Int>()
                    nextValue.values.forEach { (key, childValue) ->
                        keyRefs += add(Value.StringValue(key))
                        valueRefs += add(childValue)
                    }
                    ObjectValue.DictObject(keyRefs, valueRefs)
                }
            }
            return index
        }

        val rootIndex = add(value)
        val objectRefSize = bytesNeeded(objects.size.toLong())
        val output = ByteArrayOutputStream()
        val offsets = mutableListOf<Int>()

        output.write(HEADER)
        objects.forEach { objectValue ->
            offsets += output.size()
            writeObject(output, objectValue, objectRefSize)
        }

        val offsetTableOffset = output.size()
        val offsetIntSize = bytesNeeded(offsets.maxOrNull()?.toLong() ?: 0L)
        offsets.forEach { offset ->
            output.writeSizedInt(offset.toLong(), offsetIntSize)
        }

        output.write(ByteArray(6))
        output.write(offsetIntSize)
        output.write(objectRefSize)
        output.writeSizedInt(objects.size.toLong(), 8)
        output.writeSizedInt(rootIndex.toLong(), 8)
        output.writeSizedInt(offsetTableOffset.toLong(), 8)
        return output.toByteArray()
    }

    fun decode(bytes: ByteArray): Value {
        require(bytes.size >= HEADER.size + TRAILER_SIZE) { "Binary plist is too short." }
        require(bytes.copyOfRange(0, HEADER.size).contentEquals(HEADER)) {
            "Binary plist header is missing."
        }

        val trailerOffset = bytes.size - TRAILER_SIZE
        val offsetIntSize = bytes[trailerOffset + 6].toInt() and 0xFF
        val objectRefSize = bytes[trailerOffset + 7].toInt() and 0xFF
        val objectCount = bytes.readSizedInt(trailerOffset + 8, 8).toInt()
        val rootIndex = bytes.readSizedInt(trailerOffset + 16, 8).toInt()
        val offsetTableOffset = bytes.readSizedInt(trailerOffset + 24, 8).toInt()

        require(offsetIntSize > 0 && objectRefSize > 0) { "Binary plist trailer has invalid sizes." }
        require(objectCount > 0 && rootIndex in 0 until objectCount) { "Binary plist trailer has invalid object indexes." }
        require(offsetTableOffset in HEADER.size until trailerOffset) { "Binary plist offset table is invalid." }

        val offsets = IntArray(objectCount) { index ->
            bytes.readSizedInt(offsetTableOffset + (index * offsetIntSize), offsetIntSize).toInt()
        }
        val cache = arrayOfNulls<Value>(objectCount)

        fun readObject(index: Int): Value {
            require(index in 0 until objectCount) { "Binary plist object reference is out of range." }
            cache[index]?.let { return it }

            val offset = offsets[index]
            require(offset in HEADER.size until offsetTableOffset) { "Binary plist object offset is invalid." }
            val marker = bytes[offset].toInt() and 0xFF
            val type = marker and 0xF0
            val nibble = marker and 0x0F

            val value = when (type) {
                0x00 -> when (nibble) {
                    0x08 -> Value.BoolValue(false)
                    0x09 -> Value.BoolValue(true)
                    else -> throw IllegalArgumentException("Unsupported binary plist simple marker: $marker")
                }
                0x10 -> {
                    val length = 1 shl nibble
                    Value.IntValue(bytes.readSizedInt(offset + 1, length))
                }
                0x20 -> {
                    val length = 1 shl nibble
                    require(length == 4 || length == 8) { "Unsupported binary plist real length: $length" }
                    val bits = bytes.readSizedInt(offset + 1, length)
                    Value.RealValue(
                        if (length == 4) {
                            java.lang.Float.intBitsToFloat(bits.toInt()).toDouble()
                        } else {
                            java.lang.Double.longBitsToDouble(bits)
                        }
                    )
                }
                0x40 -> {
                    val count = readCount(bytes, offset, nibble)
                    Value.DataValue(bytes.copyOfRange(count.nextOffset, count.nextOffset + count.value))
                }
                0x50 -> {
                    val count = readCount(bytes, offset, nibble)
                    Value.StringValue(
                        String(bytes, count.nextOffset, count.value, StandardCharsets.US_ASCII)
                    )
                }
                0x60 -> {
                    val count = readCount(bytes, offset, nibble)
                    Value.StringValue(readUtf16Be(bytes, count.nextOffset, count.value))
                }
                0xA0 -> {
                    val count = readCount(bytes, offset, nibble)
                    val refs = (0 until count.value).map { itemIndex ->
                        bytes.readSizedInt(count.nextOffset + (itemIndex * objectRefSize), objectRefSize).toInt()
                    }
                    Value.ArrayValue(refs.map { readObject(it) })
                }
                0xD0 -> {
                    val count = readCount(bytes, offset, nibble)
                    val keyStart = count.nextOffset
                    val valueStart = keyStart + (count.value * objectRefSize)
                    val map = LinkedHashMap<String, Value>()
                    repeat(count.value) { itemIndex ->
                        val keyRef = bytes.readSizedInt(keyStart + (itemIndex * objectRefSize), objectRefSize).toInt()
                        val valueRef = bytes.readSizedInt(valueStart + (itemIndex * objectRefSize), objectRefSize).toInt()
                        val key = readObject(keyRef) as? Value.StringValue
                            ?: throw IllegalArgumentException("Binary plist dictionary key is not a string.")
                        map[key.value] = readObject(valueRef)
                    }
                    Value.DictValue(map)
                }
                else -> throw IllegalArgumentException("Unsupported binary plist object marker: $marker")
            }

            cache[index] = value
            return value
        }

        return readObject(rootIndex)
    }

    fun dict(vararg entries: Pair<String, Value>): Value.DictValue {
        return Value.DictValue(LinkedHashMap<String, Value>().apply { putAll(entries) })
    }

    fun array(vararg values: Value): Value.ArrayValue {
        return Value.ArrayValue(values.toList())
    }

    fun string(value: String): Value.StringValue {
        return Value.StringValue(value)
    }

    fun int(value: Long): Value.IntValue {
        return Value.IntValue(value)
    }

    fun real(value: Double): Value.RealValue {
        return Value.RealValue(value)
    }

    fun bool(value: Boolean): Value.BoolValue {
        return Value.BoolValue(value)
    }

    fun data(value: ByteArray): Value.DataValue {
        return Value.DataValue(value)
    }

    private fun writeObject(
        output: ByteArrayOutputStream,
        objectValue: ObjectValue,
        objectRefSize: Int
    ) {
        when (objectValue) {
            is ObjectValue.BoolObject -> output.write(if (objectValue.value) 0x09 else 0x08)
            is ObjectValue.IntObject -> {
                val byteCount = integerByteCount(objectValue.value)
                val power = when (byteCount) {
                    1 -> 0
                    2 -> 1
                    4 -> 2
                    else -> 3
                }
                output.write(0x10 or power)
                output.writeSizedInt(objectValue.value, byteCount)
            }
            is ObjectValue.RealObject -> {
                output.write(0x23)
                output.writeSizedInt(java.lang.Double.doubleToLongBits(objectValue.value), 8)
            }
            is ObjectValue.DataObject -> {
                writeCountMarker(output, 0x40, objectValue.value.size)
                output.write(objectValue.value)
            }
            is ObjectValue.StringObject -> writeString(output, objectValue.value)
            is ObjectValue.ArrayObject -> {
                writeCountMarker(output, 0xA0, objectValue.refs.size)
                objectValue.refs.forEach { output.writeSizedInt(it.toLong(), objectRefSize) }
            }
            is ObjectValue.DictObject -> {
                writeCountMarker(output, 0xD0, objectValue.keyRefs.size)
                objectValue.keyRefs.forEach { output.writeSizedInt(it.toLong(), objectRefSize) }
                objectValue.valueRefs.forEach { output.writeSizedInt(it.toLong(), objectRefSize) }
            }
        }
    }

    private fun writeString(output: ByteArrayOutputStream, value: String) {
        if (value.all { it.code <= 0x7F }) {
            val bytes = value.toByteArray(StandardCharsets.US_ASCII)
            writeCountMarker(output, 0x50, bytes.size)
            output.write(bytes)
        } else {
            writeCountMarker(output, 0x60, value.length)
            value.forEach { char ->
                output.write((char.code ushr 8) and 0xFF)
                output.write(char.code and 0xFF)
            }
        }
    }

    private fun writeCountMarker(output: ByteArrayOutputStream, typeMarker: Int, count: Int) {
        if (count < 15) {
            output.write(typeMarker or count)
            return
        }

        output.write(typeMarker or 0x0F)
        val byteCount = integerByteCount(count.toLong())
        val power = when (byteCount) {
            1 -> 0
            2 -> 1
            4 -> 2
            else -> 3
        }
        output.write(0x10 or power)
        output.writeSizedInt(count.toLong(), byteCount)
    }

    private fun readCount(bytes: ByteArray, offset: Int, nibble: Int): Count {
        if (nibble < 15) {
            return Count(nibble, offset + 1)
        }

        val intMarkerOffset = offset + 1
        val marker = bytes[intMarkerOffset].toInt() and 0xFF
        require((marker and 0xF0) == 0x10) { "Binary plist extended count is not an integer." }
        val byteCount = 1 shl (marker and 0x0F)
        return Count(
            value = bytes.readSizedInt(intMarkerOffset + 1, byteCount).toInt(),
            nextOffset = intMarkerOffset + 1 + byteCount
        )
    }

    private fun readUtf16Be(bytes: ByteArray, offset: Int, charCount: Int): String {
        val chars = CharArray(charCount)
        repeat(charCount) { index ->
            val high = bytes[offset + (index * 2)].toInt() and 0xFF
            val low = bytes[offset + (index * 2) + 1].toInt() and 0xFF
            chars[index] = ((high shl 8) or low).toChar()
        }
        return String(chars)
    }

    private fun integerByteCount(value: Long): Int {
        require(value >= 0) { "Binary plist writer only supports non-negative integers." }
        return when {
            value <= 0xFF -> 1
            value <= 0xFFFF -> 2
            value <= 0xFFFFFFFFL -> 4
            else -> 8
        }
    }

    private fun bytesNeeded(value: Long): Int {
        return when {
            value <= 0xFF -> 1
            value <= 0xFFFF -> 2
            value <= 0xFFFFFFFFL -> 4
            else -> 8
        }
    }

    private fun ByteArrayOutputStream.writeSizedInt(value: Long, byteCount: Int) {
        for (index in byteCount - 1 downTo 0) {
            write(((value ushr (index * 8)) and 0xFF).toInt())
        }
    }

    private fun ByteArray.readSizedInt(offset: Int, byteCount: Int): Long {
        require(offset >= 0 && offset + byteCount <= size) { "Binary plist integer is out of bounds." }
        var value = 0L
        repeat(byteCount) { index ->
            value = (value shl 8) or (this[offset + index].toLong() and 0xFF)
        }
        return value
    }

    private data class Count(
        val value: Int,
        val nextOffset: Int
    )
}
