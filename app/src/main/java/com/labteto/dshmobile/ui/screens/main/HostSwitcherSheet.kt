package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.connection.ConnectionPhase
import com.labteto.dshmobile.connection.HostConfig
import com.labteto.dshmobile.connection.HostsStore
import com.labteto.dshmobile.connection.belongsTo
import com.labteto.dshmobile.connection.parseHostInput
import com.labteto.dshmobile.core.wire.HarnessLaunchLine
import com.labteto.dshmobile.data.SessionStore
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.screens.connect.InkTextField
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/**
 * The backend switcher: every remembered harness, one tap away — plus the form that adds a new one
 * without leaving the chat.
 *
 * Switching is the manager's own atomic disconnect-then-connect — the SessionStore's host-change
 * reset clears the old host's mirror, and the new connection's baseline lands the app back on the
 * session last opened on that host. Nothing here needs to orchestrate any of that; it only picks
 * the target.
 *
 * The add form deliberately accepts the same shapes the connect screen's host field does — a bare
 * host, `host:port`, or the whole startup line `dsh web` prints, token included. A pasted token is
 * spent on the spot ([HarnessSessionStore.pair]) so a harness that demands a sign-in is signed in
 * as part of being added, instead of connecting into a 401. Adding is connecting: a backend that
 * was never reached cannot be remembered, so the form's Connect both remembers and switches.
 *
 * Rows are filtered to the active connect mode's trust domain (loopback / lan / relay) the same
 * way every per-mode list on the connect screen is: crossing modes mid-switch would offer a
 * loopback address a subnet check can never pass, or a LAN host under "this phone"'s promise that
 * nothing leaves the device.
 */
@Composable
internal fun HostSwitcherSheet(
    hostsStore: HostsStore,
    store: SessionStore,
    onDismiss: () -> Unit,
) {
    val colors = DsTheme.colors
    val hosts by hostsStore.hosts.collectAsState(initial = emptyList())
    val settings by hostsStore.settings.collectAsState(initial = null)
    val connectionState by store.connectionState.collectAsState()
    val activeId = connectionState.host?.id
    val mode = settings?.connectMode
    // Non-null while the rename dialog for this backend is open.
    var renaming by remember { mutableStateOf<HostConfig?>(null) }

    DsBottomSheet(
        title = stringResource(R.string.hostswitcher_title),
        onDismiss = onDismiss,
    ) {
        val visible = if (mode == null) hosts else hosts.filter { it.belongsTo(mode) }
        if (visible.isEmpty()) {
            Text(
                text = stringResource(R.string.hostswitcher_empty),
                style = DsType.std14,
                color = colors.labelSecondary,
                modifier = Modifier.padding(vertical = DsSpacing.comfortable),
            )
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                items(visible, key = { it.id }) { host ->
                    HostSwitcherRow(
                        host = host,
                        active = host.id == activeId,
                        connecting = host.id == activeId &&
                            connectionState.phase == ConnectionPhase.CONNECTING,
                        onSwitch = {
                            if (host.id != activeId) {
                                onDismiss()
                                // Not launched here on purpose: the sheet's scope dies with the
                                // dismissal above, and the switch must outlive it — see
                                // SessionStore.switchHost.
                                store.switchHost(host)
                            }
                        },
                        onRename = { renaming = host },
                        onForget = {
                            store.forgetHost(host.id)
                        },
                    )
                }
            }
        }
        HostSwitcherAddForm(
            store = store,
            onAdded = onDismiss,
        )
    }

    renaming?.let { target ->
        RenameDialog(
            initial = target.name,
            title = stringResource(R.string.hostswitcher_rename),
            onDismiss = { renaming = null },
            onConfirm = { name ->
                renaming = null
                if (name.isNotBlank() && name != target.name) {
                    // The store's own scope, not the sheet's — same outlives-the-sheet rule as
                    // switchHost. The chip and every row update through the hosts flow.
                    store.renameHost(target.id, name)
                }
            },
        )
    }
}

/**
 * The collapsed "add backend" row that expands into the address form.
 *
 * Mirrors the connect screen's parsing ([HarnessLaunchLine] then [parseHostInput]) so the same
 * pasted strings work in both places. No subnet guard here: the address was typed deliberately
 * into this form, and the connection loop's handshake reports anything unreachable — the guard
 * exists on the connect screen to short-circuit *scanned* guesses, not typed intent.
 */
@Composable
private fun HostSwitcherAddForm(
    store: SessionStore,
    onAdded: () -> Unit,
) {
    val colors = DsTheme.colors
    var open by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("3080") }
    var invalid by remember { mutableStateOf(false) }

    if (!open) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(DsShapes.row)
                .clickable { open = true }
                .padding(horizontal = DsSpacing.small, vertical = DsSpacing.compact),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            Icon(
                Icons.Outlined.Add,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.hostswitcher_add),
                style = DsType.std14,
                color = colors.accent,
            )
        }
        return
    }

    val submit: () -> Unit = {
        val line = HarnessLaunchLine.parse(address)
        val parsed = parseHostInput(line?.baseUrl ?: address)
        // Same precedence as the connect screen: a port the address itself names wins over the
        // port field; an explicit scheme implies its default port; only a bare host reads the
        // field — which may still hold the default from a different harness.
        val portInt = when {
            parsed?.port != null -> parsed.port
            parsed?.useTls == true -> 443
            parsed?.useTls == false -> 80
            else -> port.filter { it.isDigit() }.toIntOrNull()
        }
        if (parsed == null || portInt == null || portInt !in 1..65535) {
            invalid = true
        } else {
            open = false
            onAdded()
            // On the store's own scope, not this sheet's: see SessionStore.addHost.
            store.addHost(
                host = parsed.host,
                port = portInt,
                useTls = parsed.useTls ?: (portInt == 443),
                basePath = parsed.basePath,
                launchToken = line?.token,
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
        val clipboard = LocalClipboardManager.current
        Row(verticalAlignment = Alignment.Bottom) {
            InkTextField(
                value = address,
                onValueChange = { address = it; invalid = false },
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.hostswitcher_address),
                placeholder = "10.0.2.2:3080",
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                trailing = {
                    DsIconButton(
                        icon = Icons.Outlined.ContentPaste,
                        contentDescription = stringResource(R.string.hostswitcher_paste),
                        onClick = {
                            clipboard.getText()?.text?.trim()?.takeIf { it.isNotEmpty() }?.let {
                                address = it; invalid = false
                            }
                        },
                        tint = colors.labelTertiary,
                    )
                },
            )
            Spacer(Modifier.width(DsSpacing.compact))
            InkTextField(
                value = port,
                onValueChange = { port = it.filter { c -> c.isDigit() } },
                modifier = Modifier.width(96.dp),
                label = stringResource(R.string.hostswitcher_port),
                mono = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() }),
            )
        }
        if (invalid) {
            Text(
                text = stringResource(R.string.hostswitcher_invalid),
                style = DsType.caption11,
                color = colors.warnLabel,
            )
        } else {
            Text(
                text = stringResource(R.string.hostswitcher_add_hint),
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
        }
        DsButton(
            text = stringResource(R.string.connect_button),
            onClick = { submit() },
            enabled = address.isNotBlank(),
            variant = DsButtonVariant.Info,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun HostSwitcherRow(
    host: HostConfig,
    active: Boolean,
    connecting: Boolean,
    onSwitch: () -> Unit,
    onRename: () -> Unit,
    onForget: () -> Unit,
) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DsShapes.row)
            .background(if (active) colors.bgLayer3 else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(enabled = !active, onClick = onSwitch)
            .padding(horizontal = DsSpacing.small, vertical = DsSpacing.compact),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        Icon(
            Icons.Outlined.Dashboard,
            contentDescription = null,
            tint = colors.labelTertiary,
            modifier = Modifier.size(18.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = host.name,
                style = DsType.std14,
                color = colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = host.displayAddress,
                style = DsType.caption11,
                color = colors.labelCaption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (host.isRelay) {
            DsPill(text = stringResource(R.string.hostswitcher_relay))
        }
        if (active) {
            DsPill(text = stringResource(if (connecting) R.string.hostswitcher_connecting else R.string.hostswitcher_current))
        }
        Icon(
            Icons.Outlined.Edit,
            contentDescription = stringResource(R.string.hostswitcher_rename),
            tint = colors.labelTertiary,
            modifier = Modifier
                .size(18.dp)
                .clip(DsShapes.chip)
                .clickable(onClick = onRename)
                .padding(2.dp),
        )
        Icon(
            Icons.Outlined.Delete,
            contentDescription = stringResource(R.string.hostswitcher_forget),
            tint = colors.labelTertiary,
            modifier = Modifier
                .size(18.dp)
                .clip(DsShapes.chip)
                .clickable(onClick = onForget)
                .padding(2.dp),
        )
    }
}
