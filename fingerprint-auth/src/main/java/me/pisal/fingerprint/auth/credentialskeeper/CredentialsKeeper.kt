package me.pisal.fingerprint.auth.credentialskeeper

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import me.pisal.fingerprint.auth.FingerprintAuth
import me.pisal.fingerprint.auth.model.ICredentials


/**
 * Trying to call these functions before authentication with allowed Device/Biometric auth methods
 * will throw security exception.
 */

class CredentialsKeeper(private val context: Context) {

    inline fun <reified T : ICredentials> saveCredentials(key: String, credentials: T) {
        encryptedSharedPreferences?.edit(true) {
            putString(key, Gson().toJson(credentials))
        }
    }

    fun removeCredentials(key: String) {
        encryptedSharedPreferences?.edit(true) {
            putString(key, "")
        }
    }

    inline fun <reified T : ICredentials> readCredentials(key: String): T? {
        val credentialsJson = encryptedSharedPreferences?.getString(key, null)
        return Gson().fromJson(credentialsJson ?: "", T::class.java)
    }

    private fun getOrCreateMasterKey(): MasterKey? {
        try {
            val keyProperties = KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            val keySpec = KeyGenParameterSpec.Builder(LOCKED_KEY_ALIAS_NAME, keyProperties).apply {
                setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                setUserAuthenticationRequired(true)
                setKeySize(256)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    setInvalidatedByBiometricEnrollment(FingerprintAuth.invalidateByFingerprintEnrollment)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(
                        FingerprintAuth.validityDuration,
                        KeyProperties.AUTH_BIOMETRIC_STRONG
                    )
                } else {
                    setUserAuthenticationValidityDurationSeconds(FingerprintAuth.validityDuration)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val hasStrongBox = context
                        .packageManager
                        .hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
                    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                        if (hasStrongBox) {
                            setIsStrongBoxBacked(true)
                        }
                    }
                }
            }.build()
            return MasterKey
                .Builder(context, LOCKED_KEY_ALIAS_NAME)
                .setKeyGenParameterSpec(keySpec)
                .build()
        } catch (t: Throwable) {
            t.printStackTrace()
            return null
        }
    }

    val encryptedSharedPreferences by lazy {
        val masterKey = getOrCreateMasterKey()
        if (masterKey == null) {
            null
        } else {
            EncryptedSharedPreferences.create(
                context,
                SHARED_PREFERENCE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }

    companion object {
        private const val LOCKED_KEY_ALIAS_NAME = "2PpI5O9HM7L4"
        private const val SHARED_PREFERENCE_NAME = "ae0p8RPKSl5G"
    }
}