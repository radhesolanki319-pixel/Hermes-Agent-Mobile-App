package com.jarves.mh.genui

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.R
import com.jarves.mh.ui.MarkdownText
import com.jarves.mh.ui.theme.PocketBlue
import com.jarves.mh.ui.theme.PocketGradientPrimary
import com.jarves.mh.ui.theme.PocketGradientRainbow
import com.jarves.mh.ui.theme.PocketGradientTeal
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Leaf and container composables for the MVP + Phase 2 component sets. Every composable
 * receives the message's [GenUiMessageState] and reports interactions as [GenUiEvent]s —
 * none of them holds form state of its own, so state survives recomposition and LazyColumn
 * recycling. All colors come from MaterialTheme / PocketTheme tokens.
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
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.055f)),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.13f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(16.dp),
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

/** Primary action pill in the violet→pink brand gradient (AMOLED colourful style). */
@Composable
fun GradientPillButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (enabled) PocketGradientPrimary
                else androidx.compose.ui.graphics.Brush.linearGradient(
                    listOf(Color(0xFF3A3A46), Color(0xFF3A3A46)),
                ),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.5f),
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyLarge,
        )
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
        else -> GradientPillButton(label = label, enabled = buttonEnabled, onClick = onClick)
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
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color(0xFFA78BFA),
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            focusedContainerColor = Color.White.copy(alpha = 0.04f),
            unfocusedContainerColor = Color.White.copy(alpha = 0.03f),
            disabledContainerColor = Color.White.copy(alpha = 0.02f),
        ),
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
                shape = RoundedCornerShape(16.dp),
                color = if (isSelected) Color(0xFF7C3AED).copy(alpha = 0.16f) else Color.White.copy(alpha = 0.04f),
                border = BorderStroke(
                    1.dp,
                    if (isSelected) Color(0xFFA78BFA) else MaterialTheme.colorScheme.outlineVariant,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = interactive) { onEvent(GenUiEvent.FieldChanged(id, value)) },
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = null,
                        enabled = interactive,
                        colors = RadioButtonDefaults.colors(
                            selectedColor = Color(0xFFA78BFA),
                            unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
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
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            enabled = interactive,
            colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary),
        )
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
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.055f)),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.13f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(PocketGradientTeal),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Warning, null, tint = Color.White, modifier = Modifier.size(19.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    node.propText("title") ?: stringResource(R.string.genui_approval_title),
                    fontWeight = FontWeight.Bold,
                )
            }
            node.propText("text")?.takeIf { it.isNotBlank() }?.let { MarkdownText(markdown = it) }
            node.propText("command")?.takeIf { it.isNotBlank() }?.let { command ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Black.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, Color(0xFF34D399).copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(Modifier.horizontalScroll(rememberScrollState())) {
                        Text(
                            command,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = Color(0xFF6EE7B7),
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
                    GradientPillButton(
                        label = stringResource(R.string.genui_approve),
                        enabled = enabled,
                        onClick = { onEvent(GenUiEvent.ApprovalDecision(id, true)) },
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = { onEvent(GenUiEvent.ApprovalDecision(id, false)) },
                        Modifier.weight(1f),
                        enabled = enabled,
                    ) { Text(stringResource(R.string.ws_reject)) }
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
            // Rainbow gradient bar on a translucent track (AMOLED colourful style).
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(Color.White.copy(alpha = 0.12f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(value.toFloat().coerceIn(0f, 1f))
                        .height(8.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(PocketGradientRainbow),
                )
            }
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
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF0A0A0E),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.05f))
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
                        tint = if (copied) MaterialTheme.colorScheme.primary else Color(0xFF9AA0A6),
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

// ---------------------------------------------------------------------------
// Phase 2 components. Tone/status props arrive validator-normalized, so the
// views below can trust them and only need a fallback for hand-built nodes.
// ---------------------------------------------------------------------------

/** Shared tone palette for `badge` and `alert` (colourful AMOLED accents). */
private fun genUiToneColor(tone: String?): Color = when (tone) {
    "success" -> Color(0xFF34D399)
    "warning" -> Color(0xFFF59E0B)
    "danger" -> Color(0xFFF87171)
    "info" -> Color(0xFF60A5FA)
    else -> Color(0xFFA6A6B8)
}

@Composable
fun GenUiSelectView(
    node: GenUiNode,
    state: GenUiMessageState,
    enabled: Boolean,
    onEvent: (GenUiEvent) -> Unit,
) {
    val id = node.id ?: return
    val options = node.propOptions("options")
    if (options.isEmpty()) return
    val selected = state.value(id, GenUiActionBridge.defaultValue(node))
    val selectedLabel = options.firstOrNull { it.first == selected }?.second ?: selected
    val interactive = enabled && !state.submitted
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        node.propText("label")?.takeIf { it.isNotBlank() }?.let { label ->
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        Box {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.White.copy(alpha = 0.04f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = interactive) { expanded = true },
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        selectedLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.Default.ArrowDropDown,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            DropdownMenu(
                expanded = expanded && interactive,
                onDismissRequest = { expanded = false },
            ) {
                options.forEach { (value, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            expanded = false
                            onEvent(GenUiEvent.FieldChanged(id, value))
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun GenUiSliderView(
    node: GenUiNode,
    state: GenUiMessageState,
    enabled: Boolean,
    onEvent: (GenUiEvent) -> Unit,
) {
    val id = node.id ?: return
    val min = (node.propNumber("min") ?: 0.0).toFloat()
    val max = (node.propNumber("max") ?: 100.0).toFloat()
    val step = (node.propNumber("step") ?: 0.0).toFloat()
    val currentText = state.value(id, GenUiActionBridge.defaultValue(node))
    val current = (currentText.toFloatOrNull() ?: min).coerceIn(min, max)
    val interactive = enabled && !state.submitted
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            node.propText("label")?.takeIf { it.isNotBlank() }?.let { label ->
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                formatGenUiNumber(current.toDouble()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = current,
            onValueChange = { raw ->
                val snapped = if (step > 0f) {
                    min + kotlin.math.round((raw - min) / step) * step
                } else {
                    raw
                }
                onEvent(GenUiEvent.FieldChanged(id, formatGenUiNumber(snapped.toDouble())))
            },
            valueRange = min..max,
            steps = if (step > 0f) {
                (((max - min) / step).toInt() - 1).coerceAtLeast(0)
            } else {
                0
            },
            enabled = interactive,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFFA78BFA),
                activeTrackColor = Color(0xFF7C3AED),
                inactiveTrackColor = Color.White.copy(alpha = 0.12f),
                disabledThumbColor = Color(0xFFA78BFA).copy(alpha = 0.5f),
                disabledActiveTrackColor = Color(0xFF7C3AED).copy(alpha = 0.5f),
                disabledInactiveTrackColor = Color.White.copy(alpha = 0.08f),
            ),
        )
    }
}

@Composable
fun GenUiTabsView(
    node: GenUiNode,
    payload: GenUiPayload,
    state: GenUiMessageState,
    enabled: Boolean,
    onEvent: (GenUiEvent) -> Unit,
) {
    val id = node.id ?: return
    val labels = node.propTextList("labels")
    if (labels.isEmpty() || node.children.isEmpty()) return
    val lastIndex = minOf(labels.lastIndex, node.children.lastIndex)
    val selected = state.value(id, GenUiActionBridge.defaultValue(node)).toIntOrNull() ?: 0
    val active = selected.coerceIn(0, lastIndex)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            labels.forEachIndexed { index, label ->
                val isActive = index == active
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (isActive) {
                        Color(0xFF7C3AED).copy(alpha = 0.16f)
                    } else {
                        Color.White.copy(alpha = 0.04f)
                    },
                    border = BorderStroke(
                        1.dp,
                        if (isActive) Color(0xFFA78BFA) else MaterialTheme.colorScheme.outlineVariant,
                    ),
                    modifier = Modifier.clickable(enabled = enabled) {
                        onEvent(GenUiEvent.FieldChanged(id, index.toString()))
                    },
                ) {
                    Text(
                        label,
                        Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
        GenUiNodeView(node.children[active], payload, state, enabled, onEvent)
    }
}

@Composable
fun GenUiImageView(node: GenUiNode) {
    val url = node.propText("url") ?: return
    val alt = node.propText("alt")
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url) {
        val loaded = withContext(Dispatchers.IO) { loadGenUiImageBitmap(url) }
        if (loaded != null) bitmap = loaded else failed = true
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val loadedBitmap = bitmap
        when {
            loadedBitmap != null -> Image(
                bitmap = loadedBitmap,
                contentDescription = alt,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp)),
                contentScale = ContentScale.FillWidth,
            )
            failed -> Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.White.copy(alpha = 0.04f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.BrokenImage,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        alt?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.genui_image_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> Box(
                Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.04f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
        }
        node.propText("caption")?.takeIf { it.isNotBlank() }?.let { caption ->
            Text(caption, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Download cap for payload-declared images; anything larger renders as unavailable. */
private const val MAX_GENUI_IMAGE_BYTES = 8 * 1024 * 1024

/**
 * One-shot bitmap fetch for `image` nodes, mirroring the app's existing networking idiom
 * (`HttpURLConnection` with explicit timeouts, as in GitHubClient/AppUpdater — the app has
 * no image-loading library and none is added). The URL was already validated as https-only;
 * redirects are followed by the connection itself. Returns null on any failure.
 */
private fun loadGenUiImageBitmap(url: String): ImageBitmap? = runCatching {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 20_000
        instanceFollowRedirects = true
    }
    try {
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            null
        } else {
            val bytes = connection.inputStream.use { stream ->
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                var total = 0
                var overflow = false
                while (!overflow) {
                    val read = stream.read(chunk)
                    if (read < 0) break
                    total += read
                    if (total > MAX_GENUI_IMAGE_BYTES) {
                        overflow = true
                    } else {
                        buffer.write(chunk, 0, read)
                    }
                }
                if (overflow) null else buffer.toByteArray()
            }
            bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
        }
    } finally {
        connection.disconnect()
    }
}.getOrNull()

@Composable
fun GenUiBadgeView(node: GenUiNode) {
    val text = node.propText("text") ?: return
    val color = genUiToneColor(node.propText("tone"))
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.15f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f)),
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}

@Composable
fun GenUiAlertView(node: GenUiNode) {
    val tone = node.propText("tone") ?: "info"
    val color = genUiToneColor(tone)
    val icon = when (tone) {
        "success" -> Icons.Default.CheckCircle
        "warning" -> Icons.Default.Warning
        "danger" -> Icons.Default.Error
        else -> Icons.Default.Info
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = color.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp)) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                node.propText("title")?.takeIf { it.isNotBlank() }?.let { title ->
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                node.propText("text")?.takeIf { it.isNotBlank() }?.let { text ->
                    MarkdownText(markdown = text)
                }
            }
        }
    }
}

@Composable
fun GenUiTableView(node: GenUiNode) {
    val headers = node.propTextList("headers")
    if (headers.isEmpty()) return
    val rows = node.propMatrix("rows")
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.White.copy(alpha = 0.04f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                Row {
                    headers.forEach { header ->
                        Text(
                            header,
                            Modifier
                                .width(132.dp)
                                .padding(8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                rows.forEach { row ->
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    Row {
                        headers.indices.forEach { index ->
                            Text(
                                row.getOrNull(index) ?: "",
                                Modifier
                                    .width(132.dp)
                                    .padding(8.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GenUiToolCallCardView(node: GenUiNode) {
    val tool = node.propText("tool") ?: return
    val status = node.propText("status") ?: "running"
    val chipBrush: Brush
    val chipIcon: ImageVector
    val statusColor: Color
    val statusText: String
    when (status) {
        "done" -> {
            chipBrush = PocketGradientTeal
            chipIcon = Icons.Default.Check
            statusColor = genUiToneColor("success")
            statusText = stringResource(R.string.genui_status_done)
        }
        "error" -> {
            chipBrush = Brush.linearGradient(listOf(Color(0xFFEF4444), Color(0xFFDB2777)))
            chipIcon = Icons.Default.Close
            statusColor = genUiToneColor("danger")
            statusText = stringResource(R.string.genui_status_error)
        }
        else -> {
            chipBrush = PocketGradientPrimary
            chipIcon = Icons.Default.Build
            statusColor = Color(0xFFA78BFA)
            statusText = stringResource(R.string.genui_status_running)
        }
    }
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.055f)),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.13f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(chipBrush),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(chipIcon, null, tint = Color.White, modifier = Modifier.size(19.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    tool,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    statusText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = statusColor,
                )
            }
            node.propText("summary")?.takeIf { it.isNotBlank() }?.let { summary ->
                MarkdownText(markdown = summary)
            }
        }
    }
}

@Composable
fun GenUiFilePreviewView(node: GenUiNode) {
    val name = node.propText("name") ?: return
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.White.copy(alpha = 0.04f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.07f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    genUiFileIcon(name),
                    null,
                    tint = PocketBlue,
                    modifier = Modifier.size(21.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = listOfNotNull(
                    node.propText("size")?.takeIf { it.isNotBlank() },
                    node.propText("type")?.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                if (meta.isNotBlank()) {
                    Text(
                        meta,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Icon for a `file_preview`, chosen purely from the declared file name's extension. */
private fun genUiFileIcon(name: String): ImageVector =
    when (name.substringAfterLast('.', "").lowercase()) {
        "png", "jpg", "jpeg", "gif", "webp", "svg", "bmp" -> Icons.Default.Image
        "kt", "java", "js", "ts", "tsx", "jsx", "py", "json", "html", "css", "xml",
        "sh", "go", "rs", "c", "h", "cpp" -> Icons.Default.Code
        "pdf" -> Icons.Default.PictureAsPdf
        "zip", "tar", "gz", "xz", "7z", "rar" -> Icons.Default.FolderZip
        "mp3", "wav", "ogg", "flac", "m4a" -> Icons.Default.AudioFile
        "mp4", "mov", "mkv", "webm" -> Icons.Default.VideoFile
        else -> Icons.Default.InsertDriveFile
    }

@Composable
fun GenUiTimelineView(node: GenUiNode) {
    val steps = node.propObjectList("steps")
    if (steps.isEmpty()) return
    Column {
        steps.forEachIndexed { index, step ->
            val title = (step["title"] as? GenUiValue.Text)?.value ?: return@forEachIndexed
            val text = (step["text"] as? GenUiValue.Text)?.value
            val status = (step["status"] as? GenUiValue.Text)?.value ?: "upcoming"
            val isLast = index == steps.lastIndex
            Row {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    when (status) {
                        "done" -> Box(
                            Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF34D399)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.Check,
                                null,
                                tint = Color(0xFF022C22),
                                modifier = Modifier.size(15.dp),
                            )
                        }
                        "current" -> Box(
                            Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFF472B6).copy(alpha = 0.12f))
                                .border(2.dp, Color(0xFFF472B6), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFF472B6)),
                            )
                        }
                        else -> Box(
                            Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .border(
                                    2.dp,
                                    MaterialTheme.colorScheme.outlineVariant,
                                    CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                (index + 1).toString(),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (!isLast) {
                        Box(
                            Modifier
                                .width(2.dp)
                                .height(26.dp)
                                .background(Color.White.copy(alpha = 0.12f)),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.padding(top = 2.dp)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (status == "current") {
                            FontWeight.SemiBold
                        } else {
                            FontWeight.Normal
                        },
                        color = if (status == "upcoming") {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    text?.takeIf { it.isNotBlank() }?.let { detail ->
                        Text(
                            detail,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
