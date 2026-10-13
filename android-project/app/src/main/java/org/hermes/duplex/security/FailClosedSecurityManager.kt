package org.hermes.duplex.security

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

object FailClosedSecurityManager {
    private const val TAG = "FailClosedSecurity"
    private val isSecureState = AtomicBoolean(false)
    private var onSecurityBreachCallback: (() -> Unit)? = null

    fun initialize(onBreach: () -> Unit) {
        onSecurityBreachCallback = onBreach
        isSecureState.set(true)
    }

    fun validateToken(token: String?): Boolean {
        if (token.isNullOrBlank() || token.length < 8) {
            triggerFailClosed("Invalid or missing bridge token")
            return false
        }
        return true
    }

    fun onHeartbeatMissed(consecutiveMisses: Int) {
        if (consecutiveMisses >= 2) {
            triggerFailClosed("Missed 2 consecutive bridge pings (30s timeout)")
        }
    }

    fun triggerFailClosed(reason: String) {
        if (isSecureState.getAndSet(false)) {
            Log.e(TAG, "🚨 FAIL-CLOSED TRIGGERED: \$reason. Terminating audio.")
            onSecurityBreachCallback?.invoke()
        }
    }

    fun isPermitted(): Boolean = isSecureState.get()
}
