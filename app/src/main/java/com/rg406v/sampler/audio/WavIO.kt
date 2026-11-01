package com.rg406v.sampler.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Écriture de WAV 16 bits mono (relu ensuite par SampleLoader). */
object WavIO {
    fun write(f: File, data: FloatArray, sr: Int) {
        f.parentFile?.mkdirs()
        val bytes = data.size * 2
        val bb = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + bytes)
        bb.put("WAVE".toByteArray()); bb.put("fmt ".toByteArray())
        bb.putInt(16); bb.putShort(1); bb.putShort(1)
        bb.putInt(sr); bb.putInt(sr * 2); bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(bytes)
        for (v in data) bb.putShort((v.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        f.writeBytes(bb.array())
    }
}
