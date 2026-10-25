package uz.kodlikino.top4.utils

import android.util.Base64
import com.android.billingclient.api.Purchase
import org.telegram.messenger.FileLog
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Play xarid imzosini tekshirish — Google Play Console → Monetization setup →
 * Licensing bo'limidagi RSA ochiq kalit. Kalit maxfiy emas (u ilova binary'sida
 * turadi); maqsad: modified Play Store yoki IAP-spoofer qaytargan soxta
 * purchase'lar premium grantga aylanmasligi.
 */
object YuzBillingSecurity {

    private const val PUBLIC_KEY =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAp2zsqvgDUCG26+FBjF22+sTS90t9hStmb3CHYsoA0xb+YxJXsHYTDV2BzZ6yoglyF3Ow449sKqGdzhlG6/xGyD1uOb4SyMp4d72FBtvadVvx9GSfgTOsYCgckVI2fTtRfjwYd5KuEze+yf0I4EmhLINNqVLwDA2SLFtV5aHMVc6AoT+dDIcTuLhqR/M8pO2DmEdURiw0s74U9MB1WU46m43nz+sQ5gLXW0jymbJCp07uDqQHcS1oVowbdRfsZgVMLOqOTEDXcqXso5pSTriG5yRo1NHbX4nk885RD/76ZU/gEHwHjQyLxcRyqQPjxkhOTZ00wNqPdvAAuKcQ5QSKVwIDAQAB"

    @Volatile
    private var cachedKey: PublicKey? = null

    fun isSignatureValid(purchase: Purchase): Boolean {
        val signature = purchase.signature
        if (signature.isBlank()) return false
        return try {
            val key = cachedKey ?: loadKey().also { cachedKey = it }
            val verifier = Signature.getInstance("SHA1withRSA")
            verifier.initVerify(key)
            verifier.update(purchase.originalJson.toByteArray(Charsets.UTF_8))
            verifier.verify(Base64.decode(signature, Base64.DEFAULT))
        } catch (e: Exception) {
            FileLog.e(e)
            false
        }
    }

    private fun loadKey(): PublicKey {
        val decoded = Base64.decode(PUBLIC_KEY, Base64.DEFAULT)
        return KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(decoded))
    }
}
