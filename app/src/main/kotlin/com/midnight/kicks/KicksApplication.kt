package com.midnight.kicks

import android.app.Application
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
class KicksApplication : Application()
