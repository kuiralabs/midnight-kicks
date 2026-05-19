package com.midnight.kicks.di

import android.content.Context
import com.midnight.kicks.MatchStore
import com.midnight.kicks.MatchStoreBackupProvider
import com.midnight.kuira.dapp.backup.AppDataBackupProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for Kicks's storage + cloud-backup wiring.
 *
 * Provides:
 *  - [MatchStore] as a SingletonComponent-scoped instance over
 *    `@ApplicationContext`. Shared between [com.midnight.kicks.KicksActivity]
 *    (reads for the isResume gate + hasActiveSession indicator) and
 *    [com.midnight.kicks.MatchManager] (writes on every
 *    deploy/join/commit/SD success). Both layers must see the same
 *    instance — without the singleton scope they'd hold separate
 *    [androidx.security.crypto.EncryptedSharedPreferences] handles and
 *    drift on the unique-key check in EncryptedSharedPreferences.
 *  - [AppDataBackupProvider] bound to [MatchStoreBackupProvider]. The
 *    SDK's `Optional<AppDataBackupProvider>` injection in
 *    `SigilPanelViewModel` becomes `Optional.of(...)` thanks to this
 *    binding, so a sigil backup automatically includes match state
 *    and a sigil restore unpacks it.
 *
 * Two modules in one file because `@Provides` (the [MatchStore]
 * factory) and `@Binds` (the [AppDataBackupProvider] interface
 * mapping) have different Hilt compile-time requirements — `@Binds`
 * has to live on an abstract method in an abstract class / interface.
 */
@Module
@InstallIn(SingletonComponent::class)
object KicksStorageModule {

    @Provides
    @Singleton
    fun provideMatchStore(
        @ApplicationContext context: Context,
    ): MatchStore = MatchStore(context)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class KicksBackupBindings {

    @Binds
    @Singleton
    abstract fun bindAppDataBackupProvider(
        impl: MatchStoreBackupProvider,
    ): AppDataBackupProvider
}
