package com.theo.patcher.patcher

import android.content.Context
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.File
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

    // --- v1 JAR signing ---

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

    // --- PKCS#7 SignedData for .RSA ---

    private fun buildPkcs7(certDer: ByteArray, cert: X509Certificate, signature: ByteArray): ByteArray {
        val issuerDer = cert.issuerX500Principal.encoded
        val serialDer = cert.serialNumber.toByteArray()
        val digestAlg = seq(oid(OID_SHA256))
        val encAlg = seq(oid(OID_RSA), DER_NULL)

        val signerInfo = seq(
            int(byteArrayOf(1)),
            seq(issuerDer, int(serialDer)),
            digestAlg,
            encAlg,
            oct(signature)
        )

        val signedData = seq(
            int(byteArrayOf(1)),
            set(digestAlg),
            seq(oid(OID_DATA)),
            ctx(0, certDer),
            set(signerInfo)
        )

        return seq(oid(OID_SIGNED_DATA), ctx(0, signedData))
    }

    // --- Self-signed X.509 certificate (raw DER) ---

    private fun buildSelfSignedCert(kp: KeyPair): ByteArray {
        val cn = seq(set(seq(oid(OID_CN), utf8("TheoPatcher"))))
        val now = Date()
        val exp = Date(now.time + 10L * 365 * 86400000)
        val validity = seq(utcTime(now), utcTime(exp))
        val algId = seq(oid(OID_SHA256_RSA), DER_NULL)

        val tbs = seq(
            ctx(0, int(byteArrayOf(2))),
            int(byteArrayOf(1)),
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

    // --- DER encoding primitives ---

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
    private fun set(vararg parts: ByteArray): ByteArray = tlv(0x31, cat(*parts))
    private fun int(v: ByteArray): ByteArray {
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
