package com.theo.patcher.patcher

import java.security.MessageDigest
import java.util.zip.Adler32

object DexUtil {

    private val DEX_MAGIC = byteArrayOf(0x64, 0x65, 0x78, 0x0a, 0x30, 0x33)  // "dex\n03"

    /** Returns true if bytes still look like a valid DEX file. */
    fun looksValid(dex: ByteArray): Boolean {
        if (dex.size < 112) return false
        for (i in DEX_MAGIC.indices) {
            if (dex[i] != DEX_MAGIC[i]) return false
        }
        // Byte 6 must be a digit (5-9), byte 7 must be 0x00
        val versionDigit = dex[6].toInt() and 0xFF
        if (versionDigit !in 0x30..0x39) return false
        if (dex[7].toInt() != 0x00) return false
        return true
    }

    fun updateIntegrity(dex: ByteArray) {
        if (dex.size < 112) return
        updateSha1(dex)
        updateAdler32(dex)
    }

    private fun updateSha1(dex: ByteArray) {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(dex, 32, dex.size - 32)
        val hash = md.digest()
        hash.copyInto(dex, 12, 0, 20)
    }

    private fun updateAdler32(dex: ByteArray) {
        val adler = Adler32()
        adler.update(dex, 12, dex.size - 12)
        val checksum = adler.value.toInt()
        dex[8]  = (checksum and 0xFF).toByte()
        dex[9]  = ((checksum shr 8) and 0xFF).toByte()
        dex[10] = ((checksum shr 16) and 0xFF).toByte()
        dex[11] = ((checksum shr 24) and 0xFF).toByte()
    }
}
