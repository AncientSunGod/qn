package com.lagradost.quicknovel.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lagradost.cloudstream4.compose.Screen
import com.lagradost.quicknovel.CommonActivity
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.MainActivity.Companion.navigate
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.compose.BaseSearchBar
import com.lagradost.quicknovel.compose.isLandscape
import com.lagradost.quicknovel.extensions.ExtensionMainAPI
import com.lagradost.quicknovel.extensions.ExtensionProviderRegistry
import com.lagradost.quicknovel.ui.mainpage.MainPageFragment
import com.lagradost.quicknovel.util.SubtitleHelper

object HomeScreen : Screen {
    @Composable
    override fun Content() {
        val viewModel = viewModel<HomeViewModel>()
        val apis by viewModel.homeApisState.collectAsStateWithLifecycle()
        val extensionRevision by ExtensionProviderRegistry.revision.collectAsStateWithLifecycle()

        LaunchedEffect(extensionRevision) {
            viewModel.refreshApis()
        }

        HomeScreenContent(
            apis = apis,
            onOpenProvider = { api ->
                CommonActivity.activity?.navigate(
                    R.id.global_to_navigation_mainpage,
                    MainPageFragment.newInstance(api.name),
                )
            },
            onOpenSettings = {
                CommonActivity.activity?.navigate(R.id.navigation_settings)
            },
        )
    }
}

@Composable
fun HomeScreenContent(
    apis: List<MainAPI>,
    onOpenProvider: (MainAPI) -> Unit,
    onOpenSettings: () -> Unit,
) {
    var filterQuery by rememberSaveable { mutableStateOf("") }
    var selectedLang by rememberSaveable { mutableStateOf("all") }

    val availableLangs = remember(apis) {
        apis.map { it.lang.lowercase() }
            .distinct()
            .sorted()
    }

    val effectiveSelectedLang = if (selectedLang != "all" && selectedLang !in availableLangs) {
        "all"
    } else {
        selectedLang
    }

    val filteredApis = remember(apis, filterQuery, effectiveSelectedLang) {
        val q = filterQuery.trim()
        apis.filter { api ->
            val matchesLang = effectiveSelectedLang == "all" ||
                api.lang.equals(effectiveSelectedLang, ignoreCase = true)
            val matchesQuery = q.isBlank() ||
                api.name.contains(q, ignoreCase = true) ||
                api.lang.contains(q, ignoreCase = true)
            matchesLang && matchesQuery
        }
    }

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(snapAnimationSpec = null)

    Scaffold(
        topBar = {
            BaseSearchBar(
                placeholder = stringResource(R.string.explore) + "…",
                onQueryChange = { filterQuery = it },
                onSearch = { filterQuery = it },
                scrollBehavior = scrollBehavior,
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.search_icon),
                        contentDescription = stringResource(R.string.search),
                        modifier = Modifier.size(24.dp),
                    )
                },
                trailingIcon = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            painter = painterResource(R.drawable.extension_24px),
                            contentDescription = stringResource(R.string.title_settings),
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                },
            ) {
                if (availableLangs.size > 1) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item(key = "all") {
                            FilterChip(
                                selected = effectiveSelectedLang == "all",
                                onClick = { selectedLang = "all" },
                                label = { Text("${stringResource(R.string.all)} (${apis.size})") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                            )
                        }
                        items(availableLangs, key = { it }) { lang ->
                            val flag = SubtitleHelper.getFlagFromIso(lang) ?: "🌐"
                            val label = SubtitleHelper.fromTwoLettersToLanguage(lang) ?: lang.uppercase()
                            FilterChip(
                                selected = effectiveSelectedLang == lang,
                                onClick = {
                                    selectedLang = if (effectiveSelectedLang == lang) "all" else lang
                                },
                                label = { Text("$flag $label") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                            )
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        },
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = innerPadding.calculateStartPadding(LocalLayoutDirection.current),
                    end = innerPadding.calculateEndPadding(LocalLayoutDirection.current),
                    top = innerPadding.calculateTopPadding(),
                ),
        ) {
            if (filteredApis.isEmpty()) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.extension_24px),
                        contentDescription = null,
                        modifier = Modifier.size(44.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = if (apis.isEmpty()) {
                            "No sources enabled for the selected languages."
                        } else {
                            "No sources match \"$filterQuery\"."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (filterQuery.isNotBlank() || effectiveSelectedLang != "all") {
                            Button(onClick = {
                                filterQuery = ""
                                selectedLang = "all"
                            }) {
                                Text("Reset filters")
                            }
                        } else {
                            Button(onClick = onOpenSettings) {
                                Text("Manage sources")
                            }
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(if (isLandscape) 4 else 2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(filteredApis, key = { it.name }) { api ->
                        HomeProviderCard(
                            api = api,
                            onClick = { onOpenProvider(api) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeProviderCard(
    api: MainAPI,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colorResource(api.iconBackgroundId)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(api.iconId ?: R.drawable.extension_24px),
                    contentDescription = api.name,
                    modifier = Modifier.size(32.dp),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = api.name,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val flag = SubtitleHelper.getFlagFromIso(api.lang) ?: "🌐"
                val kind = if (api is ExtensionMainAPI) "Extension" else "Built-in"
                Text(
                    text = "$flag ${api.lang.uppercase()} · $kind",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
