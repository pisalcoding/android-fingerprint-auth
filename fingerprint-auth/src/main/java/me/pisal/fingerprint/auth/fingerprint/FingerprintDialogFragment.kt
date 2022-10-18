package me.pisal.fingerprint.auth.fingerprint

import android.content.DialogInterface
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.IntDef
import androidx.core.content.ContextCompat
import androidx.core.hardware.fingerprint.FingerprintManagerCompat
import androidx.core.os.CancellationSignal
import androidx.fragment.app.viewModels
import me.pisal.fingerprint.auth.FingerprintAuth.BaseFingerprintDialogFragment
import me.pisal.fingerprint.auth.R


@Suppress("DEPRECATION")
class FingerprintDialogFragment private constructor() :
    BaseFingerprintDialogFragment(R.layout.dialog_fingerprint_auth) {

    private val viewModel: FingerprintDialogViewModel by viewModels()

    override val mHandler: Handler = Handler(Looper.getMainLooper())

    override val cancellationSignal: CancellationSignal = CancellationSignal()

    override val authCallback: FingerprintManagerCompat.AuthenticationCallback =
        object : FingerprintManagerCompat.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: FingerprintManagerCompat.AuthenticationResult?) {
                super.onAuthenticationSucceeded(result)
                dismiss()
                updateFingerprintIcon(STATE_FINGERPRINT_AUTHENTICATED)
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                updateFingerprintIcon(STATE_FINGERPRINT_ERROR)
            }

            override fun onAuthenticationError(errMsgId: Int, errString: CharSequence?) {
                super.onAuthenticationError(errMsgId, errString)
                updateFingerprintIcon(STATE_FINGERPRINT_ERROR)
                dismiss()
            }

            override fun onAuthenticationHelp(helpMsgId: Int, helpString: CharSequence?) {
                super.onAuthenticationHelp(helpMsgId, helpString)
                updateFingerprintIcon(STATE_FINGERPRINT_ERROR)
                requireView().findViewById<TextView>(R.id.fingerprint_error).text = helpString
            }
        }


    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        cancellationSignal.cancel()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        requireView()
            .findViewById<View>(R.id.btn_negative)
            .setOnClickListener {
                dismiss()
            }

        requireView()
            .findViewById<TextView>(R.id.fingerprint_error)
            .text = getString(R.string.fingerprint_dialog_touch_sensor)

        updateFingerprintIcon(STATE_FINGERPRINT)
    }

    override fun onPause() {
        super.onPause()
        cancellationSignal.cancel()
        mHandler.removeCallbacksAndMessages(null)
        dismiss()
    }

    fun updateFingerprintIcon(@State state: Int) {
        if (view == null) return
        val imgFingerprint = requireView().findViewById<ImageView>(R.id.fingerprint_icon)
        val previousState = viewModel.previousState
        val icon = getAssetForTransition(previousState, state) ?: return
        imgFingerprint.setImageDrawable(icon)
        if (shouldAnimateForTransition(previousState, state)) {
            startAnimation(icon)
        }

        viewModel.previousState = state
    }

    /**
     * Starts animating the given icon if it is an [AnimatedVectorDrawable].
     *
     * @param icon A [Drawable] icon asset.
     */
    private fun startAnimation(icon: Drawable) {
        if (icon is AnimatedVectorDrawable) {
            icon.start()
        }
    }

    /**
     * Gets the icon or animation asset that should appear when transitioning between dialog states.
     *
     * @param previousState The previous state for the fingerprint dialog.
     * @param state The new state for the fingerprint dialog.
     * @return A drawable asset to be used for the fingerprint icon.
     */
    private fun getAssetForTransition(@State previousState: Int, @State state: Int): Drawable? {
        val iconRes: Int = if (previousState == STATE_NONE && state == STATE_FINGERPRINT) {
            R.drawable.ic_baseline_fingerprint_24
        } else if (previousState == STATE_FINGERPRINT && state == STATE_FINGERPRINT_ERROR) {
            R.drawable.ic_baseline_error_outline_24
        } else if (previousState == STATE_FINGERPRINT_ERROR && state == STATE_FINGERPRINT) {
            R.drawable.ic_baseline_fingerprint_24
        } else if (previousState == STATE_FINGERPRINT
            && state == STATE_FINGERPRINT_AUTHENTICATED
        ) {
            R.drawable.ic_baseline_fingerprint_24
        } else {
            return null
        }
        return ContextCompat.getDrawable(requireContext(), iconRes)
    }

    /**
     * Checks if the fingerprint icon should animate when transitioning between dialog states.
     *
     * @param previousState The previous state for the fingerprint dialog.
     * @param state The new state for the fingerprint dialog.
     * @return Whether the fingerprint icon should animate.
     */
    private fun shouldAnimateForTransition(@State previousState: Int, @State state: Int): Boolean {
        if (previousState == STATE_NONE && state == STATE_FINGERPRINT) {
            return false
        } else if (previousState == STATE_FINGERPRINT && state == STATE_FINGERPRINT_ERROR) {
            return true
        } else if (previousState == STATE_FINGERPRINT_ERROR && state == STATE_FINGERPRINT) {
            return true
        } else if (previousState == STATE_FINGERPRINT && state == STATE_FINGERPRINT_AUTHENTICATED) {
            return false
        }
        return false
    }

    /**
     * A possible state for the fingerprint dialog.
     */
    @IntDef(STATE_NONE, STATE_FINGERPRINT, STATE_FINGERPRINT_ERROR, STATE_FINGERPRINT_AUTHENTICATED)
    @Retention(
        AnnotationRetention.SOURCE
    )
    internal annotation class State

    companion object {
        /**
         * The dialog has not been initialized.
         */
        const val STATE_NONE = 0

        /**
         * Waiting for the user to authenticate with fingerprint.
         */
        const val STATE_FINGERPRINT = 1

        /**
         * An error or failure occurred during fingerprint authentication.
         */
        const val STATE_FINGERPRINT_ERROR = 2

        /**
         * The user has successfully authenticated with fingerprint.
         */
        const val STATE_FINGERPRINT_AUTHENTICATED = 3

        fun newInstance(): FingerprintDialogFragment {
            return FingerprintDialogFragment()
        }
    }
}