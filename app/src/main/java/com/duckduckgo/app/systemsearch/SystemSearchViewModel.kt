/*
 * Copyright (c) 2020 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.app.systemsearch

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.duckduckgo.anvil.annotations.ContributesViewModel
import com.duckduckgo.app.autocomplete.api.AutoComplete
import com.duckduckgo.app.autocomplete.api.AutoComplete.AutoCompleteResult
import com.duckduckgo.app.bookmarks.ui.EditSavedSiteDialogFragment
import com.duckduckgo.app.browser.favorites.FavoritesQuickAccessAdapter
import com.duckduckgo.app.di.AppCoroutineScope
import com.duckduckgo.app.global.SingleLiveEvent
import com.duckduckgo.app.onboarding.store.AppStage
import com.duckduckgo.app.onboarding.store.UserStageStore
import com.duckduckgo.app.onboarding.store.isNewUser
import com.duckduckgo.app.pixels.AppPixelName.*
import com.duckduckgo.app.settings.db.SettingsDataStore
import com.duckduckgo.app.statistics.pixels.Pixel
import com.duckduckgo.app.systemsearch.SystemSearchViewModel.Command.UpdateVoiceSearch
import com.duckduckgo.common.utils.DispatcherProvider
import com.duckduckgo.di.scopes.ActivityScope
import com.duckduckgo.savedsites.api.SavedSitesRepository
import com.duckduckgo.savedsites.api.models.SavedSite
import com.duckduckgo.savedsites.api.models.SavedSite.Bookmark
import com.duckduckgo.savedsites.api.models.SavedSite.Favorite
import com.jakewharton.rxrelay2.PublishRelay
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

data class SystemSearchResult(
    val autocomplete: AutoCompleteResult,
    val deviceApps: List<DeviceApp>,
)

@ContributesViewModel(ActivityScope::class)
class SystemSearchViewModel @Inject constructor(
    private var userStageStore: UserStageStore,
    private val autoComplete: AutoComplete,
    private val deviceAppLookup: DeviceAppLookup,
    private val pixel: Pixel,
    private val savedSitesRepository: SavedSitesRepository,
    private val appSettingsPreferencesStore: SettingsDataStore,
    private val dispatchers: DispatcherProvider,
    @AppCoroutineScope private val appCoroutineScope: CoroutineScope,
) : ViewModel(), EditSavedSiteDialogFragment.EditSavedSiteListener {

    data class OnboardingViewState(
        val visible: Boolean,
        val expanded: Boolean = false,
    )

    sealed class Suggestions {
        data class SystemSearchResultsViewState(
            val autocompleteResults: AutoCompleteResult = AutoCompleteResult("", emptyList()),
            val appResults: List<DeviceApp> = emptyList(),
        ) : Suggestions()

        data class QuickAccessItems(val favorites: List<FavoritesQuickAccessAdapter.QuickAccessFavorite>) : Suggestions()
    }

    sealed class Command {
        object ClearInputText : Command()
        object LaunchDuckDuckGo : Command()
        data class LaunchBrowser(val query: String) : Command()
        data class LaunchEditDialog(val savedSite: SavedSite) : Command()
        data class DeleteSavedSiteConfirmation(val savedSite: SavedSite) : Command()
        data class LaunchDeviceApplication(val deviceApp: DeviceApp) : Command()
        data class ShowAppNotFoundMessage(val appName: String) : Command()
        object DismissKeyboard : Command()
        data class EditQuery(val query: String) : Command()

        object UpdateVoiceSearch : Command()
    }

    val onboardingViewState: MutableLiveData<OnboardingViewState> = MutableLiveData()
    val resultsViewState: MutableLiveData<Suggestions> = MutableLiveData()
    val command: SingleLiveEvent<Command> = SingleLiveEvent()

    private val resultsPublishSubject = PublishRelay.create<String>()
    private var results = SystemSearchResult(AutoCompleteResult("", emptyList()), emptyList())
    private var resultsDisposable: Disposable? = null
    private var latestQuickAccessItems: Suggestions.QuickAccessItems = Suggestions.QuickAccessItems(emptyList())

    val hiddenIds = MutableStateFlow(HiddenBookmarksIds())

    data class HiddenBookmarksIds(val favorites: List<String> = emptyList())

    private var appsJob: Job? = null

    init {
        resetViewState()
        configureResults()
        refreshAppList()

        savedSitesRepository.getFavorites()
            .combine(hiddenIds) { favorites, hiddenIds ->
                favorites.filter { it.id !in hiddenIds.favorites }
            }
            .flowOn(dispatchers.io())
            .onEach { filteredFavourites ->
                withContext(dispatchers.main()) {
                    latestQuickAccessItems =
                        Suggestions.QuickAccessItems(filteredFavourites.map { FavoritesQuickAccessAdapter.QuickAccessFavorite(it) })
                    resultsViewState.postValue(latestQuickAccessItems)
                }
            }
            .launchIn(viewModelScope)
    }

    private fun currentOnboardingState(): OnboardingViewState = onboardingViewState.value!!
    private fun currentResultsState(): Suggestions = resultsViewState.value!!

    fun resetViewState() {
        command.value = Command.ClearInputText
        viewModelScope.launch {
            resetOnboardingState()
        }
        resetResultsState()
    }

    private suspend fun resetOnboardingState() {
        val showOnboarding = userStageStore.isNewUser()
        onboardingViewState.value = OnboardingViewState(visible = showOnboarding)
        if (showOnboarding) {
            pixel.fire(INTERSTITIAL_ONBOARDING_SHOWN)
        }
    }

    private fun resetResultsState() {
        results = SystemSearchResult(AutoCompleteResult("", emptyList()), emptyList())
        appsJob?.cancel()
        resultsViewState.value = latestQuickAccessItems
    }

    private fun configureResults() {
        resultsDisposable = resultsPublishSubject
            .debounce(DEBOUNCE_TIME_MS, TimeUnit.MILLISECONDS)
            .switchMap { buildResultsObservable(query = it) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { result ->
                    updateResults(result)
                },
                { t: Throwable? -> Timber.w(t, "Failed to get search results") },
            )
    }

    private fun buildResultsObservable(query: String): Observable<SystemSearchResult>? {
        return Observable.zip(
            autoComplete.autoComplete(query),
            Observable.just(deviceAppLookup.query(query)),
        ) { autocompleteResult: AutoCompleteResult, appsResult: List<DeviceApp> ->
            SystemSearchResult(autocompleteResult, appsResult)
        }
    }

    fun userTappedOnboardingToggle() {
        onboardingViewState.value = currentOnboardingState().copy(expanded = !currentOnboardingState().expanded)
        if (currentOnboardingState().expanded) {
            pixel.fire(INTERSTITIAL_ONBOARDING_MORE_PRESSED)
            command.value = Command.DismissKeyboard
        } else {
            pixel.fire(INTERSTITIAL_ONBOARDING_LESS_PRESSED)
        }
    }

    fun userDismissedOnboarding() {
        viewModelScope.launch {
            onboardingViewState.value = currentOnboardingState().copy(visible = false)
            userStageStore.stageCompleted(AppStage.NEW)
            pixel.fire(INTERSTITIAL_ONBOARDING_DISMISSED)
        }
    }

    fun onUserSelectedToEditQuery(query: String) {
        command.value = Command.EditQuery(query)
    }

    fun userUpdatedQuery(query: String) {
        appsJob?.cancel()

        if (query.isBlank()) {
            inputCleared()
            return
        }

        if (appSettingsPreferencesStore.autoCompleteSuggestionsEnabled) {
            val trimmedQuery = query.trim()
            resultsPublishSubject.accept(trimmedQuery)
        }
    }

    private fun updateResults(results: SystemSearchResult) {
        this.results = results

        val suggestions = results.autocomplete.suggestions
        val appResults = results.deviceApps
        val hasMultiResults = suggestions.isNotEmpty() && appResults.isNotEmpty()

        val updatedSuggestions = if (hasMultiResults) suggestions.take(RESULTS_MAX_RESULTS_PER_GROUP) else suggestions
        val updatedApps = if (hasMultiResults) appResults.take(RESULTS_MAX_RESULTS_PER_GROUP) else appResults
        resultsViewState.postValue(
            when (val currentResultsState = currentResultsState()) {
                is Suggestions.SystemSearchResultsViewState -> {
                    currentResultsState.copy(
                        autocompleteResults = AutoCompleteResult(results.autocomplete.query, updatedSuggestions),
                        appResults = updatedApps,
                    )
                }

                is Suggestions.QuickAccessItems -> Suggestions.SystemSearchResultsViewState(
                    autocompleteResults = AutoCompleteResult(results.autocomplete.query, updatedSuggestions),
                    appResults = updatedApps,
                )
            },
        )
    }

    private fun inputCleared() {
        if (appSettingsPreferencesStore.autoCompleteSuggestionsEnabled) {
            resultsPublishSubject.accept("")
        }
        resetResultsState()
    }

    fun userTappedDax() {
        viewModelScope.launch {
            userStageStore.stageCompleted(AppStage.NEW)
            pixel.fire(INTERSTITIAL_LAUNCH_DAX)
            command.value = Command.LaunchDuckDuckGo
        }
    }

    fun userRequestedClear() {
        command.value = Command.ClearInputText
        inputCleared()
    }

    fun userSubmittedQuery(query: String) {
        if (query.isBlank()) {
            return
        }

        viewModelScope.launch {
            userStageStore.stageCompleted(AppStage.NEW)
            command.value = Command.LaunchBrowser(query.trim())
            pixel.fire(INTERSTITIAL_LAUNCH_BROWSER_QUERY)
        }
    }

    fun userSubmittedAutocompleteResult(query: String) {
        command.value = Command.LaunchBrowser(query)
        pixel.fire(INTERSTITIAL_LAUNCH_BROWSER_QUERY)
    }

    fun userSelectedApp(app: DeviceApp) {
        command.value = Command.LaunchDeviceApplication(app)
        pixel.fire(INTERSTITIAL_LAUNCH_DEVICE_APP)
    }

    fun appNotFound(app: DeviceApp) {
        command.value = Command.ShowAppNotFoundMessage(app.shortName)

        refreshAppList()
    }

    private fun refreshAppList() {
        viewModelScope.launch(dispatchers.io()) {
            deviceAppLookup.refreshAppList()
        }
    }

    override fun onCleared() {
        resultsDisposable?.dispose()
        resultsDisposable = null
        super.onCleared()
    }

    fun onQuickAccessListChanged(newList: List<FavoritesQuickAccessAdapter.QuickAccessFavorite>) {
        viewModelScope.launch(dispatchers.io()) {
            savedSitesRepository.updateWithPosition(newList.map { it.favorite })
        }
    }

    fun onQuickAccessItemClicked(it: FavoritesQuickAccessAdapter.QuickAccessFavorite) {
        pixel.fire(FAVORITE_SYSTEM_SEARCH_ITEM_PRESSED)
        command.value = Command.LaunchBrowser(it.favorite.url)
    }

    fun onEditQuickAccessItemRequested(it: FavoritesQuickAccessAdapter.QuickAccessFavorite) {
        command.value = Command.LaunchEditDialog(it.favorite)
    }

    fun onDeleteQuickAccessItemRequested(it: FavoritesQuickAccessAdapter.QuickAccessFavorite) {
        hideQuickAccessItem(it)
        command.value = Command.DeleteSavedSiteConfirmation(it.favorite)
    }

    companion object {
        private const val DEBOUNCE_TIME_MS = 200L
        private const val RESULTS_MAX_RESULTS_PER_GROUP = 4
    }

    override fun onFavouriteEdited(favorite: Favorite) {
        viewModelScope.launch(dispatchers.io()) {
            savedSitesRepository.updateFavourite(favorite)
        }
    }

    override fun onBookmarkEdited(
        bookmark: Bookmark,
        oldFolderId: String,
    ) {
        viewModelScope.launch(dispatchers.io()) {
            savedSitesRepository.updateBookmark(bookmark, oldFolderId)
        }
    }

    fun deleteSavedSiteSnackbarDismissed(savedSite: SavedSite) {
        when (savedSite) {
            is SavedSite.Favorite -> {
                appCoroutineScope.launch(dispatchers.io()) {
                    savedSitesRepository.delete(savedSite)
                }
            }

            else -> throw IllegalArgumentException("Illegal SavedSite to delete received")
        }
    }

    private fun hideQuickAccessItem(quickAccessFavourite: FavoritesQuickAccessAdapter.QuickAccessFavorite) {
        viewModelScope.launch(dispatchers.io()) {
            hiddenIds.emit(hiddenIds.value.copy(favorites = hiddenIds.value.favorites + quickAccessFavourite.favorite.id))
        }
    }

    fun undoDelete(savedSite: SavedSite) {
        viewModelScope.launch(dispatchers.io()) {
            hiddenIds.emit(
                hiddenIds.value.copy(
                    favorites = hiddenIds.value.favorites - savedSite.id,
                ),
            )
        }
    }

    fun voiceSearchDisabled() {
        command.value = UpdateVoiceSearch
    }
}
