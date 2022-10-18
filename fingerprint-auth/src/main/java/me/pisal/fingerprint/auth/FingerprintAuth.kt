package me.pisal.fingerprint.auth

import android.app.KeyguardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties.*
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.core.hardware.fingerprint.FingerprintManagerCompat
import androidx.core.hardware.fingerprint.FingerprintManagerCompat.*
import androidx.core.os.CancellationSignal
import androidx.fragment.app.FragmentActivity
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import me.pisal.fingerprint.auth.credentialskeeper.CredentialsKeeper
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

typealias BiometricSuccessBlock = CredentialsKeeper.() -> Unit
typealias BiometricFailureBlock = (message: String) -> Unit

@Suppress("DEPRECATION")
class FingerprintAuth private constructor(private val mHostActivity: FragmentActivity) {

    private lateinit var mDialogFragment: BaseFingerprintDialogFragment
    private var mSuccessBlock: BiometricSuccessBlock? = null
    private var mFailureBlock: BiometricFailureBlock? = null

    /**
     * Set a custom dialog fragment for handling UI parts of fingerprint authentication
     */
    fun withDialogView(fragment: BaseFingerprintDialogFragment): FingerprintAuth {
        mDialogFragment = fragment
        return this
    }

    /**
     * Defaults to 128 bits.
     */
    fun setAesKeySize(size: Int): FingerprintAuth {
        aesKeySize = size
        return this
    }

    /**
     * Duration in seconds of how long the authenticated session is valid.
     * Defaults to 5 minutes.
     */
    fun setAuthValidityDuration(duration: Int) {
        validityDuration = duration
    }

    /**
     * After fingerprint scan is successful, you can access [CredentialsKeeper] safely
     * and without SecurityExceptions
     */
    fun doOnSuccess(block: BiometricSuccessBlock): FingerprintAuth {
        mSuccessBlock = block
        return this
    }

    fun doOnFailure(block: BiometricFailureBlock): FingerprintAuth {
        mFailureBlock = block
        return this
    }

    /**
     * Show the fingerprint dialog and start listening for sensor input
     */
    fun authenticate() {
        val manager = FingerprintManagerCompat.from(mHostActivity)
        validate(manager)

        mHostActivity.supportFragmentManager
            .findFragmentByTag(BaseFingerprintDialogFragment.TAG)
            ?.onDestroy()

        // Show a dialog to interact with user while scanning for a fingerprint
        mDialogFragment.show(
            mHostActivity.supportFragmentManager,
            BaseFingerprintDialogFragment.TAG
        )

        // Warm up the fingerprint hardware and starts scanning for a fingerprint
        manager.authenticate(
            cryptoObject(),
            FINGERPRINT_FLAGS,
            mDialogFragment.cancellationSignal,
            mAuthCallback,
            mDialogFragment.mHandler
        )
    }

    abstract class BaseFingerprintDialogFragment(@LayoutRes private val layoutRes: Int) :
        BottomSheetDialogFragment() {

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?,
        ): View? = inflater.inflate(layoutRes, container, false)

        abstract val authCallback: AuthenticationCallback
        abstract val cancellationSignal: CancellationSignal
        abstract val mHandler: Handler

        companion object {
            const val TAG = "BaseFingerprintDialogFragment"
        }
    }

    /////////////////////////////////////////
    //#region Private members
    /////////////////////////////////////////
    private val mCredentialsKeeper = CredentialsKeeper(mHostActivity.applicationContext)

    private val mAuthCallback: AuthenticationCallback =
        object : FingerprintManagerCompat.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: AuthenticationResult?) {
                super.onAuthenticationSucceeded(result)
                try {
                    mDialogFragment.authCallback.onAuthenticationSucceeded(result)
                    mDialogFragment.onDestroy()
                    mHostActivity.fragmentManager
                        ?.findFragmentByTag(BaseFingerprintDialogFragment.TAG)
                        ?.onDestroy()
                } catch (t: Throwable) {
                    t.printStackTrace()
                }
                mSuccessBlock?.invoke(mCredentialsKeeper)
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                mDialogFragment.authCallback.onAuthenticationFailed()
            }

            override fun onAuthenticationHelp(helpMsgId: Int, helpString: CharSequence?) {
                super.onAuthenticationHelp(helpMsgId, helpString)
                mDialogFragment.authCallback.onAuthenticationHelp(helpMsgId, helpString)
            }

            override fun onAuthenticationError(errMsgId: Int, errString: CharSequence?) {
                super.onAuthenticationError(errMsgId, errString)
                mDialogFragment.authCallback.onAuthenticationError(errMsgId, errString)
            }
        }

    private fun validate(fpManager: FingerprintManagerCompat) {
        if (!isKeyguardSecure(mHostActivity)) {
            throw IllegalStateException(mHostActivity.getString(R.string.msg_no_screen_lock_detected))
        }
        if (!::mDialogFragment.isInitialized) {
            throw IllegalStateException("Dialog view must be initialized before calling authenticate()")
        }
        if (!fpManager.isHardwareDetected) {
            throw IllegalStateException("No fingerprint sensor detected!")
        }
        if (!fpManager.hasEnrolledFingerprints()) {
            throw IllegalStateException("No fingerprints enrolled!")
        }
    }

    private fun isKeyguardSecure(context: Context): Boolean {
        val keyguardManager =
            context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return keyguardManager.isKeyguardSecure
    }

    private fun cryptoObject(): CryptoObject {
        val transformation = "$KEY_ALGORITHM_AES/$BLOCK_MODE_GCM/$ENCRYPTION_PADDING_NONE"
        val cipher = Cipher.getInstance(transformation)
        val secKey = getOrCreateSecretKey()
        cipher.init(Cipher.ENCRYPT_MODE, secKey)
        return CryptoObject(cipher)
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        val paramsBuilder = KeyGenParameterSpec.Builder(
            DUMMY_KEY_ALIAS,
            PURPOSE_ENCRYPT or PURPOSE_DECRYPT
        ).apply {
            setBlockModes(BLOCK_MODE_GCM)
            setEncryptionPaddings(ENCRYPTION_PADDING_NONE)
            setKeySize(aesKeySize)
            setUserAuthenticationRequired(true)
        }

        val keyGenParams = paramsBuilder.build()
        val keyGenerator = KeyGenerator.getInstance(
            KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        keyGenerator.init(keyGenParams)
        return keyGenerator.generateKey()
    }
    //#endregion

    companion object {
        private const val DEFAULT_VALIDITY_DURATION: Int = 60 * 5 // In Seconds
        private const val DEFAULT_AES_KEY_SIZE = 128
        private const val FINGERPRINT_FLAGS = 0
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val DUMMY_KEY_ALIAS = "pZZA27l28r97"

        fun from(hostActivity: FragmentActivity): FingerprintAuth {
            return FingerprintAuth(hostActivity)
        }

        var validityDuration: Int = DEFAULT_VALIDITY_DURATION
        var aesKeySize: Int = DEFAULT_AES_KEY_SIZE
    }
}