package com.labteto.dshmobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.labteto.dshmobile.connection.ConnectionManager
import com.labteto.dshmobile.connection.HarnessSessionStore
import com.labteto.dshmobile.connection.HostsStore
import com.labteto.dshmobile.data.SessionStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Hilt entry point for the process-scoped [SessionStore].
 *
 * The store is a [javax.inject.Singleton], not a [androidx.lifecycle.ViewModel] — it outlives every
 * screen and owns the live mirror of the connected harness — so composables resolve it through an
 * app-scoped entry point rather than `hiltViewModel()`. One declaration serves every screen;
 * duplicating it per file would mean several Hilt-generated accessors for one singleton.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SessionStoreEntryPoint {
    fun sessionStore(): SessionStore
    fun hostsStore(): HostsStore
    fun connectionManager(): ConnectionManager
    fun harnessSessionStore(): HarnessSessionStore
}

/** Resolves the process-scoped [SessionStore] once per composition. */
@Composable
internal fun rememberSessionStore(): SessionStore {
    val context = LocalContext.current.applicationContext
    return remember {
        EntryPointAccessors.fromApplication(context, SessionStoreEntryPoint::class.java).sessionStore()
    }
}

/**
 * Resolves the process-scoped [HostsStore] once per composition.
 *
 * For the handful of preferences a screen owns outright — drawer sort order, for instance — where
 * routing through a ViewModel would add a layer that only forwards.
 */
@Composable
internal fun rememberHostsStore(): HostsStore {
    val context = LocalContext.current.applicationContext
    return remember {
        EntryPointAccessors.fromApplication(context, SessionStoreEntryPoint::class.java).hostsStore()
    }
}

/**
 * Resolves the process-scoped [ConnectionManager] once per composition.
 *
 * For the host switcher, which drives [ConnectionManager.connect] directly — switching backends is
 * exactly the manager's atomic disconnect-then-connect, and no ViewModel is needed to say so.
 */
@Composable
internal fun rememberConnectionManager(): ConnectionManager {
    val context = LocalContext.current.applicationContext
    return remember {
        EntryPointAccessors.fromApplication(context, SessionStoreEntryPoint::class.java).connectionManager()
    }
}

/**
 * Resolves the process-scoped [HarnessSessionStore] once per composition.
 *
 * For the host switcher's add form, which spends a pasted launch token on the new backend — the
 * one-step sign-in the connect screen has always offered.
 */
@Composable
internal fun rememberHarnessSessionStore(): HarnessSessionStore {
    val context = LocalContext.current.applicationContext
    return remember {
        EntryPointAccessors.fromApplication(context, SessionStoreEntryPoint::class.java).harnessSessionStore()
    }
}
