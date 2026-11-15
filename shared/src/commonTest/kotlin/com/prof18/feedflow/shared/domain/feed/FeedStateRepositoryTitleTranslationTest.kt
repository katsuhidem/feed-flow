package com.prof18.feedflow.shared.domain.feed

import com.prof18.feedflow.core.model.FeedItem
import com.prof18.feedflow.core.model.FeedSource
import com.prof18.feedflow.database.DatabaseHelper
import com.prof18.feedflow.shared.test.KoinTestBase
import com.prof18.feedflow.shared.test.TestDispatcherProvider.testDispatcher
import com.prof18.feedflow.shared.test.buildFeedItem
import com.prof18.feedflow.shared.test.generators.FeedSourceGenerator
import com.prof18.feedflow.shared.test.toParsedFeedSource
import kotlinx.coroutines.test.runTest
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.inject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** fork (Phase 2): the title translation hook of [FeedStateRepository]. */
class FeedStateRepositoryTitleTranslationTest : KoinTestBase() {

    private class FakeTranslations : TimelineTitleTranslations {
        val known = mutableMapOf<String, String>()
        val loaded = mutableListOf<List<String>>()
        var onChange: ((Set<String>, Map<String, String>) -> Unit)? = null

        override suspend fun lookup(items: List<FeedItem>): Map<String, String> =
            items.mapNotNull { item -> known[item.id]?.let { item.id to it } }.toMap()

        override fun onItemsLoaded(items: List<FeedItem>) {
            loaded += items.map { it.id }
        }

        override fun setListener(listener: (ids: Set<String>, translations: Map<String, String>) -> Unit) {
            onChange = listener
        }
    }

    private val translations = FakeTranslations()

    override fun getTestModules(): List<Module> =
        super.getTestModules() + module { single<TimelineTitleTranslations> { translations } }

    private val repository: FeedStateRepository by inject()
    private val databaseHelper: DatabaseHelper by inject()

    private fun titles(): Map<String, String?> =
        repository.feedState.value.associate { it.title.orEmpty() to it.translatedTitle }

    @Test
    fun `known translations are filled in before the list is published`() = runTest(testDispatcher) {
        val source = createSource("source-1")
        insertItems(source, "first" to 1, "second" to 2)
        val ids = itemIds(source)
        translations.known[ids.getValue("first")] = "最初"

        repository.getFeeds()

        assertEquals(mapOf<String, String?>("first" to "最初", "second" to null), titles())
        assertEquals(listOf(ids.values.toSet()), translations.loaded.map { it.toSet() })
    }

    @Test
    fun `a stored translation replaces the title without reloading the list`() = runTest(testDispatcher) {
        val source = createSource("source-1")
        insertItems(source, "first" to 1, "second" to 2)
        val ids = itemIds(source)
        repository.getFeeds()
        val versionBefore = repository.feedListVersion.value

        translations.onChange?.invoke(setOf(ids.getValue("second")), mapOf(ids.getValue("second") to "二番目"))

        assertEquals(mapOf<String, String?>("first" to null, "second" to "二番目"), titles())
        assertEquals(versionBefore, repository.feedListVersion.value, "the list position must be kept")
    }

    @Test
    fun `an id without a translation loses its translated title`() = runTest(testDispatcher) {
        val source = createSource("source-1")
        insertItems(source, "first" to 1)
        val id = itemIds(source).getValue("first")
        translations.known[id] = "最初"
        repository.getFeeds()
        assertEquals("最初", repository.feedState.value.single().translatedTitle)

        // For example after the style setting changed and the old translation no longer applies.
        translations.onChange?.invoke(setOf(id), emptyMap())

        assertNull(repository.feedState.value.single().translatedTitle)
    }

    @Test
    fun `changes for items that are not in the list are ignored`() = runTest(testDispatcher) {
        val source = createSource("source-1")
        insertItems(source, "first" to 1)
        repository.getFeeds()
        val before = repository.feedState.value

        translations.onChange?.invoke(setOf("unknown-id"), mapOf("unknown-id" to "x"))

        assertEquals(before, repository.feedState.value)
    }

    private suspend fun insertItems(source: FeedSource, vararg items: Pair<String, Int>) {
        databaseHelper.insertFeedSource(listOf(source.toParsedFeedSource()))
        databaseHelper.insertFeedItems(
            items.map { (title, time) ->
                buildFeedItem(id = title, title = title, pubDateMillis = time.toLong(), source = source)
            },
            lastSyncTimestamp = 0,
        )
    }

    /** Title to id, read back from the repository's own query so the ids are the real ones. */
    private suspend fun itemIds(source: FeedSource): Map<String, String> {
        repository.getFeeds()
        translations.loaded.clear()
        return repository.feedState.value.associate { it.title.orEmpty() to it.id }
    }

    private fun createSource(id: String): FeedSource = FeedSourceGenerator.feedSource(
        id = id,
        url = "https://example.com/$id.xml",
        title = id,
        category = null,
    )
}
