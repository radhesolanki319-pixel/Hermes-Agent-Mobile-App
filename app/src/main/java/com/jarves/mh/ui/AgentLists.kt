package com.jarves.mh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.data.AgentListsStore

private val CardBg = Color(0xFF1A1A1E)
private val TextMain = Color(0xFFF2F2F5)
private val TextSub = Color(0xFF9E9EA4)
private val Accent = Color(0xFF4A90E2)

/**
 * Termux Agent App ke AgentSimpleActivity jaisi sada list screen —
 * Ideas / Goals / Feed ke liye. Upar ka header aur neeche ka nav
 * RootScreenHost deta hai; yahan sirf list + add row hai.
 */
@Composable
fun AgentListScreen(storeKey: String, emptyHint: String) {
    val context = LocalContext.current
    var entries by remember(storeKey) { mutableStateOf(AgentListsStore.load(context, storeKey)) }
    var draft by remember(storeKey) { mutableStateOf("") }

    fun persist(next: List<AgentListsStore.Entry>) {
        entries = next
        AgentListsStore.save(context, storeKey, next)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (entries.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(emptyHint, color = TextSub, fontSize = 15.sp)
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { Spacer(Modifier.height(6.dp)) }
                itemsIndexed(entries) { index, entry ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                            .background(CardBg).padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (entry.done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (entry.done) Accent else TextSub,
                            modifier = Modifier.clickable {
                                persist(entries.mapIndexed { i, e ->
                                    if (i == index) e.copy(done = !e.done) else e
                                })
                            },
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.text,
                                color = TextMain,
                                fontSize = 16.sp,
                                textDecoration = if (entry.done) TextDecoration.LineThrough else null,
                            )
                            if (entry.sub.isNotEmpty()) {
                                Text(entry.sub, color = TextSub, fontSize = 13.sp)
                            }
                        }
                        IconButton(onClick = { persist(entries.filterIndexed { i, _ -> i != index }) }) {
                            Icon(Icons.Default.Close, contentDescription = "Remove", tint = TextSub)
                        }
                    }
                }
                item { Spacer(Modifier.height(6.dp)) }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Naya likho…", color = TextSub) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            IconButton(
                onClick = {
                    val text = draft.trim()
                    if (text.isNotEmpty()) {
                        persist(entries + AgentListsStore.Entry(text))
                        draft = ""
                    }
                },
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add", tint = Accent)
            }
        }
    }
}
