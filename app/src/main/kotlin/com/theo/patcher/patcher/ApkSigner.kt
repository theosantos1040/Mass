package com.theo.patcher.patcher

import android.content.Context
import com.android.apksig.ApkSigner as GoogleApkSigner
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date

object ApkSigner {

    init {
        Security.addProvider(BouncyCastleProvider())
    }

    fun sign(unsignedApk: File, context: Context, log: (String) -> Unit = {}): File {
        val mat = getOrCreateSigningMaterial(context)
        val signedApk = File(unsignedApk.parent, "signed_${unsignedApk.name}")

        val signerConfig = GoogleApkSigner.SignerConfig.Builder(
            "theopatch",
            mat.privateKey,
            listOf(mat.certificate)
        ).build()

        GoogleApkSigner.Builder(listOf(signerConfig))
            .setInputApk(unsignedApk)
            .setOutputApk(signedApk)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setV4SigningEnabled(false)
            .build()
            .sign()

        log("  + apksig: v1+v2+v3 (${signedApk.length() / 1024} KB)")
        return signedApk
    }

    private class SigningMaterial(
        val privateKey: PrivateKey,
        val certificate: X509Certificate
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
            return SigningMaterial(key, cert)
        }

        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()

        val now = Date()
        val expiry = Date(now.time + 10L * 365 * 86400_000L)
        val subject = X500Name("CN=TheoPatcher, O=VANTA, C=BR")
        val serial = BigInteger.valueOf(System.currentTimeMillis())

        val builder = JcaX509v3CertificateBuilder(subject, serial, now, expiry, subject, kp.public)
        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider("BC")
            .build(kp.private)
        val holder = builder.build(signer)
        val cert = JcaX509CertificateConverter().setProvider("BC").getCertificate(holder)

        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, pass)
        ks.setKeyEntry(alias, kp.private, pass, arrayOf<java.security.cert.Certificate>(cert))
        p12.outputStream().use { ks.store(it, pass) }

        return SigningMaterial(kp.private, cert)
    }
}
