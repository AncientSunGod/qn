package com.lagradost.quicknovel.ui.mainpage

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.cloudstream4.compose.Colors
import com.lagradost.cloudstream4.compose.SingleSelectDialog
import com.lagradost.cloudstream4.rememberAppSettings
import com.lagradost.cloudstream4.theme.CloudStreamPreviewTheme
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.compose.BaseSearchBar
import com.lagradost.quicknovel.ui.common.SearchList
import com.lagradost.quicknovel.ui.common.SearchResponseAction
import com.lagradost.quicknovel.ui.search.SearchRow
import com.mihon.presentation.settings.collectAsState
import kotlinx.coroutines.launch

@Composable
fun MainScreenDialog(
    dialog: MainPageDialog,
    action: (MainPageAction) -> Unit
) {
    val title = when (dialog.type) {
        DialogType.Tags -> stringResource(R.string.filter_dialog_genre)
        DialogType.Category -> stringResource(R.string.filter_dialog_general)
        DialogType.OrderBy -> stringResource(R.string.filter_dialog_order_by)
    }

    SingleSelectDialog(
        entries = dialog.options,
        dismiss = {
            action(MainPageAction.Dismiss)
        },
        title = title,
        selectedIndex = dialog.selected,
        confirm = { selected ->
            action(MainPageAction.SelectDialog(dialog.type, selected))
        })


    /*BaseDialog(
        dismiss = {
            action(MainPageAction.Dismiss)
        },
        title = { Text(text = title) },
        items = dialog.options,
        selected = dialog.selected,
        onSelect = { selected ->
            action(MainPageAction.SelectDialog(dialog.type, selected))
        })*/
}

@Composable
fun MainPageScreen(state: MainPageState, action: (MainPageAction) -> Unit) {
    if (state.openQuery) {
        BackHandler {
            action(MainPageAction.Back)
        }
    }

    if (state.dialog != null) {
        MainScreenDialog(state.dialog, action)
    }

    val listState = rememberLazyGridState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(snapAnimationSpec = null)
    val shouldLoadMore = remember {
        derivedStateOf {
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            totalItems > 0 && lastVisibleIndex >= totalItems - 5
        }
    }

    val searchAction = remember<(SearchResponseAction) -> Unit>(action) {
        { action ->
            action(MainPageAction.ResultAction(action))
        }
    }

    val settings = rememberAppSettings()
    val searchIsRow = settings.main.isRow
    val searchIsRowState by searchIsRow.collectAsState()

    Scaffold(
        topBar = {
            MainPageSearchBar(
                openQuery = state.openQuery,
                loading = if (state.openQuery) {
                    state.query.loading
                } else {
                    state.filter.loading
                },
                url = state.filter.url,
                action = action,
                query = state.filterVisual,
                scrollBehavior = scrollBehavior,
                apiName = state.apiName
            )
        },
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                // This fixes the double padding from the bottom nav bar
                .padding(
                    start = innerPadding.calculateStartPadding(LocalLayoutDirection.current),
                    end = innerPadding.calculateEndPadding(LocalLayoutDirection.current),
                    top = innerPadding.calculateTopPadding()
                )
        ) {
            val visibleItems = if (state.openQuery) state.query.items else state.filter.items
            val visibleError = if (state.openQuery) state.query.error else state.filter.error
            val visibleLoading = if (state.openQuery) state.query.loading else state.filter.loading

            SearchList(
                lazyGridState = listState,
                isRow = searchIsRowState,
                items = visibleItems,
                modifier = Modifier.fillMaxSize(),
                searchAction = searchAction,
            )

            // Keep loading feedback visible in the content area as well as the toolbar.
            // This is especially important on an empty provider page, where the grid
            // otherwise looks like a blank screen while the first request is running.
            if (visibleLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth(),
                )
                if (visibleItems.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = "Loading novels…",
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }
            }

            // An extension runtime failure previously left an empty black provider page.
            // Show the actual error (or a clear empty state) and provide a retry/verify action.
            if (!visibleLoading && visibleItems.isEmpty()) {
                val context = LocalContext.current
                val cfChallenge = remember(visibleError) {
                    com.lagradost.quicknovel.network.CloudflareChallengeManager.findCloudflareChallenge(visibleError)
                }
                Surface(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 2.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = visibleError?.message ?: "No novels found.",
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                        )
                        if (visibleError != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        if (state.openQuery) {
                                            action(MainPageAction.Search(state.query.query))
                                        } else {
                                            action(MainPageAction.Expand)
                                        }
                                    }
                                ) {
                                    Text(stringResource(R.string.reload_error))
                                }
                                if (cfChallenge != null) {
                                    FilledTonalButton(
                                        onClick = {
                                            com.lagradost.quicknovel.network.CloudflareChallengeManager.launchVerification(
                                                context,
                                                cfChallenge.url,
                                            ) {
                                                if (state.openQuery) {
                                                    action(MainPageAction.Search(state.query.query))
                                                } else {
                                                    action(MainPageAction.Refresh)
                                                }
                                            }
                                        }
                                    ) {
                                        Text(stringResource(R.string.open_verification))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Keep a pagination failure visible when earlier pages already loaded.
            // Otherwise the grid remains on screen and the failure is only a brief toast.
            if (!state.openQuery && !visibleLoading && visibleItems.isNotEmpty() && visibleError != null) {
                val context = LocalContext.current
                val cfChallenge = remember(visibleError) {
                    com.lagradost.quicknovel.network.CloudflareChallengeManager.findCloudflareChallenge(visibleError)
                }
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = visibleError.message ?: "Could not load more novels.",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { action(MainPageAction.Expand) }) {
                                Text("Retry")
                            }
                            if (cfChallenge != null) {
                                androidx.compose.material3.OutlinedButton(
                                    onClick = {
                                        com.lagradost.quicknovel.network.CloudflareChallengeManager.launchVerification(
                                            context,
                                            cfChallenge.url,
                                        ) {
                                            action(MainPageAction.Expand)
                                        }
                                    }
                                ) {
                                    Text(stringResource(R.string.open_verification))
                                }
                            }
                        }
                    }
                }
            }
        }

        LaunchedEffect(shouldLoadMore.value, state.filter.endReached, state.filter.loading, state.filter.error) {
            if (shouldLoadMore.value &&
                state.filter.error == null &&
                !state.filter.loading &&
                !state.filter.endReached
            ) {
                action(MainPageAction.Expand)
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchResponseDialog(
    dialog: SearchRow,
    action: (SearchResponseAction) -> Unit,
    dismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        onDismissRequest = dismiss,
        modifier = Modifier.fillMaxSize(),
        dragHandle = { },
        shape = RectangleShape
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .clickable(
                    onClick = {
                        scope.launch {
                            sheetState.hide()
                        }.invokeOnCompletion {
                            dismiss()
                        }
                    }
                )
                .padding(horizontal = 10.dp)
        ) {
            Text(
                text = dialog.name,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 20.sp,
            )
            Icon(
                painter = painterResource(R.drawable.arrow_drop_down_24px),
                tint = MaterialTheme.colorScheme.onBackground,
                contentDescription = null,
            )
        }

        if (dialog.error != null) {
            val context = LocalContext.current
            val cfChallenge = remember(dialog.error) {
                com.lagradost.quicknovel.network.CloudflareChallengeManager.findCloudflareChallenge(dialog.error)
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            ) {
                Text(
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.bodyMedium,
                    text = dialog.error.message ?: dialog.error.toString(),
                    textAlign = TextAlign.Center,
                )
                if (cfChallenge != null) {
                    Button(
                        onClick = {
                            com.lagradost.quicknovel.network.CloudflareChallengeManager.launchVerification(
                                context,
                                cfChallenge.url,
                            ) {
                                dismiss()
                            }
                        }
                    ) {
                        Text(stringResource(R.string.open_verification))
                    }
                }
            }
        } else if (dialog.items.isEmpty()) {
            Text(
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.bodyMedium,
                text = stringResource(R.string.no_data),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            SearchList(
                isRow = false,
                items = dialog.items,
                modifier = Modifier
                    .fillMaxSize(),
                searchAction = action,
            )
        }
    }
}


@Composable
fun MainPageSearchBar(
    openQuery: Boolean,
    loading: Boolean,
    url: String,
    query: FilterQueryVisual,
    action: (MainPageAction) -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
    apiName: String,
) {
    val settings = rememberAppSettings()
    val searchIsRow = settings.main.isRow
    val searchIsRowState by searchIsRow.collectAsState()

    BaseSearchBar(
        placeholder = "${stringResource(R.string.search)} $apiName…",
        onQueryChange = { _ ->
            // action(MainPageAction.Search(query))
        },
        onSearch = { query ->
            action(MainPageAction.Search(query))
        },
        leadingIcon = {
            if (openQuery) {
                IconButton(onClick = {
                    action(MainPageAction.Back)
                }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_arrow_back_24),
                        contentDescription = stringResource(R.string.back_to_search),
                        modifier = Modifier.size(24.dp)
                    )
                }
            } else {
                Icon(
                    painter = painterResource(R.drawable.search_icon),
                    contentDescription = stringResource(R.string.search),
                    modifier = Modifier.size(24.dp)
                )
            }
        },
        scrollBehavior = scrollBehavior,
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(34.dp)
                            .padding(5.dp),
                        color = MaterialTheme.colorScheme.onBackground, strokeWidth = 3.0.dp
                    )
                } else if (!openQuery) {
                    IconButton(onClick = {
                        action(MainPageAction.OpenInBrowser(url))
                    }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_baseline_public_24),
                            contentDescription = stringResource(R.string.open_in_browser),
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }

                if (!openQuery) {
                    IconButton(
                        onClick = { action(MainPageAction.Refresh) },
                        enabled = !loading,
                    ) {
                        Text(
                            text = "↻",
                            fontSize = 24.sp,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }

                IconButton(onClick = {
                    searchIsRow.set(!searchIsRowState)
                }) {
                    Icon(
                        painter = painterResource(if (searchIsRowState) R.drawable.ic_baseline_grid_view_24 else R.drawable.ic_baseline_list_24),
                        contentDescription = stringResource(if (searchIsRowState) R.string.grid_view else R.string.list_view),
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

        },
    ) {
        if (!openQuery) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp, start = 10.dp, end = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                query.category?.let {
                    SelectButton(it) {
                        action(MainPageAction.OpenDialog(DialogType.Category))
                    }
                }
                query.orderBy?.let {
                    SelectButton(it) {
                        action(MainPageAction.OpenDialog(DialogType.OrderBy))
                    }
                }
                query.tag?.let {
                    SelectButton(it) {
                        action(MainPageAction.OpenDialog(DialogType.Tags))
                    }
                }
            }
        }
    }
}

@Composable
fun RowScope.SelectButton(text: String, onClick: () -> Unit) {
    FilterChip(
        selected = true,
        onClick = onClick,
        modifier = Modifier.weight(1.0f),
        shape = RoundedCornerShape(12.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.background,
            selectedLabelColor = MaterialTheme.colorScheme.onBackground,
            selectedTrailingIconColor = MaterialTheme.colorScheme.onBackground,
        ),
        label = {
            Text(
                text = text,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        },
        trailingIcon = {
            Icon(
                painter = painterResource(R.drawable.arrow_drop_down_24px),
                contentDescription = text,
                modifier = Modifier.size(18.dp),
            )
        },
    )
}

@PreviewLightDark
@Composable
private fun SettingsScreenPreview() {
    CloudStreamPreviewTheme {
        MainPageScreen(
            state = MainPageState(apiName = "hello world"),
            action = {})
    }
}
