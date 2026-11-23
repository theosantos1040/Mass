package com.theo.patcher.patcher

import android.content.Context
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.*
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.*

object ApkSigner {

    fun sign(unsignedApk: File, context: Context): File {
        val mat = getOrCreateSigningMaterial(context)
        val signedApk = File(unsignedApk.parent, "signed_${unsignedApk.name}")
        v1Sign(unsignedApk, signedApk, mat)
        v2Sign(signedApk, mat)
        return signedApk
    }

    private class SigningMaterial(
        val privateKey: PrivateKey,
        val certificate: X509Certificate,
        val certDer: ByteArray
    )

    private fun getOrCreateSigningMaterial(context: Context): SigningMaterial {
        val p12 = File(context.filesDir, "theo_sign.p12")
        val pass = "theopatch".toCharArray()
        val alias = "theopatch"

        if (p12.exists()) {
            val ks = KeyStore.getInstance("PKCS12")
            p12.inputStream().use { ks.load(it, pass) }
            val key = ks.getKey(alias, pass) as PrivateKey
            val cert = ks.getCertificate(alias) as X509Certificate
            return SigningMaterial(key, cert, cert.encoded)
        }

        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()
        val certDer = buildSelfSignedCert(kp)
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate

        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, pass)
        ks.setKeyEntry(alias, kp.private, pass, arrayOf(cert))
        p12.outputStream().use { ks.store(it, pass) }

        return SigningMaterial(kp.private, cert, certDer)
    }

    // ========================= v1 JAR signing =========================

    private fun v1Sign(input: File, output: File, mat: SigningMaterial) {
        val entries = linkedMapOf<String, ByteArray>()
        val storedNames = mutableSetOf<String>()

        ZipFile(input).use { zip ->
            zip.entries().asSequence()
                .filter { !it.name.startsWith("META-INF/") }
                .forEach { entry ->
                    entries[entry.name] = zip.getInputStream(entry).readBytes()
                    if (entry.method == ZipEntry.STORED) storedNames += entry.name
                }
        }

        val md = MessageDigest.getInstance("SHA-256")

        val mfSections = linkedMapOf<String, String>()
        val mf = StringBuilder()
        mf.append("Manifest-Version: 1.0\r\n")
        mf.append("Created-By: TheoPatcher\r\n\r\n")
        for ((name, data) in entries) {
            val digest = Base64.encodeToString(md.digest(data), Base64.NO_WRAP)
            val section = wrap("Name: $name") + wrap("SHA-256-Digest: $digest") + "\r\n"
            mf.append(section)
            mfSections[name] = section
        }
        val mfBytes = mf.toString().toByteArray(Charsets.UTF_8)

        val sf = StringBuilder()
        sf.append("Signature-Version: 1.0\r\n")
        sf.append("Created-By: TheoPatcher\r\n")
        sf.append(wrap("SHA-256-Digest-Manifest: ${Base64.encodeToString(md.digest(mfBytes), Base64.NO_WRAP)}"))
        sf.append("\r\n")
        for ((name, section) in mfSections) {
            val d = Base64.encodeToString(md.digest(section.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
            sf.append(wrap("Name: $name") + wrap("SHA-256-Digest: $d") + "\r\n")
        }
        val sfBytes = sf.toString().toByteArray(Charsets.UTF_8)

        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(mat.privateKey)
        sig.update(sfBytes)
        val rsaBytes = buildPkcs7(mat.certDer, mat.certificate, sig.sign())

        ZipOutputStream(output.outputStream().buffered()).use { zout ->
            writeZipEntry(zout, "META-INF/MANIFEST.MF", mfBytes, false)
            writeZipEntry(zout, "META-INF/CERT.SF", sfBytes, false)
            writeZipEntry(zout, "META-INF/CERT.RSA", rsaBytes, false)

            for ((name, data) in entries) {
                val store = name in storedNames || name.endsWith(".arsc")
                writeZipEntry(zout, name, data, store)
            }
        }
    }

    private fun writeZipEntry(zout: ZipOutputStream, name: String, data: ByteArray, stored: Boolean) {
        val ze = ZipEntry(name)
        if (stored) {
            ze.method = ZipEntry.STORED
            ze.size = data.size.toLong()
            ze.compressedSize = data.size.toLong()
            ze.crc = CRC32().also { it.update(data) }.value
        } else {
            ze.method = ZipEntry.DEFLATED
        }
        zout.putNextEntry(ze)
        zout.write(data)
        zout.closeEntry()
    }

    private fun wrap(line: String): String {
        if (line.length <= 70) return "$line\r\n"
        val sb = StringBuilder()
        sb.append(line, 0, 70).append("\r\n")
        var i = 70
        while (i < line.length) {
            val end = minOf(i + 69, line.length)
            sb.append(' ').append(line, i, end).append("\r\n")
            i = end
        }
        return sb.toString()
    }

    // ========================= v2 APK Signature Scheme =========================

    private const val APK_SIG_SCHEME_V2_ID = 0x7109871a
    private const val CHUNK_SIZE = 1048576 // 1 MB

    private fun v2Sign(apk: File, mat: SigningMaterial) {
        val raf = RandomAccessFile(apk, "rw")
        try {
            val fileSize = raf.length().toInt()

            val eocdOffset = findEocd(raf) ?: throw RuntimeException("EOCD not found")
            raf.seek(eocdOffset.toLong() + 16)
            val cdOffset = readUint32Le(raf)

            val beforeCd = ByteArray(cdOffset)
            raf.seek(0)
            raf.readFully(beforeCd)

            val cdAndEocd = ByteArray(fileSize - cdOffset)
            raf.seek(cdOffset.toLong())
            raf.readFully(cdAndEocd)

            val cd = ByteArray(eocdOffset - cdOffset)
            System.arraycopy(cdAndEocd, 0, cd, 0, cd.size)
            val eocd = ByteArray(fileSize - eocdOffset)
            System.arraycopy(cdAndEocd, cd.size, eocd, 0, eocd.size)

            val topDigest = computeApkDigest(beforeCd, cd, eocd)

            val signedData = buildV2SignedData(topDigest, mat.certDer)
            val signedDataBytes = lengthPrefixed(signedData)

            val sig = Signature.getInstance("SHA256withRSA")
            sig.initSign(mat.privateKey)
            sig.update(signedData)
            val signature = sig.sign()

            val sigEntry = buildSignatureEntry(signature)
            val publicKeyDer = mat.certificate.publicKey.encoded

            val signer = lengthPrefixed(
                concat(signedDataBytes, lengthPrefixed(sigEntry), lengthPrefixed(publicKeyDer))
            )
            val v2Value = lengthPrefixed(signer)

            val sigBlock = buildApkSigningBlock(v2Value)

            val newEocd = eocd.copyOf()
            val newCdOffset = cdOffset + sigBlock.size
            putUint32Le(newEocd, 16, newCdOffset)

            raf.seek(cdOffset.toLong())
            raf.write(sigBlock)
            raf.write(cd)
            raf.write(newEocd)
            raf.setLength(cdOffset.toLong() + sigBlock.size + cd.size + newEocd.size)
        } finally {
            raf.close()
        }
    }

    private fun computeApkDigest(beforeCd: ByteArray, cd: ByteArray, eocd: ByteArray): ByteArray {
        val sections = listOf(beforeCd, cd, eocd)
        val chunkDigests = mutableListOf<ByteArray>()
        val md = MessageDigest.getInstance("SHA-256")

        for (section in sections) {
            var offset = 0
            while (offset < section.size) {
                val chunkLen = minOf(CHUNK_SIZE, section.size - offset)
                md.reset()
                md.update(byteArrayOf(0xa5.toByte()))
                md.update(uint32LeBytes(chunkLen))
                md.update(section, offset, chunkLen)
                chunkDigests += md.digest()
                offset += chunkLen
            }
        }

        md.reset()
        md.update(byteArrayOf(0x5a))
        md.update(uint32LeBytes(chunkDigests.size))
        for (d in chunkDigests) md.update(d)
        return md.digest()
    }

    private fun buildV2SignedData(digest: ByteArray, certDer: ByteArray): ByteArray {
        val digestEntry = ByteBuffer.allocate(8 + digest.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(4 + digest.size)
            .putInt(0x0103)
            .put(lengthPrefixed(digest))
        val digests = lengthPrefixed(digestEntry.array())
        val certs = lengthPrefixed(lengthPrefixed(certDer))
        return concat(digests, certs)
    }

    private fun buildSignatureEntry(signature: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(4 + 4 + signature.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(4 + signature.size)
            .putInt(0x0103)
            .put(signature)
        return buf.array()
    }

    private fun buildApkSigningBlock(v2Value: ByteArray): ByteArray {
        val pairSize = 4L + v2Value.size
        val payloadSize = 8L + pairSize
        val blockSize = payloadSize + 8 + 16

        val buf = ByteBuffer.allocate((8 + payloadSize + 8 + 16).toInt()).order(ByteOrder.LITTLE_ENDIAN)
        buf.putLong(blockSize)
        buf.putLong(pairSize)
        buf.putInt(APK_SIG_SCHEME_V2_ID)
        buf.put(v2Value)
        buf.putLong(blockSize)
        buf.put("APK Sig Block 42".toByteArray(Charsets.US_ASCII))
        return buf.array()
    }

    private fun findEocd(raf: RandomAccessFile): Int? {
        val fileSize = raf.length().toInt()
        val searchStart = maxOf(0, fileSize - 65557)
        val buf = ByteArray(fileSize - searchStart)
        raf.seek(searchStart.toLong())
        raf.readFully(buf)

        for (i in buf.size - 22 downTo 0) {
            if (buf[i] == 0x50.toByte() && buf[i + 1] == 0x4b.toByte() &&
                buf[i + 2] == 0x05.toByte() && buf[i + 3] == 0x06.toByte()
            ) {
                return searchStart + i
            }
        }
        return null
    }

    private fun readUint32Le(raf: RandomAccessFile): Int {
        val b = ByteArray(4)
        raf.readFully(b)
        return (b[0].toInt() and 0xFF) or
               ((b[1].toInt() and 0xFF) shl 8) or
               ((b[2].toInt() and 0xFF) shl 16) or
               ((b[3].toInt() and 0xFF) shl 24)
    }

    private fun uint32LeBytes(v: Int): ByteArray =
        byteArrayOf(
            (v and 0xFF).toByte(),
            ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(),
            ((v shr 24) and 0xFF).toByte()
        )

    private fun putUint32Le(buf: ByteArray, offset: Int, value: Int) {
        buf[offset]     = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun lengthPrefixed(data: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(4 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(data.size)
        buf.put(data)
        return buf.array()
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val total = parts.sumOf { it.size }
        val r = ByteArray(total)
        var off = 0
        for (p in parts) { p.copyInto(r, off); off += p.size }
        return r
    }

    // ========================= PKCS#7 SignedData =========================

    private fun buildPkcs7(certDer: ByteArray, cert: X509Certificate, signature: ByteArray): ByteArray {
        val issuerDer = cert.issuerX500Principal.encoded
        val serialDer = cert.serialNumber.toByteArray()
        val digestAlg = seq(oid(OID_SHA256))
        val encAlg = seq(oid(OID_RSA), DER_NULL)

        val signerInfo = seq(
            derInt(byteArrayOf(1)),
            seq(issuerDer, derInt(serialDer)),
            digestAlg,
            encAlg,
            oct(signature)
        )

        val signedData = seq(
            derInt(byteArrayOf(1)),
            derSet(digestAlg),
            seq(oid(OID_DATA)),
            ctx(0, certDer),
            derSet(signerInfo)
        )

        return seq(oid(OID_SIGNED_DATA), ctx(0, signedData))
    }

    // ========================= Self-signed X.509 (raw DER) =========================

    private fun buildSelfSignedCert(kp: KeyPair): ByteArray {
        val cn = seq(derSet(seq(oid(OID_CN), utf8("TheoPatcher"))))
        val now = Date()
        val exp = Date(now.time + 10L * 365 * 86400000)
        val validity = seq(utcTime(now), utcTime(exp))
        val algId = seq(oid(OID_SHA256_RSA), DER_NULL)

        val tbs = seq(
            ctx(0, derInt(byteArrayOf(2))),
            derInt(byteArrayOf(1)),
            algId,
            cn,
            validity,
            cn,
            kp.public.encoded
        )

        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(kp.private)
        sig.update(tbs)

        return seq(tbs, algId, bitStr(sig.sign()))
    }

    // ========================= DER primitives =========================

    private val OID_SHA256_RSA = bytes(0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x01, 0x0B)
    private val OID_RSA = bytes(0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x01, 0x01)
    private val OID_SHA256 = bytes(0x60, 0x86, 0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x01)
    private val OID_CN = bytes(0x55, 0x04, 0x03)
    private val OID_SIGNED_DATA = bytes(0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x07, 0x02)
    private val OID_DATA = bytes(0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x07, 0x01)
    private val DER_NULL = byteArrayOf(0x05, 0x00)

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun tlv(tag: Int, content: ByteArray): ByteArray {
        val len = encLen(content.size)
        val result = ByteArray(1 + len.size + content.size)
        result[0] = tag.toByte()
        len.copyInto(result, 1)
        content.copyInto(result, 1 + len.size)
        return result
    }

    private fun encLen(len: Int): ByteArray = when {
        len < 0x80 -> byteArrayOf(len.toByte())
        len < 0x100 -> byteArrayOf(0x81.toByte(), len.toByte())
        len < 0x10000 -> byteArrayOf(0x82.toByte(), (len shr 8).toByte(), len.toByte())
        else -> byteArrayOf(0x83.toByte(), (len shr 16).toByte(), (len shr 8).toByte(), len.toByte())
    }

    private fun cat(vararg parts: ByteArray): ByteArray {
        val total = parts.sumOf { it.size }
        val r = ByteArray(total)
        var off = 0
        for (p in parts) { p.copyInto(r, off); off += p.size }
        return r
    }

    private fun seq(vararg parts: ByteArray): ByteArray = tlv(0x30, cat(*parts))
    private fun derSet(vararg parts: ByteArray): ByteArray = tlv(0x31, cat(*parts))
    private fun derInt(v: ByteArray): ByteArray {
        val padded = if (v.isNotEmpty() && v[0] < 0) byteArrayOf(0) + v else v
        return tlv(0x02, padded)
    }
    private fun oid(v: ByteArray): ByteArray = tlv(0x06, v)
    private fun oct(v: ByteArray): ByteArray = tlv(0x04, v)
    private fun utf8(s: String): ByteArray = tlv(0x0C, s.toByteArray(Charsets.UTF_8))
    private fun bitStr(v: ByteArray): ByteArray = tlv(0x03, byteArrayOf(0) + v)
    private fun ctx(tag: Int, data: ByteArray): ByteArray = tlv(0xA0 + tag, data)

    private fun utcTime(d: Date): ByteArray {
        val fmt = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return tlv(0x17, fmt.format(d).toByteArray(Charsets.US_ASCII))
    }
}
