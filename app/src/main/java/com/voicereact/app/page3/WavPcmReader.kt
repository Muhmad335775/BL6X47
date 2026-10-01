package com.voicereact.app.page3

import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavPcmReader {

    data class Pcm16(val samples: ShortArray, val sampleRate: Int)

    fun read(file: File): Pcm16 = parse(file.readBytes())

    fun readStream(input: InputStream): Pcm16 = parse(input.readBytes())

    private fun parse(bytes: ByteArray): Pcm16 {
        require(bytes.size >= 44) { "ملف WAV غير صالح" }
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        header.position(24)
        val sampleRate = header.int

        var pos = 12
        var dataOffset = -1
        var dataSize = 0
        while (pos + 8 <= bytes.size) {
            val chunkId = String(bytes, pos, 4, Charsets.US_ASCII)
            val sizeBuf = ByteBuffer.wrap(bytes, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN)
            val size = sizeBuf.int
            if (chunkId == "data") {
                dataOffset = pos + 8
                dataSize = size
                break
            }
            pos += 8 + size + (size % 2)
        }
        require(dataOffset >= 0) { "ما لقيت data chunk بملف WAV" }

        val end = (dataOffset + dataSize).coerceAtMost(bytes.size)
        val shortBuffer = ByteBuffer.wrap(bytes, dataOffset, end - dataOffset).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = ShortArray(shortBuffer.remaining())
        shortBuffer.get(samples)
        return Pcm16(samples, sampleRate)
    }
}
