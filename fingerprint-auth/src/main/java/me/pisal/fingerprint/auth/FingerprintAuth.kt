package me.pisal.fingerprint.auth

import android.app.KeyguardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties.BLOCK_MODE_GCM
import android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE
import android.security.keystore.KeyProperties.KEY_ALGORITHM_AES
import android.security.keystore.KeyProperties.PURPOSE_DECRYPT
import android.security.keystore.KeyProperties.PURPOSE_ENCRYPT
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.core.hardware.fingerprint.FingerprintManagerCompat
import androidx.core.hardware.fingerprint.FingerprintManagerCompat.AuthenticationCallback
import androidx.core.hardware.fingerprint.FingerprintManagerCompat.AuthenticationResult
import androidx.core.hardware.fingerprint.FingerprintManagerCompat.CryptoObject
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
    private lateinit var mFingerprintManager: FingerprintManagerCompat
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
     * Sets whether this key should be invalidated on biometric enrollment.
     *
     * <p>By default, {@code invalidateKey} is {@code true}, so keys that are valid for
     * biometric authentication only are <em>irreversibly invalidated</em> when a new
     * biometric is enrolled, or when all existing biometrics are deleted.  That may be
     * changed by calling this method with {@code invalidateKey} set to {@code false}.
     *
     * <p>Invalidating keys on enrollment of a new biometric or unenrollment of all biometrics
     * improves security by ensuring that an unauthorized person who obtains the password can't
     * gain the use of biometric-authenticated keys by enrolling their own biometric.  However,
     * invalidating keys makes key-dependent operations impossible, requiring some fallback
     * procedure to authenticate the user and set up a new key.
     */
    fun invalidateByFingerprintEnrollment(invalidateKey: Boolean) {
        invalidateByFingerprintEnrollment = invalidateKey
    }

    /**
     * After fingerprint scan is successful, you can access [CredentialsKeeper] safely
     * and without SecurityExceptions
     */
    fun doOnSuccess(block: BiometricSuccessBlock): FingerprintAuth {
        mSuccessBlock = block
        return this
    }

    /**
     * Error callback when the Fingerprint authentication fails.
     */
    fun doOnFailure(block: BiometricFailureBlock): FingerprintAuth {
        mFailureBlock = block
        return this
    }

    /**
     * Return whether the keyguard is secured by a PIN, pattern or password or a SIM card
     * is currently locked.
     * @return {@code true} if a PIN, pattern or password is set or a SIM card is locked.
     */
    fun deviceHasSecureLock(): Boolean {
        return isKeyguardSecure(mHostActivity)
    }

    /**
     * Determine if fingerprint hardware is present and functional.
     *
     * @return true if hardware is present and functional, false otherwise.
     */
    fun deviceHasFingerprintSensor(): Boolean {
        return mFingerprintManager.isHardwareDetected
    }

    /**
     * Determine if there is at least one fingerprint enrolled.
     *
     * @return true if at least one fingerprint is enrolled, false otherwise
     */
    fun deviceHasFingerprintsEnrolled(): Boolean {
        return mFingerprintManager.hasEnrolledFingerprints()
    }

    /**
     * @return true if the device has met all conditions to start using Fingerprint authentication.
     * { 1. deviceHasSecureLock(), 2. deviceHasFingerprintSensor(), 3. deviceHasFingerprintsEnrolled }
     */
    fun isDeviceEligible(): Boolean {
        return deviceHasSecureLock() &&
                deviceHasFingerprintSensor() &&
                deviceHasFingerprintsEnrolled()
    }

    /**
     * Show the fingerprint dialog and start listening for sensor input
     */
    fun authenticate() {
        validate()

        mHostActivity.supportFragmentManager
            .findFragmentByTag(BaseFingerprintDialogFragment.TAG)
            ?.onDestroy()

        // Show a dialog to interact with user while scanning for a fingerprint
        mDialogFragment.show(
            mHostActivity.supportFragmentManager,
            BaseFingerprintDialogFragment.TAG
        )

        // Warm up the fingerprint hardware and starts scanning for a fingerprint
        mFingerprintManager.authenticate(
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
        object : AuthenticationCallback() {
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

    private fun validate() {
        if (!::mFingerprintManager.isInitialized) {
            throw IllegalStateException(mHostActivity.getString(R.string.msg_fm_not_initialized))
        }
        if (!::mDialogFragment.isInitialized) {
            throw IllegalStateException("Dialog view must be initialized before calling authenticate()")
        }
        if (!deviceHasSecureLock()) {
            throw IllegalStateException(mHostActivity.getString(R.string.msg_no_screen_lock_detected))
        }
        if (!deviceHasFingerprintSensor()) {
            throw IllegalStateException("No fingerprint sensor detected!")
        }
        if (!deviceHasFingerprintsEnrolled()) {
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
            return FingerprintAuth(hostActivity).apply {
                this.mFingerprintManager = FingerprintManagerCompat.from(mHostActivity)
            }
        }

        var validityDuration: Int = DEFAULT_VALIDITY_DURATION
        var invalidateByFingerprintEnrollment: Boolean = true
        var aesKeySize: Int = DEFAULT_AES_KEY_SIZE
    }
}