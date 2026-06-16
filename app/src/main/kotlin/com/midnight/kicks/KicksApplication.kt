package com.midnight.kicks

import android.app.Application
import com.midnight.kuira.sdk.walletruntime.SessionLock
import com.midnight.kuira.sdk.walletruntime.WalletForegroundService
import dagger.hilt.android.HiltAndroidApp

/**
 * Hilt entry point for Midnight Kicks.
 *
 * Registered as `android:name=".KicksApplication"` in the manifest.
 * Required for the `dapp-ui` panels (sigil + wallet pills in
 * [com.midnight.kuira.dapp.PanelBar]) to resolve their
 * `@HiltViewModel`-annotated VMs at composition.
 */
@HiltAndroidApp
class KicksApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Session auto-lock (#14): install the app-level lock triggers
        // (background + screen-off). Foreground idle is wired per-Activity via
        // onUserInteraction -> sessionLock.onUserActivity().
        SessionLock.attach(this)
        // Wallet foreground service (#261-264, generalizing #235): keep a backgrounded
        // wallet operation (send / dust-registration / contract call) OR a sync alive,
        // surface its progress notification, and fire the dismissible finalization push
        // on completion; tears down on foreground-idle / completion / session lock.
        WalletForegroundService.attach(this)
    }
}
