package com.jarves.mh.ui

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.jarves.mh.R

private val PillBg = Color(0xFF2A2A30)
private val ButtonBg = Color(0xFF1A1A1E)
private val HeaderText = Color(0xFFF2F2F5)
private val HeaderSub = Color(0xFF9E9EA4)
private val OnlineGreen = Color(0xFF4CAF50)

/**
 * Termux Agent App wala pinned header: beech me LIVE Jolly avatar (idle /
 * working video), neeche naam + live status wala pill; upar-left gol menu
 * button, upar-right "+" aur Settings wala pill. Har main screen ke upar
 * RootScreenHost isey lagata hai.
 */
@Composable
fun AgentHeader(
    agentName: String,
    busy: Boolean,
    onMenuClick: () -> Unit,
    onNewClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onAgentClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(top = 10.dp, bottom = 6.dp)) {
        // Beech: avatar + naam pill
        Column(
            Modifier.align(Alignment.TopCenter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LiveAvatar(busy = busy, modifier = Modifier.size(92.dp).clip(CircleShape).clickable { onAgentClick() })
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier.clip(RoundedCornerShape(50)).background(PillBg)
                    .clickable { onAgentClick() }
                    .padding(horizontal = 22.dp, vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(agentName, color = HeaderText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!busy) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(OnlineGreen))
                        Spacer(Modifier.size(5.dp))
                    }
                    Text(
                        if (busy) "Working on it" else "Online • Ready",
                        color = HeaderSub,
                        fontSize = 12.sp,
                    )
                }
            }
        }
        // Upar-left: gol menu button
        IconButton(
            onClick = onMenuClick,
            modifier = Modifier.align(Alignment.TopStart).size(44.dp)
                .clip(CircleShape).background(ButtonBg),
        ) {
            Icon(Icons.Default.Menu, contentDescription = "Menu", tint = HeaderText)
        }
        // Upar-right: + / settings pill
        Row(
            Modifier.align(Alignment.TopEnd).clip(RoundedCornerShape(50)).background(ButtonBg)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onNewClick, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Default.Add, contentDescription = "New chat", tint = HeaderText)
            }
            IconButton(onClick = onSettingsClick, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = HeaderText)
            }
        }
    }
}

@Composable
private fun LiveAvatar(busy: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var failed by remember { mutableStateOf(false) }
    if (failed) {
        Image(
            painter = painterResource(R.drawable.agent_avatar),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
        return
    }
    // busy badalte hi player dobara banta hai taaki sahi clip chale.
    key(busy) {
        var player by remember { mutableStateOf<MediaPlayer?>(null) }
        DisposableEffect(Unit) {
            onDispose {
                try { player?.release() } catch (ignored: Exception) {
                }
                player = null
            }
        }
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            try {
                                val mp = MediaPlayer()
                                val asset = if (busy) "avatar/avatar_working.mp4" else "avatar/avatar_idle.mp4"
                                context.assets.openFd(asset).use { fd ->
                                    mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                                }
                                mp.setSurface(Surface(st))
                                mp.isLooping = true
                                mp.setVolume(0f, 0f)
                                mp.prepare()
                                mp.start()
                                player = mp
                            } catch (e: Exception) {
                                failed = true
                            }
                        }

                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            try { player?.release() } catch (ignored: Exception) {
                            }
                            player = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
                }
            },
            modifier = modifier,
        )
    }
}
