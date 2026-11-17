package com.jarves.mh.genui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.R

/**
 * Layers 5 + 8 — Dynamic UI Renderer.
 *
 * Renders a validated [GenUiPayload] as native Compose inside the chat message flow.
 * Dispatch is a closed `when` over the component allowlist: unknown types render nothing
 * (validation already rejects them; this is defense in depth), and no payload string is ever
 * treated as code. When [enabled] is false (read-only chats) components render disabled and
 * the caller passes a no-op event handler.
 */
@Composable
fun GenerativeUiView(
    payload: GenUiPayload,
    state: GenUiMessageState,
    enabled: Boolean,
    onEvent: (GenUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GenUiNodeView(payload.root, payload, state, enabled, onEvent)
        if (state.submitted) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Check,
                    null,
                    Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.tertiary,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.genui_response_sent),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun GenUiNodeView(
    node: GenUiNode,
    payload: GenUiPayload,
    state: GenUiMessageState,
    enabled: Boolean,
    onEvent: (GenUiEvent) -> Unit,
) {
    when (node.type) {
        GenUiComponentTypes.TEXT -> GenUiTextView(node)
        GenUiComponentTypes.HEADING -> GenUiHeadingView(node)
        GenUiComponentTypes.CARD -> GenUiCardView(node, payload, state, enabled, onEvent)
        GenUiComponentTypes.ROW -> GenUiRowView(node, payload, state, enabled, onEvent)
        GenUiComponentTypes.COLUMN -> GenUiColumnView(node, payload, state, enabled, onEvent)
        GenUiComponentTypes.BUTTON -> GenUiButtonView(node, payload, state, enabled, onEvent)
        GenUiComponentTypes.INPUT -> GenUiInputView(node, state, enabled, onEvent, multiline = false)
        GenUiComponentTypes.TEXTAREA -> GenUiInputView(node, state, enabled, onEvent, multiline = true)
        GenUiComponentTypes.RADIO_GROUP -> GenUiRadioGroupView(node, state, enabled, onEvent)
        GenUiComponentTypes.CHECKBOX -> GenUiCheckboxView(node, state, enabled, onEvent)
        GenUiComponentTypes.APPROVAL_CARD -> GenUiApprovalCardView(node, state, enabled, onEvent)
        GenUiComponentTypes.PROGRESS -> GenUiProgressView(node)
        GenUiComponentTypes.CODE_BLOCK -> GenUiCodeBlockView(node)
        GenUiComponentTypes.DIVIDER -> GenUiDividerView()
        GenUiComponentTypes.SPINNER -> GenUiSpinnerView(node)
        GenUiComponentTypes.SELECT -> GenUiSelectView(node, state, enabled, onEvent)
        GenUiComponentTypes.SLIDER -> GenUiSliderView(node, state, enabled, onEvent)
        GenUiComponentTypes.TABS -> GenUiTabsView(node, payload, state, enabled, onEvent)
        GenUiComponentTypes.IMAGE -> GenUiImageView(node)
        GenUiComponentTypes.BADGE -> GenUiBadgeView(node)
        GenUiComponentTypes.ALERT -> GenUiAlertView(node)
        GenUiComponentTypes.TABLE -> GenUiTableView(node)
        GenUiComponentTypes.TOOL_CALL_CARD -> GenUiToolCallCardView(node)
        GenUiComponentTypes.FILE_PREVIEW -> GenUiFilePreviewView(node)
        GenUiComponentTypes.TIMELINE -> GenUiTimelineView(node)
        else -> Unit
    }
}
