package tv.own.owntv.features.shell

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class TiviGuideOverlayNavigationTest {
    private val source: String by lazy {
        File("src/main/java/tv/own/owntv/features/shell/components/TiviGuideOverlay.kt")
            .readText()
    }

    @Test
    fun `channel left composes the selected category before requesting focus`() {
        val channelBlock = source.substring(
            source.indexOf("// Column 2: Channel Item"),
            source.indexOf("// Column 3: Timeline Strip"),
        )

        assertTrue(
            "Channel-to-category Left must scroll an off-screen selected category into the lazy rail before requesting focus",
            channelBlock.contains(".onPreviewKeyEvent { e ->") &&
                channelBlock.contains("Key.DirectionLeft") &&
                channelBlock.contains("pendingCategoryFocus = selectedCategoryId") &&
                source.contains("val categoryListState = rememberLazyListState()") &&
                source.contains("categoryListState.scrollToItem(categoryIndex)"),
        )
    }

    @Test
    fun `up on the first channel remains in the channel column`() {
        val channelBlock = source.substring(
            source.indexOf("// Column 2: Channel Item"),
            source.indexOf("// Column 3: Timeline Strip"),
        )

        assertTrue(
            "The first channel must consume Up instead of allowing spatial focus search to enter the category rail",
            source.contains("itemsIndexed(activeChannels, key = { _, ch -> ch.id }) { index, channel ->") &&
                channelBlock.contains("Key.DirectionUp -> index == 0"),
        )
    }

    @Test
    fun `category right waits for the selected category channel before requesting focus`() {
        val categoryBlock = source.substring(
            source.indexOf("// Column 1 (18% Width): Categories Sidebar"),
            source.indexOf("// Column 2 & 3 Combined"),
        )

        assertTrue(
            "Category-to-channel Right must use preview dispatch and defer focus until activeChannels has refreshed",
                categoryBlock.contains(".onPreviewKeyEvent { e ->") &&
                categoryBlock.contains("Key.DirectionRight") &&
                source.contains("pendingChannelFocus") &&
                source.contains("LaunchedEffect(activeChannelsCategoryId, pendingChannelFocus)"),
        )
    }

    @Test
    fun `rapid category changes debounce the channel query`() {
        val loadBlock = source.substring(
            source.indexOf("LaunchedEffect(selectedCategoryId)"),
            source.indexOf("// Focus state"),
        )

        assertTrue(
            "A category query must wait briefly so cancelled D-pad stops do not load channels",
            source.contains("private const val CATEGORY_LOAD_DEBOUNCE_MS = 180L") &&
                loadBlock.contains("delay(CATEGORY_LOAD_DEBOUNCE_MS)") &&
                loadBlock.indexOf("delay(CATEGORY_LOAD_DEBOUNCE_MS)") <
                    loadBlock.indexOf("liveVm.channelsForCategory(selectedCategoryId)"),
        )
    }

    @Test
    fun `category left is consumed without dismissing the guide`() {
        val categoryBlock = source.substring(
            source.indexOf("// Column 1 (18% Width): Categories Sidebar"),
            source.indexOf("// Column 2 & 3 Combined"),
        )

        assertTrue(
            "Left in the category column must be inert; Back remains the explicit dismissal action",
            categoryBlock.contains("Key.DirectionLeft -> true") &&
                categoryBlock.contains("Key.Back -> {") &&
                !categoryBlock.contains("Key.DirectionLeft, Key.Back ->"),
        )
    }
}
