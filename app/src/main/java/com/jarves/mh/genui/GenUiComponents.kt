package com.jarves.mh.genui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.R
import com.jarves.mh.ui.MarkdownText
import com.jarves.mh.ui.theme.PocketOrange
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Leaf and container composables for the MVP component set. Every composable receives the
 * message's [GenUiMessageState] and reports interactions as [GenUiEvent]s — none of them
 * holds form state of its own, so state survives recomposition and LazyColumn recycling.
 * All colors come from MaterialTheme / PocketTheme tokens.
 */

@Composable
fun GenUiTextView(node: GenUiNode) {
    val text = node.propText("text") ?: return
    MarkdownText(markdown = text)
}

@Composable
fun GenUiHeadingView(node: GenUiNode) {
    val text = node.propText("text") ?: return
    val style = when (node.propNumber("level")?.toInt() ?: 2) {
        1 -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp)
        2 -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 17.sp)
        else -> MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
    Text(text = text, style = style, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
fun GenUiCardView(
    node: GenUiNode,
    payload: GenUiPayload,
    state: GenUiMessageState,
    enabled: Boolean,
    onEvent: (GenUiEvent) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            node.propText("title")?.takeIf { it.isNotBlank() }?.let { title ->
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            node.children.forEach { child -> GenUiNodeView(child, payload, state, enabled, onEvent) }
        }
    }
}

@Composable
fun GenUiRowView(
    node: GenUiNode,
    payload: GenUiPayload,
    state: GenUiMessageState,
    enabled: Boolean,
    onEvent: (GenUiEvent) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        node.children.forEach { child -> GenUiNodeView(child, payload, state, enabled, onEvent) }
    }
}

@Composable
fun GenUiColumnView(
    node: GenUiNode,
    payload: GenUiPayload,
    state: GenUiMessageState,
    enabled: Boolean,
    onEvent: (GenUiEvent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        node.children.forEach { child -> GenUiNodeView(child, payload, state, enabled, onEvent) }
    }
}

@Composable
fun GenUiButtonView(
    node: GenUiNode,
    payload: GenUiPayload,
    state: GenUiMessageState,
    enabled: Boolean,
    onEvent: (GenUiEvent) -> Unit,
) {
    val action = payload.actionById(node.propText("action"))
    val isSubmit = action?.type == GenUiActionTypes.SUBMIT_FORM
    val buttonEnabled = enabled && action != null && !(isSubmit && state.submitted)
    val label = node.propText("label") ?: action?.label ?: stringResource(R.string.genui_ok)
    val onClick: () -> Unit = { if (action != null) onEvent(GenUiEvent.ActionInvoked(action, node.id)) }
    when (node.propText("variant")) {
        "outline", "secondary" -> OutlinedButton(onClick = onClick, enabled = buttonEnabled) { Text(label) }
        else -> Button(onClick = onClick, enabled = buttonEnabled) { Text(label) }
    }
}

@Composable
fun GenUiInputView(
    node: GenUiNode,
    state: GenUiMessageState,
    enabled: Boolean,
    onEvent: (GenUiEvent) -> Unit,
    multiline: Boolean,
) {
    val id = node.id ?: return
    val label = node.propText("label")
    val placeholder = node.propText("placeholder")
    OutlinedTextField(
        value = state.value(id, GenUiActionBridge.defaultValue(node)),
        onValueChange = { onEvent(GenUiEvent.FieldChanged(id, it)) },
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it) } },
        enabled = enabled && !state.submitted,
        singleLine = !multiline,
        minLines = if (multiline) node.propNumber("min_lines")?.toInt()?.coerceIn(2, 8) ?: 3 else 1,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun GenUiRadioGroupView(
    node: GenUiNode,
    state: GenUiMessageState,
    enabled: Boolean,
    onEvent: (GenUiEvent) -> Unit,
) {
    val id = node.id ?: return
    val options = node.propOptions("options")
    if (options.isEmpty()) return
    val selected = state.value(id, GenUiActionBridge.defaultValue(node))
    val interactive = enabled && !state.submitted
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        node.propText("label")?.takeIf { it.isNotBlank() }?.let { label ->
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        options.forEach { (value, label) ->
            val isSelected = value == selected
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(
                    1.dp,
                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = interactive) { onEvent(GenUiEvent.FieldChanged(id, value)) },
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = isSelected, onClick = null, enabled = interactive)
                    Spacer(Modifier.width(8.dp))
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
fun GenUiCheckboxView(
    node: GenUiNode,
    state: GenUiMessageState,
    enabled: Boolean,
    onEvent: (GenUiEvent) -> Unit,
) {
    val id = node.id ?: return
    val checked = state.value(id, GenUiActionBridge.defaultValue(node)) == "true"
    val interactive = enabled && !state.submitted
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = interactive) { onEvent(GenUiEvent.FieldChanged(id, (!checked).toString())) },
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = interactive)
        Spacer(Modifier.width(4.dp))
        Text(node.propText("label") ?: "", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun GenUiApprovalCardView(
    node: GenUiNode,
    state: GenUiMessageState,
    enabled: Boolean,
    onEvent: (GenUiEvent) -> Unit,
) {
    val id = node.id ?: return
    val decision = state.approvalDecisions[id]
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    node.propText("title") ?: stringResource(R.string.genui_approval_title),
                    fontWeight = FontWeight.Bold,
                )
            }
            node.propText("text")?.takeIf { it.isNotBlank() }?.let { MarkdownText(markdown = it) }
            node.propText("command")?.takeIf { it.isNotBlank() }?.let { command ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(Modifier.horizontalScroll(rememberScrollState())) {
                        Text(
                            command,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            if (decision != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (decision) Icons.Default.Check else Icons.Default.Close,
                        null,
                        Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(if (decision) R.string.genui_approved else R.string.genui_rejected),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onEvent(GenUiEvent.ApprovalDecision(id, false)) },
                        Modifier.weight(1f),
                        enabled = enabled,
                    ) { Text(stringResource(R.string.ws_reject)) }
                    Button(
                        onClick = { onEvent(GenUiEvent.ApprovalDecision(id, true)) },
                        Modifier.weight(1f),
                        enabled = enabled,
                    ) { Text(stringResource(R.string.genui_approve)) }
                }
            }
        }
    }
}

@Composable
fun GenUiProgressView(node: GenUiNode) {
    val value = node.propNumber("value") ?: node.propNumber("percent")?.div(100.0)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (value != null) {
            LinearProgressIndicator(
                progress = { value.toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        node.propText("label")?.takeIf { it.isNotBlank() }?.let { label ->
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun GenUiCodeBlockView(node: GenUiNode) {
    val code = node.propText("code") ?: return
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF14171E),
        border = BorderStroke(1.dp, Color(0xFF2A2E39)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1C202B))
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = node.propText("language")?.takeIf { it.isNotBlank() } ?: stringResource(R.string.ws_md_code_label),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF9AA0A6),
                    fontFamily = FontFamily.Monospace,
                )
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(code))
                        copied = true
                        scope.launch {
                            delay(2000)
                            copied = false
                        }
                    },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.genui_copy),
                        tint = if (copied) PocketOrange else Color(0xFF9AA0A6),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Box(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    code,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = Color(0xFFE6EDF3),
                )
            }
        }
    }
}

@Composable
fun GenUiDividerView() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

@Composable
fun GenUiSpinnerView(node: GenUiNode) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        node.propText("label")?.takeIf { it.isNotBlank() }?.let { label ->
            Spacer(Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
