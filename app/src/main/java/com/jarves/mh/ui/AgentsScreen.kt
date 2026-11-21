package com.jarves.mh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.R
import com.jarves.mh.model.AgentKind

/**
 * v56: the Agents tab — the old-style home for agents (removed in v48,
 * restored at the user's order). Each installable agent gets a card with
 * its status and a clear Download & Install button; the built-in App Agent
 * is listed first and is always ready. Install/update run through the same
 * MainViewModel functions the pre-v48 Agent screen used.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsScreen(
    state: AppUiState,
    onInstallAgent: (AgentKind) -> Unit,
    onUseAgent: (AgentKind) -> Unit,
    onUpdateAgent: (AgentKind) -> Unit,
    onCheckUpdates: () -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.agents_screen_title)) }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.agents_screen_subtitle),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            listOf(AgentKind.NATIVE, AgentKind.HERMES, AgentKind.CLAUDE_CODE).forEach { kind ->
                AgentInstallCard(
                    kind = kind,
                    state = state,
                    onInstallAgent = onInstallAgent,
                    onUseAgent = onUseAgent,
                    onUpdateAgent = onUpdateAgent,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = onCheckUpdates,
                    enabled = !state.agentUpdatesChecking && state.agentUpdating == null,
                ) {
                    Text(
                        if (state.agentUpdatesChecking) {
                            stringResource(R.string.agents_checking)
                        } else {
                            stringResource(R.string.agent_check_updates)
                        },
                    )
                }
                state.agentUpdateMessage?.let { message ->
                    Spacer(Modifier.width(8.dp))
                    Text(message, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state.agentInstalling == null) {
                state.agentMessage?.let { message ->
                    Text(message, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun AgentInstallCard(
    kind: AgentKind,
    state: AppUiState,
    onInstallAgent: (AgentKind) -> Unit,
    onUseAgent: (AgentKind) -> Unit,
    onUpdateAgent: (AgentKind) -> Unit,
) {
    val installedVersion = state.installedAgentVersions[kind]
    val installed = kind == AgentKind.NATIVE || !installedVersion.isNullOrBlank()
    val installing = state.agentInstalling == kind
    val updating = state.agentUpdating == kind
    val busy = installing || updating
    val update = state.agentUpdates[kind]
    val accent = when (kind) {
        AgentKind.HERMES -> Color(0xFF0F766E)
        AgentKind.CLAUDE_CODE -> Color(0xFFD97757)
        AgentKind.ANTIGRAVITY -> Color(0xFF7C3AED)
        AgentKind.NATIVE -> Color(0xFF2563EB)
    }
    val monogram = when (kind) {
        AgentKind.HERMES -> "HA"
        AgentKind.CLAUDE_CODE -> "CC"
        AgentKind.ANTIGRAVITY -> "AG"
        AgentKind.NATIVE -> "AA"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = accent, modifier = Modifier.size(42.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Text(monogram, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(kind.titleRes()), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(
                        stringResource(kind.subtitleRes()),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (kind == state.agentKind) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)) {
                        Text(
                            stringResource(R.string.agents_active),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                when {
                    kind == AgentKind.NATIVE -> stringResource(R.string.agents_builtin)
                    installed -> stringResource(R.string.agents_installed, installedVersion.orEmpty())
                    else -> stringResource(R.string.agents_not_installed)
                },
                fontSize = 13.sp,
                color = if (installed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                when (kind) {
                    AgentKind.HERMES -> stringResource(R.string.agents_hermes_source)
                    AgentKind.CLAUDE_CODE -> stringResource(R.string.agents_claude_source)
                    else -> stringResource(R.string.agents_native_source)
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (busy) {
                Spacer(Modifier.height(12.dp))
                if (installing) {
                    LinearProgressIndicator(progress = { state.agentProgress }, modifier = Modifier.fillMaxWidth())
                    state.agentMessage?.let { message ->
                        Spacer(Modifier.height(6.dp))
                        Text(message, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    if (state.agentUpdateIndeterminate) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(progress = { state.agentUpdateProgress }, modifier = Modifier.fillMaxWidth())
                    }
                    state.agentUpdateMessage?.let { message ->
                        Spacer(Modifier.height(6.dp))
                        Text(message, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!installed && !installing) {
                    Button(
                        onClick = { onInstallAgent(kind) },
                        enabled = state.agentInstalling == null && state.agentUpdating == null,
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.agents_download_install))
                    }
                }
                // v57: a stale Hermes (purana version pre-installed) gets a
                // reinstall button — install replaces it with the current
                // GitHub bundle instead of keeping the broken old one.
                if (kind == AgentKind.HERMES && installed && !installing &&
                    installedVersion != com.jarves.mh.runtime.RuntimeInstaller.HERMES_VERSION
                ) {
                    Button(
                        onClick = { onInstallAgent(kind) },
                        enabled = state.agentInstalling == null && state.agentUpdating == null,
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.agents_reinstall_hermes, com.jarves.mh.runtime.RuntimeInstaller.HERMES_VERSION))
                    }
                }
                if (installed && kind != state.agentKind) {
                    Button(onClick = { onUseAgent(kind) }, enabled = !state.isRunning && !busy) {
                        Text(stringResource(R.string.agents_use_in_chat))
                    }
                }
                if (installed && update != null && !updating) {
                    OutlinedButton(
                        onClick = { onUpdateAgent(kind) },
                        enabled = state.agentUpdating == null && state.agentInstalling == null,
                    ) {
                        Text(stringResource(R.string.agents_update_to, update.latestVersion))
                    }
                }
            }
        }
    }
}
