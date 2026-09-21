// patcher/ApkSigner.kt — signs patched APK with a generated debug keystore
package com.theo.patcher.patcher

import android.content.Context
import com.theo.patcher.util.RootUtil
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Date

object ApkSigner {

    private const val KEYSTORE_FILE = "theo_patch_key.jks"
    private const val KEY_ALIAS     = "theopatch"
    private const val KEY_PASS      = "theopatch123"

    fun sign(apk: File, context: Context): File {
        val keystore = getOrCreateKeystore(context)
        val signedApk = File(apk.parent, "signed_${apk.name}")

        return try {
            signWithApkSig(apk, signedApk, keystore)
            signedApk
        } catch (e: Exception) {
            signWithShell(apk, signedApk, keystore)
            signedApk
        }
    }

    private fun signWithApkSig(input: File, output: File, keystore: File) {
        val signerClass = Class.forName("com.android.apksig.ApkSigner")
        val builderClass = Class.forName("com.android.apksig.ApkSigner\$Builder")

        val ks = KeyStore.getInstance("JKS").apply {
            load(keystore.inputStream(), KEY_PASS.toCharArray())
        }
        val privateKey = ks.getKey(KEY_ALIAS, KEY_PASS.toCharArray()) as java.security.PrivateKey
        val certChain = ks.getCertificateChain(KEY_ALIAS).map { it as X509Certificate }

        val signerConfigBuilder = Class.forName("com.android.apksig.ApkSigner\$SignerConfig\$Builder")
        val scb = signerConfigBuilder.getConstructor(String::class.java, java.security.PrivateKey::class.java, List::class.java)
            .newInstance("CERT", privateKey, certChain)
        val signerConfig = signerConfigBuilder.getMethod("build").invoke(scb)

        val builder = builderClass.getConstructor(List::class.java).newInstance(listOf(signerConfig))
        builderClass.getMethod("setInputApk", File::class.java).invoke(builder, input)
        builderClass.getMethod("setOutputApk", File::class.java).invoke(builder, output)
        builderClass.getMethod("setV1SigningEnabled", Boolean::class.java).invoke(builder, true)
        builderClass.getMethod("setV2SigningEnabled", Boolean::class.java).invoke(builder, true)
        val signer = builderClass.getMethod("build").invoke(builder)
        signerClass.getMethod("sign").invoke(signer)
    }

    private fun signWithShell(input: File, output: File, keystore: File) {
        RootUtil.exec(
            "jarsigner -keystore ${keystore.absolutePath}" +
            " -storepass $KEY_PASS -keypass $KEY_PASS" +
            " -signedjar ${output.absolutePath}" +
            " ${input.absolutePath} $KEY_ALIAS"
        )
    }

    private fun getOrCreateKeystore(context: Context): File {
        val ksFile = File(context.filesDir, KEYSTORE_FILE)
        if (ksFile.exists()) return ksFile

        val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val kp = kpg.generateKeyPair()
        val cert = generateSelfSignedCert(kp)

        val ks = KeyStore.getInstance("JKS").apply {
            load(null, KEY_PASS.toCharArray())
            setKeyEntry(KEY_ALIAS, kp.private, KEY_PASS.toCharArray(), arrayOf(cert))
        }
        ksFile.outputStream().use { ks.store(it, KEY_PASS.toCharArray()) }
        return ksFile
    }

    private fun generateSelfSignedCert(kp: java.security.KeyPair): X509Certificate {
        val subject = "CN=TheoPatcher, O=TheoPatcher, C=BR"
        try {
            val x500Class = Class.forName("sun.security.x509.X500Name")
            val x500Name = x500Class.getConstructor(String::class.java).newInstance(subject)
            val certInfoClass = Class.forName("sun.security.x509.X509CertInfo")
            val certInfo = certInfoClass.newInstance()
            val dateClass = Class.forName("sun.security.x509.CertificateValidity")
            val now = Date()
            val exp = Date(now.time + 365L * 24 * 60 * 60 * 1000 * 10)
            val validity = dateClass.getConstructor(Date::class.java, Date::class.java).newInstance(now, exp)
            val setMethod = certInfoClass.getMethod("set", String::class.java, Any::class.java)
            setMethod.invoke(certInfo, "validity", validity)
            val snClass = Class.forName("sun.security.x509.CertificateSerialNumber")
            setMethod.invoke(certInfo, "serialNumber", snClass.getConstructor(Int::class.java).newInstance(1))
            val subjectClass = Class.forName("sun.security.x509.CertificateSubjectName")
            setMethod.invoke(certInfo, "subject", subjectClass.getConstructor(x500Class).newInstance(x500Name))
            val issuerClass = Class.forName("sun.security.x509.CertificateIssuerName")
            setMethod.invoke(certInfo, "issuer", issuerClass.getConstructor(x500Class).newInstance(x500Name))
            val keyClass = Class.forName("sun.security.x509.CertificateX509Key")
            setMethod.invoke(certInfo, "key", keyClass.getConstructor(java.security.PublicKey::class.java).newInstance(kp.public))
            val algClass = Class.forName("sun.security.x509.CertificateAlgorithmId")
            val algIdClass = Class.forName("sun.security.x509.AlgorithmId")
            val sha256rsa = algIdClass.getMethod("get", String::class.java).invoke(null, "SHA256withRSA")
            setMethod.invoke(certInfo, "algorithmID", algClass.getConstructor(algIdClass).newInstance(sha256rsa))
            val certVersionClass = Class.forName("sun.security.x509.CertificateVersion")
            setMethod.invoke(certInfo, "version", certVersionClass.newInstance())
            val x509Class = Class.forName("sun.security.x509.X509CertImpl")
            val x509Cert = x509Class.getConstructor(certInfoClass).newInstance(certInfo)
            x509Class.getMethod("sign", java.security.PrivateKey::class.java, String::class.java)
                .invoke(x509Cert, kp.private, "SHA256withRSA")
            return x509Cert as X509Certificate
        } catch (e: Exception) {
            throw RuntimeException("Cannot generate signing certificate: ${e.message}")
        }
    }
}
