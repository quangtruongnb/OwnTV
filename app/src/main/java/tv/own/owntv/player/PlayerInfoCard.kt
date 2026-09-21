package tv.own.owntv.player

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.own.owntv.R
import tv.own.owntv.ui.theme.OwnTVTheme

/**
 * Compact info bar shown at the bottom of the player when the HUD is in [HudLevel.INFO_CARD]
 * state (d-pad OK short press while HUD is hidden). Inspired by TiviMate's channel card.
 *
 * Non-focusable, purely informational. Shows:
 * - Channel logo / first letter
 * - Stream technical chips (codec · resolution · fps · HDR · engine)
 * - Channel name + number
 * - Live EPG card (now/next) if available, or VOD position/duration
 *
 * Wrapped in a gradient scrim so text remains readable over bright video.
 */
@Composable
internal fun PlayerInfoCard(
    player: PlaybackEngine,
    isLive: Boolean,
    streamChips: List<String>,
    videoRes: String?,
    engineChip: String?,
    position: Long,
    duration: Long,
    liveEpgCard: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val meta by player.currentMeta.collectAsStateWithLifecycle()
    val colors = OwnTVTheme.colors

    Column(modifier = modifier.fillMaxWidth()) {
        // Gradient scrim above the bar for readability
        Box(
            Modifier.fillMaxWidth().height(80.dp)
                .background(Brush.verticalGradient(
                    0f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.82f),
                )),
        )

        // The info bar itself
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.78f))
                .padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Channel logo / first letter
            val logoUrl = meta.logoUrl
            Box(
                Modifier.size(50.dp).clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF004F46)),
                contentAlignment = Alignment.Center,
            ) {
                if (!logoUrl.isNullOrBlank()) {
                    coil3.compose.AsyncImage(
                        model = logoUrl,
                        contentDescription = null,
                        modifier = Modifier.size(50.dp),
                    )
                } else {
                    Text(
                        (meta.title?.firstOrNull()?.uppercase() ?: "?"),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFF6FF8E4),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // Channel identity + technical chips
            Column(modifier = Modifier.widthIn(max = 200.dp)) {
                // Chips: engine · codec · resolution · fps · HDR (fps already in streamChips)
                val chips = buildList {
                    engineChip?.let { add(it) }
                    addAll(streamChips.ifEmpty { listOfNotNull(videoRes) })
                }
                if (chips.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        chips.forEachIndexed { i, chip ->
                            if (i > 0) {
                                Box(
                                    Modifier.size(3.dp).clip(RoundedCornerShape(50))
                                        .background(Color.White.copy(alpha = 0.25f)),
                                )
                            }
                            Text(
                                chip,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = Color.White.copy(alpha = 0.45f),
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                }

                // Channel number + name
                Row(verticalAlignment = Alignment.CenterVertically) {
                    meta.subtitle?.let { number ->
                        Text(
                            number,
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White.copy(alpha = 0.4f),
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        meta.title ?: "",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Divider
            Box(
                Modifier.width(1.dp).height(44.dp)
                    .background(Color.White.copy(alpha = 0.1f)),
            )

            // Right side: EPG card (live) or VOD position/duration
            if (isLive && liveEpgCard != null) {
                Box(modifier = Modifier.weight(1f)) {
                    liveEpgCard()
                }
            } else if (!isLive && duration > 0L) {
                // VOD: position / duration with mini progress bar
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.Start,
                ) {
                    Text(
                        "${formatTime(position)} / ${formatTime(duration)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.55f),
                    )
                    Spacer(Modifier.height(4.dp))
                    val progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                    Box(
                        Modifier.fillMaxWidth(0.5f).height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = 0.15f)),
                    ) {
                        Box(
                            Modifier.fillMaxWidth(progress).height(3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(colors.primary),
                        )
                    }
                }
            } else {
                Spacer(Modifier.weight(1f))
            }

            // Clock
            var wallNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(Unit) { while (true) { delay(20_000); wallNow = System.currentTimeMillis() } }
            val cal = remember(wallNow / 60_000) {
                java.util.Calendar.getInstance().apply { timeInMillis = wallNow }
            }
            Text(
                "%02d:%02d".format(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE)),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Light, letterSpacing = 1.sp),
                color = Color.White.copy(alpha = 0.8f),
            )
        }
    }
}
