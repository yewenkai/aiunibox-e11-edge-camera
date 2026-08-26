package org.e11camera.edge

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory

data class LocalHttpsStatus(
    val configured: Boolean = false,
    val running: Boolean = false,
    val port: Int = LocalHttpsConfig.PORT,
    val certificateSha256: String = "",
    val certificateExpiresAt: Long = 0L,
    val lastError: String = ""
)

data class LocalHttpsMaterial(
    val keyStore: KeyStore,
    val keyManagerFactory: KeyManagerFactory,
    val certificateSha256: String,
    val certificateExpiresAt: Long
)

/** 从应用私有目录加载由部署脚本生成的设备专用 PKCS#12。 */
object LocalHttpsConfig {
    const val PORT = 8443
    private const val TLS_DIRECTORY = "tls"
    private const val KEYSTORE_FILE = "server.p12"
    private const val PASSWORD_FILE = "password.txt"
    private const val CA_CERTIFICATE_FILE = "local-ca.crt"

    fun isConfigured(context: Context): Boolean =
        keyStoreFile(context).isFile && passwordFile(context).isFile

    fun caCertificateFile(context: Context): File =
        File(File(context.filesDir, TLS_DIRECTORY), CA_CERTIFICATE_FILE)

    fun load(context: Context): LocalHttpsMaterial {
        val keyStoreFile = keyStoreFile(context)
        val passwordFile = passwordFile(context)
        require(keyStoreFile.isFile) { "https_keystore_missing" }
        require(passwordFile.isFile) { "https_password_missing" }

        val password = passwordFile.readText(Charsets.UTF_8).trim().toCharArray()
        require(password.isNotEmpty()) { "https_password_empty" }
        val keyStore = KeyStore.getInstance("PKCS12")
        FileInputStream(keyStoreFile).use { keyStore.load(it, password) }

        val aliases = keyStore.aliases()
        var keyAlias: String? = null
        while (aliases.hasMoreElements()) {
            val candidate = aliases.nextElement()
            if (keyStore.isKeyEntry(candidate)) {
                keyAlias = candidate
                break
            }
        }
        require(keyAlias != null) { "https_private_key_missing" }
        val certificate = keyStore.getCertificate(keyAlias) as? X509Certificate
            ?: error("https_certificate_missing")
        certificate.checkValidity()

        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(keyStore, password)
        password.fill('\u0000')

        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        val fingerprint = digest.joinToString(":") { "%02X".format(it.toInt() and 0xff) }
        return LocalHttpsMaterial(
            keyStore = keyStore,
            keyManagerFactory = keyManagerFactory,
            certificateSha256 = fingerprint,
            certificateExpiresAt = certificate.notAfter.time
        )
    }

    private fun keyStoreFile(context: Context): File =
        File(File(context.filesDir, TLS_DIRECTORY), KEYSTORE_FILE)

    private fun passwordFile(context: Context): File =
        File(File(context.filesDir, TLS_DIRECTORY), PASSWORD_FILE)
}
