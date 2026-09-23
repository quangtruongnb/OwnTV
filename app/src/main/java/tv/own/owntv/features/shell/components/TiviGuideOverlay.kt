package tv.own.owntv.features.shell.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.own.owntv.R
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.epg.displayLogoUrl
import tv.own.owntv.core.live.EpgNowNext
import tv.own.owntv.core.theme.GlassSurface
import tv.own.owntv.features.epg.GuideGridDefaults
import tv.own.owntv.features.epg.ProgrammeStripCanvas
import tv.own.owntv.features.live.LiveViewModel
import tv.own.owntv.ui.components.ChannelNumberColumn
import tv.own.owntv.ui.components.FocusableSurface
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.format.rememberSystemTimeFormatter
import tv.own.owntv.ui.theme.OwnTVTheme

private const val CATEGORY_LOAD_DEBOUNCE_MS = 180L

/**
 * Three-column TV Guide and Channel List Overlay.
 *
 * Proportions (percentage-based fractions for responsive scaling across 720p/1080p/4K):
 * - Top Area: 32% screen height
 *   - Left pane: 72% width (Focused Programme Details card)
 *   - Right pane: 28% width, 16:9 ratio (Video Preview transparent placeholder)
 * - Bottom Grid Area: 63% screen height
 *   - Column 1: 18% width (Categories sidebar)
 *   - Column 2: 24% width (Channel list, pinned)
 *   - Column 3: 58% width (EPG Timeline Grid synchronized across rows)
 * - Footer: 5% screen height (D-pad key hints)
 */
@Composable
fun TiviGuideOverlay(
    categories: List<Pair<CategoryEntity, String>>,
    currentCategoryId: Long?,
    channels: List<ChannelEntity>,
    currentChannelId: Long?,
    liveVm: LiveViewModel,
    onSelectChannel: (ChannelEntity) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    providerNames: Map<Long, String> = emptyMap(),
    showNumbers: Boolean = true,
) {
    val colors = OwnTVTheme.colors
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val formatTime = rememberSystemTimeFormatter()

    // 2-hour lookback, 6-hour lookahead timeline window
    var liveNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            liveNow = System.currentTimeMillis()
        }
    }
    val windowStart = remember(liveNow) { liveNow - 2 * 3600_000L }
    val windowEnd = remember(liveNow) { liveNow + 6 * 3600_000L }

    // Categories list: add Favorites as the top entry if user has favorites
    val favoriteIds by liveVm.favoriteIds.collectAsStateWithLifecycle()
    val favLabel = stringResource(R.string.content_category_favorites)
    val allCategories = remember(categories, favoriteIds, favLabel) {
        val list = mutableListOf<Pair<Long, String>>()
        if (favoriteIds.isNotEmpty()) {
            list.add(-1L to favLabel)
        }
        categories.forEach { (cat, name) ->
            list.add(cat.id to name)
        }
        list
    }

    var selectedCategoryId by remember(currentCategoryId) {
        mutableLongStateOf(currentCategoryId ?: allCategories.firstOrNull()?.first ?: -1L)
    }

    // Active channels in the selected category
    var activeChannels by remember { mutableStateOf(channels) }
    var activeChannelsCategoryId by remember { mutableStateOf(currentCategoryId) }
    LaunchedEffect(selectedCategoryId) {
        activeChannels = if (selectedCategoryId == currentCategoryId && channels.isNotEmpty()) {
            channels
        } else {
            // Arrowing rapidly across categories cancels this effect before the delay completes,
            // so only the category where the user pauses reaches the database.
            delay(CATEGORY_LOAD_DEBOUNCE_MS)
            liveVm.channelsForCategory(selectedCategoryId)
        }
        activeChannelsCategoryId = selectedCategoryId
    }

    // Focus state: currently focused channel (drives top-left details)
    var focusedChannel by remember {
        mutableStateOf(channels.firstOrNull { it.id == currentChannelId } ?: channels.firstOrNull())
    }

    // Programme caching for visible rows
    val rowProgrammes = remember { mutableStateMapOf<Long, List<EpgProgrammeEntity>>() }

    // Shared timeline horizontal scroll
    val hScroll = rememberScrollState()

    // Auto-scroll timeline to anchor "Now" line ~30min into view
    LaunchedEffect(windowStart) {
        val minutesBack = ((liveNow - windowStart) / 60_000L).toInt()
        val px = with(density) {
            ((minutesBack - GuideGridDefaults.SlotMin).coerceAtLeast(0) * GuideGridDefaults.PxPerMin.value).dp.roundToPx()
        }
        runCatching { hScroll.scrollTo(px) }
    }

    // Vertical rows scroll state
    val rowListState = rememberLazyListState()
    val categoryListState = rememberLazyListState()

    // Focus management
    val categoryFocusRequesters = remember { mutableMapOf<Long, FocusRequester>() }
    val channelFocusRequesters = remember { mutableMapOf<Long, FocusRequester>() }
    val timelineFocusRequesters = remember { mutableMapOf<Long, FocusRequester>() }
    // A category switch fetches its channel rows asynchronously. Hold the D-pad Right intent until
    // those rows have replaced the previous category, otherwise the requester belongs to a row that
    // has just left composition and focus appears not to move at all.
    var pendingChannelFocus by remember { mutableStateOf<Long?>(null) }
    // Category requesters belong to lazy-list items, so an off-screen category has no requester
    // until the rail scrolls it into composition.
    var pendingCategoryFocus by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(allCategories, pendingCategoryFocus) {
        val categoryId = pendingCategoryFocus ?: return@LaunchedEffect
        val categoryIndex = allCategories.indexOfFirst { it.first == categoryId }
        if (categoryIndex < 0) return@LaunchedEffect

        categoryListState.scrollToItem(categoryIndex)
        delay(120)
        categoryFocusRequesters[categoryId]?.requestFocus()
        pendingCategoryFocus = null
    }

    LaunchedEffect(activeChannelsCategoryId, pendingChannelFocus) {
        val categoryId = pendingChannelFocus ?: return@LaunchedEffect
        if (categoryId != activeChannelsCategoryId) return@LaunchedEffect
        val channel = activeChannels.firstOrNull { it.id == focusedChannel?.id }
            ?: activeChannels.firstOrNull()
            ?: run {
                pendingChannelFocus = null
                return@LaunchedEffect
            }
        focusedChannel = channel
        rowListState.scrollToItem(activeChannels.indexOf(channel).coerceAtLeast(0))
        delay(120)
        channelFocusRequesters[channel.id]?.requestFocus()
        pendingChannelFocus = null
    }

    // Initial focus on the currently playing channel
    LaunchedEffect(Unit) {
        val targetId = currentChannelId ?: channels.firstOrNull()?.id
        val targetIdx = activeChannels.indexOfFirst { it.id == targetId }.coerceAtLeast(0)
        runCatching { rowListState.scrollToItem(targetIdx) }
        delay(120)
        targetId?.let { channelFocusRequesters[it]?.requestFocus() }
    }

    BackHandler { onDismiss() }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
        ) {
            // =========================================================================
            // TOP SECTION (~32% Height): Programme Focus Info + Video Preview Cutout
            // =========================================================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.32f),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                // Left Pane (72% Width): Detailed programme information card
                Box(
                    modifier = Modifier
                        .weight(0.72f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xE614161F))
                        .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(16.dp))
                        .padding(16.dp),
                ) {
                    val ch = focusedChannel
                    val nowNext by produceState<EpgNowNext?>(null, ch?.id) {
                        value = ch?.let { liveVm.nowNextFor(it) }
                    }
                    val currentProg = nowNext?.now
                    val nextProg = nowNext?.next

                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        // Channel Meta Row
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (ch != null) {
                                OverlayChannelLogo(channel = ch, sizeDp = 36)
                                if (showNumbers && ch.number != null) {
                                    ChannelNumberColumn(number = ch.number, color = colors.primary)
                                }
                                Text(
                                    text = ch.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = colors.primary,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (ch.id == currentChannelId) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color(0xFFE50914))
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    ) {
                                        Text(
                                            text = stringResource(R.string.player_live).uppercase(),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White,
                                            fontWeight = FontWeight.ExtraBold,
                                        )
                                    }
                                }
                            }
                        }

                        // Programme Title & Progress
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (currentProg != null) {
                                Text(
                                    text = currentProg.title,
                                    style = MaterialTheme.typography.titleLarge,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )

                                val remaining = ((currentProg.stopMs - liveNow) / 60_000L).toInt()
                                Text(
                                    text = if (remaining in 1..600) {
                                        stringResource(
                                            R.string.content_live_time_remaining,
                                            formatTime(currentProg.stopMs),
                                            remaining,
                                        )
                                    } else {
                                        stringResource(
                                            R.string.content_live_time_range,
                                            formatTime(currentProg.startMs),
                                            formatTime(currentProg.stopMs),
                                        )
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.onSurfaceVariant,
                                )

                                val duration = (currentProg.stopMs - currentProg.startMs).toFloat()
                                val progress = if (duration > 0) ((liveNow - currentProg.startMs).toFloat() / duration).coerceIn(0f, 1f) else 0f

                                // Progress bar
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(Color(0x33FFFFFF)),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(progress)
                                            .fillMaxHeight()
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(Color(0xFFE50914)),
                                    )
                                }
                            } else {
                                Text(
                                    text = ch?.name ?: stringResource(R.string.content_epg_title),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = stringResource(R.string.content_epg_no_programme),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                        }

                        // Programme Synopsis / Next item
                        val description = currentProg?.description
                        if (!description.isNullOrBlank()) {
                            Text(
                                text = description,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        } else if (nextProg != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.content_live_next),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = stringResource(
                                        R.string.content_live_time_range,
                                        formatTime(nextProg.startMs),
                                        formatTime(nextProg.stopMs),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant.copy(alpha = 0.7f),
                                )
                                Text(
                                    text = nextProg.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                // Right Pane (28% Width): Video Preview container
                // Video Surface rendered in OwnTVShell docks into this exact reserved location!
                Box(
                    modifier = Modifier
                        .weight(0.28f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.TopEnd,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.5.dp, Color(0x33FFFFFF), RoundedCornerShape(14.dp)),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // =========================================================================
            // BOTTOM GRID SECTION (~63% Height): 3 Columns (Categories + Channels + EPG)
            // =========================================================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xF00D0F16))
                    .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(16.dp))
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // ---------------------------------------------------------------------
                // Column 1 (18% Width): Categories Sidebar
                // ---------------------------------------------------------------------
                Column(
                    modifier = Modifier
                        .weight(0.18f)
                        .fillMaxHeight()
                        .background(Color(0x33000000), RoundedCornerShape(12.dp))
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                ) {
                    LazyColumn(
                        state = categoryListState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        itemsIndexed(allCategories, key = { _, item -> item.first }) { _, (catId, catName) ->
                            val isSelected = catId == selectedCategoryId
                            val fr = categoryFocusRequesters.getOrPut(catId) { FocusRequester() }

                            FocusableSurface(
                                onClick = {
                                    selectedCategoryId = catId
                                    pendingChannelFocus = catId
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .focusRequester(fr)
                                    .onFocusChanged {
                                        if (it.isFocused && selectedCategoryId != catId) {
                                            selectedCategoryId = catId
                                        }
                                    }
                                    .onPreviewKeyEvent { e ->
                                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                        when (e.key) {
                                            Key.DirectionRight -> {
                                                pendingChannelFocus = catId
                                                true
                                            }
                                            // The category rail is the leftmost column. Consume Left so
                                            // an accidental repeat cannot dismiss the guide; Back is the
                                            // explicit exit action.
                                            Key.DirectionLeft -> true
                                            Key.Back -> {
                                                onDismiss()
                                                true
                                            }
                                            else -> false
                                        }
                                    },
                                shape = RoundedCornerShape(8.dp),
                                unfocusedContainerColor = if (isSelected) Color(0x33FFFFFF) else Color.Transparent,
                                contentAlignment = Alignment.CenterStart,
                                surface = GlassSurface.CARDS,
                            ) { focused ->
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = catName,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = if (focused) colors.primary else if (isSelected) Color.White else colors.onSurfaceVariant,
                                        fontWeight = if (isSelected || focused) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }

                // ---------------------------------------------------------------------
                // Column 2 & 3 Combined (82% Width): Channels (24% of screen) + EPG (58% of screen)
                // ---------------------------------------------------------------------
                Column(
                    modifier = Modifier
                        .weight(0.82f)
                        .fillMaxHeight(),
                ) {
                    val slots = remember(windowStart, windowEnd) {
                        ((windowEnd - windowStart) / (GuideGridDefaults.SlotMin * 60_000L)).toInt()
                    }

                    // Header Row: Time Axis for Timeline Grid
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(28.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Spacer for Column 2 width (24 / 82 ≈ 29.27% of this container)
                            Box(modifier = Modifier.weight(0.2927f)) {
                                Text(
                                    text = stringResource(R.string.search_channels).uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.onSurfaceVariant,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(start = 12.dp),
                                )
                            }

                            // Time Ruler for Column 3 (58 / 82 ≈ 70.73% of this container)
                            Box(
                                modifier = Modifier
                                    .weight(0.7073f)
                                    .horizontalScroll(hScroll),
                            ) {
                                Row {
                                    for (i in 0 until slots) {
                                        val slotMs = windowStart + i * GuideGridDefaults.SlotMin * 60_000L
                                        Text(
                                            text = formatTime(slotMs),
                                            style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Content),
                                            color = colors.onSurfaceVariant,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier
                                                .width((GuideGridDefaults.SlotMin * GuideGridDefaults.PxPerMin.value).dp)
                                                .padding(start = 6.dp),
                                        )
                                    }
                                }
                                if (liveNow in windowStart..windowEnd) {
                                    val nowOffset = (((liveNow - windowStart) / 60_000f) * GuideGridDefaults.PxPerMin.value).dp
                                    Text(
                                        text = formatTime(liveNow),
                                        style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Content),
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .offset(x = nowOffset - 24.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color(0xFFE50914))
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    // Synchronized Rows: Channel on Left (Col 2), Timeline strip on Right (Col 3)
                    LazyColumn(
                        state = rowListState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        itemsIndexed(activeChannels, key = { _, ch -> ch.id }) { index, channel ->
                            val chFR = channelFocusRequesters.getOrPut(channel.id) { FocusRequester() }
                            val tlFR = timelineFocusRequesters.getOrPut(channel.id) { FocusRequester() }
                            val isCurrent = channel.id == currentChannelId

                            // Fetch programmes for this channel row lazily
                            val programmes by produceState<List<EpgProgrammeEntity>?>(
                                initialValue = rowProgrammes[channel.id],
                                channel.id,
                                windowStart,
                            ) {
                                if (rowProgrammes[channel.id] == null) {
                                    val progs = liveVm.guideProgrammesFor(channel, windowStart, windowEnd)
                                    rowProgrammes[channel.id] = progs
                                    value = progs
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(GuideGridDefaults.RowHeight),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // ---------------------------------------------
                                // Column 2: Channel Item (29.27% of container)
                                // ---------------------------------------------
                                FocusableSurface(
                                    onClick = {
                                        liveVm.loadChannelsForCategory(selectedCategoryId)
                                        onSelectChannel(channel)
                                        onDismiss()
                                    },
                                    modifier = Modifier
                                        .weight(0.2927f)
                                        .fillMaxHeight()
                                        .focusRequester(chFR)
                                        .onFocusChanged {
                                            if (it.isFocused) {
                                                focusedChannel = channel
                                            }
                                        }
                                        .onPreviewKeyEvent { e ->
                                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                            when (e.key) {
                                                // There is no channel above the first row. Keep focus here
                                                // rather than letting spatial focus search jump to the category rail.
                                                Key.DirectionUp -> index == 0
                                                Key.DirectionLeft -> {
                                                    pendingCategoryFocus = selectedCategoryId
                                                    true
                                                }
                                                Key.DirectionRight -> {
                                                    tlFR.requestFocus()
                                                    true
                                                }
                                                Key.Back -> {
                                                    onDismiss()
                                                    true
                                                }
                                                else -> false
                                            }
                                        },
                                    shape = RoundedCornerShape(8.dp),
                                    unfocusedContainerColor = if (isCurrent) Color(0x33E50914) else colors.surfaceContainerHigh,
                                    contentAlignment = Alignment.CenterStart,
                                    surface = GlassSurface.CARDS,
                                ) { focused ->
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        OverlayChannelLogo(channel = channel, sizeDp = 28)
                                        if (showNumbers && channel.number != null) {
                                            ChannelNumberColumn(
                                                number = channel.number,
                                                color = if (focused) colors.primary else colors.onSurfaceVariant,
                                            )
                                        }
                                        Text(
                                            text = channel.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = if (focused) colors.primary else if (isCurrent) Color.White else colors.onSurface,
                                            fontWeight = if (isCurrent || focused) FontWeight.Bold else FontWeight.Normal,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (isCurrent) {
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(Color(0xFFE50914)),
                                            )
                                        }
                                    }
                                }

                                // ---------------------------------------------
                                // Column 3: Timeline Strip (70.73% of container)
                                // ---------------------------------------------
                                var stripFocused by remember { mutableStateOf(false) }
                                var cursorTime by remember { mutableLongStateOf(liveNow) }

                                Box(
                                    modifier = Modifier
                                        .weight(0.7073f)
                                        .fillMaxHeight()
                                        .focusRequester(tlFR)
                                        .onFocusChanged {
                                            stripFocused = it.isFocused
                                            if (it.isFocused) {
                                                focusedChannel = channel
                                            }
                                        }
                                        .onKeyEvent { e ->
                                            if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                                            val progs = programmes
                                            when (e.key) {
                                                Key.DirectionLeft -> {
                                                    if (!progs.isNullOrEmpty()) {
                                                        val curIdx = progs.indexOfLast { it.startMs <= cursorTime }.coerceAtLeast(0)
                                                        if (curIdx > 0) {
                                                            cursorTime = progs[curIdx - 1].startMs
                                                            val px = with(density) {
                                                                (((cursorTime - windowStart) / 60_000f) * GuideGridDefaults.PxPerMin.value).dp.roundToPx()
                                                            }
                                                            scope.launch { hScroll.scrollTo(px.coerceIn(0, hScroll.maxValue)) }
                                                            return@onKeyEvent true
                                                        }
                                                    }
                                                    chFR.requestFocus()
                                                    true
                                                }
                                                Key.DirectionRight -> {
                                                    if (!progs.isNullOrEmpty()) {
                                                        val curIdx = progs.indexOfLast { it.startMs <= cursorTime }.coerceAtLeast(0)
                                                        if (curIdx < progs.size - 1) {
                                                            cursorTime = progs[curIdx + 1].startMs
                                                            val px = with(density) {
                                                                (((cursorTime - windowStart) / 60_000f) * GuideGridDefaults.PxPerMin.value).dp.roundToPx()
                                                            }
                                                            scope.launch { hScroll.scrollTo(px.coerceIn(0, hScroll.maxValue)) }
                                                            return@onKeyEvent true
                                                        }
                                                    }
                                                    false
                                                }
                                                Key.Back -> {
                                                    chFR.requestFocus()
                                                    true
                                                }
                                                Key.DirectionCenter, Key.Enter -> {
                                                    liveVm.loadChannelsForCategory(selectedCategoryId)
                                                    onSelectChannel(channel)
                                                    onDismiss()
                                                    true
                                                }
                                                else -> false
                                            }
                                        }
                                        .focusable()
                                        .clip(RoundedCornerShape(8.dp))
                                        .then(
                                            if (stripFocused) Modifier.border(2.dp, colors.focusBorder, RoundedCornerShape(8.dp))
                                            else Modifier
                                        ),
                                ) {
                                    programmes?.let { progs ->
                                        ProgrammeStripCanvas(
                                            programmes = progs,
                                            windowStart = windowStart,
                                            windowEnd = windowEnd,
                                            now = liveNow,
                                            highlightTime = if (stripFocused) cursorTime else null,
                                            catchupIds = emptySet(),
                                            hScroll = hScroll,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OverlayChannelLogo(channel: ChannelEntity, sizeDp: Int = 32) {
    val url = channel.displayLogoUrl
    if (url.isNullOrBlank()) {
        Box(
            modifier = Modifier
                .size(sizeDp.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0x33FFFFFF)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = channel.name.take(1).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
        }
        return
    }
    var failed by remember(url) { mutableStateOf(false) }
    if (failed) {
        Box(
            modifier = Modifier
                .size(sizeDp.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0x33FFFFFF)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = channel.name.take(1).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
        }
        return
    }
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.size(sizeDp.dp),
        onState = { if (it is AsyncImagePainter.State.Error) failed = true },
    )
}
