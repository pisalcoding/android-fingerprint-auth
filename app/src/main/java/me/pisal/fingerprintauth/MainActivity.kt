package me.pisal.fingerprintauth

import android.app.KeyguardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.hardware.fingerprint.FingerprintManagerCompat
import androidx.core.widget.doOnTextChanged
import me.pisal.fingerprint.auth.BiometricFailureBlock
import me.pisal.fingerprint.auth.BiometricSuccessBlock
import me.pisal.fingerprint.auth.FingerprintAuth
import me.pisal.fingerprint.auth.credentialskeeper.CredentialsKeeper
import me.pisal.fingerprint.auth.fingerprint.FingerprintDialogFragment
import me.pisal.fingerprint.auth.model.ICredentials
import me.pisal.fingerprintauth.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private var credentialsKeeper: CredentialsKeeper? = null
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupButtons()
    }

    private fun setupButtons() {
        updateInputs(enable = false)
        if (!isKeyguardSecure(this)) {
            showDialog("You have to setup device's lockscreen to use Fingerprint authentication!")
            return
        }
        if (!canUseFingerprint(this)) {
            showDialog("Your device cannot use Fingerprint authentication at the moment!")
            return
        }

        updateInputs(enable = true)
        initListeners()
    }

    private fun updateInputs(enable: Boolean) {
        binding.run {
            btnRead.isEnabled = enable
            // btnSave.isEnabled = enable
            txlUsername.isEnabled = enable
            txlPin.isEnabled = enable
        }
    }

    private fun getCredentialsFromInputs(): Credentials? {
        binding.run {
            val username = txlUsername.editText?.text?.toString()
            val pin = txlPin.editText?.text?.toString()
            if (!username.isNullOrEmpty() && !pin.isNullOrEmpty()) {
                return Credentials(username, pin)
            }
        }
        return null
    }

    private fun populateCredentialsOnInputs(credentials: Credentials) {
        binding.run {
            txlUsername.editText?.setText(credentials.username)
            txlPin.editText?.setText(credentials.pin)
        }
    }

    private fun initListeners() {
        binding.run {
            btnRead.setOnClickListener {
                authenticate({
                    val savedCreds = readCredentials<Credentials>(KEY_CREDENTIALS)
                    if (savedCreds != null) {
                        populateCredentialsOnInputs(savedCreds)
                    } else {
                        showDialog("No credentials have been saved in KeyStore!")
                    }
                }, {
                    showDialog("Failed: $it")
                })
            }

            btnSave.setOnClickListener {
                authenticate({
                    getCredentialsFromInputs()?.let {
                        saveCredentials(KEY_CREDENTIALS, it)
                        showDialog("Saved!")
                    }
                }, {
                    showDialog("Failed: $it")
                })
            }

            txlUsername.editText?.doOnTextChanged { text, _, _, _ ->
                btnSave.isEnabled = !text.isNullOrEmpty() && !txlPin.editText?.text.isNullOrEmpty()
            }

            txlPin.editText?.doOnTextChanged { text, _, _, _ ->
                btnSave.isEnabled =
                    !text.isNullOrEmpty() && !txlUsername.editText?.text.isNullOrEmpty()
            }
        }
    }

    private fun authenticate(
        doOnSuccess: BiometricSuccessBlock,
        doOnFailure: BiometricFailureBlock,
    ) {

        if (credentialsKeeper != null && !shouldAuthenticate(credentialsKeeper)) {
            credentialsKeeper?.let { doOnSuccess(it) }
            return
        }

        val fingerprintFragment = FingerprintDialogFragment.newInstance()
        FingerprintAuth.from(this)
            .withDialogView(fingerprintFragment)
            .doOnSuccess {
                Log.d("FingerprintAuth", "Success")
                credentialsKeeper = this
                doOnSuccess(this)
            }
            .doOnFailure {
                Log.d("FingerprintAuth", "Failed: $it")
                doOnFailure(it)
            }
            .authenticate()
    }

    /////////////////////////////////////////
    //#region Helpers
    /////////////////////////////////////////
    private fun showDialog(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun shouldAuthenticate(credentialsKeeper: CredentialsKeeper?): Boolean {
        if (!canUseFingerprint(this)) {
            return false
        }
        if (credentialsKeeper == null) {
            return true
        }

        return try {
            credentialsKeeper.readCredentials<Credentials>(KEY_CREDENTIALS)
            false
        } catch (t: Throwable) {
            t.printStackTrace()
            true
        }
    }

    private fun isKeyguardSecure(context: Context): Boolean {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return keyguardManager.isKeyguardSecure
    }

    private fun canUseFingerprint(context: Context): Boolean {
        val fpManager = FingerprintManagerCompat.from(context)
        val isKeyguardSecure = isKeyguardSecure(context)
        return fpManager.isHardwareDetected &&
                fpManager.hasEnrolledFingerprints() &&
                isKeyguardSecure
    }
    //#endregion

    companion object {
        const val KEY_CREDENTIALS = "MY_SECRET_CREDENTIALS"
    }
}


data class Credentials(
    val username: String,
    val pin: String,
) : ICredentials
