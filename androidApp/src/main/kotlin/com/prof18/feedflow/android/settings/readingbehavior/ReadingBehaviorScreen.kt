package com.prof18.feedflow.android.settings.readingbehavior

import androidx.compose.foundation.layout.Column
import com.prof18.feedflow.android.datause.DataUseSettingsSection
import com.prof18.feedflow.android.duplicates.DuplicateReadSettingsSection
import com.prof18.feedflow.shared.domain.feed.DuplicateReadMarker
import com.prof18.feedflow.android.datause.DataUseSetup
import com.prof18.feedflow.android.translation.TranslationSettingsSection
import com.prof18.feedflow.android.translation.TranslationSetup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prof18.feedflow.android.BrowserManager
import com.prof18.feedflow.android.ui.utils.LocalFeedFlowStrings
import com.prof18.feedflow.core.utils.BrowserIds
import com.prof18.feedflow.shared.presentation.ReadingBehaviorSettingsViewModel
import kotlinx.collections.immutable.toImmutableList
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

@Composable
internal fun ReadingBehaviorScreen(
    navigateBack: () -> Unit,
) {
    val viewModel = koinViewModel<ReadingBehaviorSettingsViewModel>()
    val browserManager = koinInject<BrowserManager>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val browserListState by browserManager.browserListState.collectAsStateWithLifecycle()
    val strings = LocalFeedFlowStrings.current

    val translation = koinInject<TranslationSetup>()
    val dataUse = koinInject<DataUseSetup>()
    val duplicateRead = koinInject<DuplicateReadMarker>()

    ReadingBehaviorScreenContent(
        navigateBack = navigateBack,
        footerContent = {
            Column {
                DuplicateReadSettingsSection(duplicateRead)
                TranslationSettingsSection(translation)
                DataUseSettingsSection(dataUse)
            }
        },
        state = state,
        browsers = browserListState.map { browser ->
            if (browser.id == BrowserIds.IN_APP_BROWSER) {
                browser.copy(name = strings.inAppBrowser)
            } else {
                browser
            }
        }.toImmutableList(),
        onBrowserSelected = { browser ->
            browserManager.setFavouriteBrowser(browser)
        },
        setArticleOpenMode = viewModel::updateArticleOpenMode,
        setSaveReaderModeContent = viewModel::updateSaveReaderModeContent,
        setPrefetchArticleContent = viewModel::updatePrefetchArticleContent,
        setMarkReadWhenScrolling = viewModel::updateMarkReadWhenScrolling,
        setShowReadItem = viewModel::updateShowReadItemsOnTimeline,
        setHideReadItems = viewModel::updateHideReadItems,
    )
}
