package com.prof18.feedflow.android.home

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.prof18.feedflow.core.model.FeedFilter
import com.prof18.feedflow.core.model.FeedSourceCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HomeBackHandlerTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var isDrawerOpen by mutableStateOf(false)
    private var currentFeedFilter by mutableStateOf<FeedFilter>(FeedFilter.Timeline)
    private val events = mutableListOf<String>()

    // fork (Phase 4j): on Timeline, the drawer opens first and Back with it open leaves the app.
    @Test
    fun `back on the timeline with the drawer open is left to the system`() {
        setHandler(drawerOpen = true, filter = FeedFilter.Timeline)

        pressBack()

        assertEquals(listOf("system"), events)
    }

    @Test
    fun `back from a filter returns to the timeline`() {
        setHandler(drawerOpen = false, filter = categoryFilter)

        pressBack()

        assertEquals(listOf("show timeline"), events)
    }

    @Test
    fun `back with the drawer open over a filter only closes the drawer`() {
        setHandler(drawerOpen = true, filter = categoryFilter)

        pressBack()

        assertEquals(listOf("close drawer"), events)
    }

    @Test
    fun `back on the timeline root opens the drawer`() {
        setHandler(drawerOpen = false, filter = FeedFilter.Timeline)

        pressBack()

        assertEquals(listOf("open drawer"), events)
    }

    @Test
    fun `back steps from a filter to the timeline, the drawer, then the system`() {
        setHandler(drawerOpen = false, filter = categoryFilter)
        pressBack()

        currentFeedFilter = FeedFilter.Timeline
        pressBack()

        isDrawerOpen = true
        pressBack()

        assertEquals(listOf("show timeline", "open drawer", "system"), events)
    }

    @Test
    fun `back from a filter with the docked drawer shown returns to the timeline`() {
        setHandler(drawerOpen = true, filter = categoryFilter, docked = true)

        pressBack()

        assertEquals(listOf("show timeline"), events)
    }

    @Test
    fun `back while the drawer is still opening over a filter closes it`() {
        lateinit var drawerState: DrawerState
        lateinit var scope: CoroutineScope
        compose.setContent {
            drawerState = rememberDrawerState(DrawerValue.Closed)
            scope = rememberCoroutineScope()
            BackHandler { events.add("system") }
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = { ModalDrawerSheet { Text("Drawer") } },
            ) {
                HomeBackHandler(
                    isDrawerOpen = drawerState.isOpenForBack,
                    currentFeedFilter = categoryFilter,
                    onCloseDrawer = { events.add("close drawer") },
                    onShowTimeline = { events.add("show timeline") },
                    onOpenDrawer = { events.add("open drawer") },
                )
            }
        }
        compose.waitForIdle()

        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { scope.launch { drawerState.open() } }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertFalse("drawer should still be animating", drawerState.isOpen) }

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeByFrame()

        assertEquals(listOf("close drawer"), events)
    }

    private fun setHandler(drawerOpen: Boolean, filter: FeedFilter, docked: Boolean = false) {
        isDrawerOpen = drawerOpen
        currentFeedFilter = filter
        compose.setContent {
            // Stands in for the activity finishing: it only runs when no home handler is enabled.
            BackHandler { events.add("system") }
            HomeBackHandler(
                isDrawerOpen = isDrawerOpen,
                currentFeedFilter = currentFeedFilter,
                onCloseDrawer = { events.add("close drawer") },
                onShowTimeline = { events.add("show timeline") },
                onOpenDrawer = { events.add("open drawer") },
                closesOnFilterBack = !docked,
            )
        }
    }

    private fun pressBack() {
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private companion object {
        val categoryFilter = FeedFilter.Category(FeedSourceCategory(id = "tech", title = "Tech"))
    }
}
