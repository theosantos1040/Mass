package com.theo.patcher.patcher

import java.security.MessageDigest
import java.util.zip.Adler32

object DexUtil {

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
