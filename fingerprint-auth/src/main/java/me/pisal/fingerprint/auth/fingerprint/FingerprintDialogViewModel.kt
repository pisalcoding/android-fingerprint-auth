package me.pisal.fingerprint.auth.fingerprint

import androidx.lifecycle.ViewModel

class FingerprintDialogViewModel : ViewModel() {

    @FingerprintDialogFragment.State
    var previousState: Int = FingerprintDialogFragment.STATE_NONE
}