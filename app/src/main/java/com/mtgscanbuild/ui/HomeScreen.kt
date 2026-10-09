package com.mtgscanbuild.ui

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mtgscanbuild.data.BanLists
import com.mtgscanbuild.data.HomeMode
import com.mtgscanbuild.data.HomeModes
import com.mtgscanbuild.data.HomeSources
import com.mtgscanbuild.data.LegalityCard
import com.mtgscanbuild.data.MatchType
import com.mtgscanbuild.data.NewsApi
import com.mtgscanbuild.data.NewsArticle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

data class HomeLoadState<T>(
    val data: T? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val fetchedAt: Long? = null,
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val newsApi = NewsApi()
    private val scryfall = app.repo.api
    var news by mutableStateOf(HomeLoadState<List<NewsArticle>>())
        private set
    val bans = mutableStateMapOf<String, HomeLoadState<BanLists>>()

    init { refreshNews() }

    fun refreshNewsIfStale() {
        val fetchedAt = news.fetchedAt
        if (fetchedAt == null || System.currentTimeMillis() - fetchedAt >= 15 * 60 * 1000L) refreshNews()
    }

    fun refreshNews() {
        if (news.loading) return
        news = news.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                news = HomeLoadState(data = newsApi.latest(), fetchedAt = System.currentTimeMillis())
            } catch (e: CancellationException) {
                news = news.copy(loading = false)
                throw e
            } catch (e: Exception) {
                news = news.copy(loading = false, error = e.message ?: "Could not load Wizards news.")
            }
        }
    }

    fun loadBans(mode: HomeMode, force: Boolean = false) {
        val id = mode.legalityId ?: return
        val previous = bans[id] ?: HomeLoadState()
        if (previous.loading || (!force && previous.fetchedAt != null &&
                System.currentTimeMillis() - previous.fetchedAt < 15 * 60 * 1000L)) return
        bans[id] = previous.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                bans[id] = HomeLoadState(data = scryfall.bannedAndRestricted(id), fetchedAt = System.currentTimeMillis())
            } catch (e: CancellationException) {
                bans[id] = previous.copy(loading = false)
                throw e
            } catch (e: Exception) {
                bans[id] = previous.copy(error = e.message ?: "Could not load the legality list.")
            }
        }
    }
}

private val homeTabs = listOf("News", "Banned / Restricted", "Rulebook")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: HomeViewModel = viewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var banModeId by rememberSaveable { mutableStateOf("standard") }
    var ruleModeId by rememberSaveable { mutableStateOf("commander") }
    val banMode = HomeModes.withBanLists.first { it.id == banModeId }
    val ruleMode = HomeModes.all.first { it.id == ruleModeId }
    LaunchedEffect(tab, banModeId) {
        if (tab == 0) vm.refreshNewsIfStale()
        if (tab == 1) vm.loadBans(banMode)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (tab == 0) vm.refreshNewsIfStale()
        if (tab == 1) vm.loadBans(banMode)
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = {
            Column {
                Text("Home")
                Text("MTG Scan & Build", style = MaterialTheme.typography.bodySmall)
            }
        })
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            homeTabs.forEachIndexed { index, title ->
                FilterChip(tab == index, { tab = index }, label = { Text(title) })
            }
        }
        when (tab) {
            0 -> NewsSection(vm.news, vm::refreshNews)
            1 -> BanSection(banMode, { banModeId = it.id },
                vm.bans[banMode.id] ?: HomeLoadState(), { vm.loadBans(banMode, force = true) })
            2 -> RulesSection(ruleMode, { ruleModeId = it.id })
        }
    }
}

@Composable
private fun NewsSection(state: HomeLoadState<List<NewsArticle>>, refresh: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionHeading("Latest MTG news", state.loading, refresh)
            Text("Headlines from Wizards of the Coast. Tap an article to read it on the official site.",
                style = MaterialTheme.typography.bodySmall)
            SourceStatus(state)
            TextButton(onClick = { uriHandler.openUri(HomeSources.NEWS) }) { Text("Open official news") }
        }
        items(state.data.orEmpty(), key = { it.url }) { article ->
            Card(Modifier.fillMaxWidth().clickable { uriHandler.openUri(article.url) }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(article.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(article.publishedDate?.let { "Wizards of the Coast - $it" } ?: "Wizards of the Coast",
                        style = MaterialTheme.typography.bodySmall)
                    Text("Read article", color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun BanSection(
    mode: HomeMode, onMode: (HomeMode) -> Unit, state: HomeLoadState<BanLists>, refresh: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    var query by rememberSaveable { mutableStateOf("") }
    val banned = state.data?.banned.orEmpty().filter { it.name.contains(query.trim(), ignoreCase = true) }
    val restricted = state.data?.restricted.orEmpty().filter { it.name.contains(query.trim(), ignoreCase = true) }
    Column {
        Column(Modifier.padding(horizontal = 16.dp)) {
            ModeSelector(HomeModes.withBanLists, mode, onMode)
            OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search cards in this format") }, singleLine = true)
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                SectionHeading("${mode.name} legality", state.loading, refresh)
                Text("Current Scryfall snapshot, not a chronological ban history. Rotation and cards outside the format's pool are not bans.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Check the official list and announcements for effective dates and event-specific rules.",
                    style = MaterialTheme.typography.bodySmall)
                SourceStatus(state)
                TextButton(onClick = { uriHandler.openUri(HomeSources.BANS) }) { Text("Official banned / restricted list") }
                TextButton(onClick = { uriHandler.openUri(HomeSources.NEWS + "/announcements") }) {
                    Text("Latest official announcements")
                }
            }
            if (state.data != null) {
                item {
                    Text("Banned (${state.data.banned.size})", style = MaterialTheme.typography.titleMedium)
                    Text("Cannot be included in your deck or sideboard.", style = MaterialTheme.typography.bodySmall)
                    if (state.data.banned.isEmpty()) Text("No banned cards reported for ${mode.name}.")
                    else if (banned.isEmpty()) Text("No banned cards match your search.")
                }
                items(banned, key = { "banned_${it.id}" }) { card -> LegalityRow(card, mode.name) }
                item {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("Restricted (${state.data.restricted.size})", style = MaterialTheme.typography.titleMedium)
                    Text("At most one copy across your deck and sideboard.", style = MaterialTheme.typography.bodySmall)
                    if (state.data.restricted.isEmpty()) Text("No restricted cards reported for ${mode.name}.")
                    else if (restricted.isEmpty()) Text("No restricted cards match your search.")
                }
                items(restricted, key = { "restricted_${it.id}" }) { card -> LegalityRow(card, mode.name) }
            }
        }
    }
}

@Composable
private fun LegalityRow(card: LegalityCard, format: String) {
    val uriHandler = LocalUriHandler.current
    OutlinedCard(Modifier.fillMaxWidth().clickable { uriHandler.openUri(card.url) }) {
        Column(Modifier.padding(12.dp)) {
            Text(card.name, fontWeight = FontWeight.SemiBold)
            Text("${if (card.status == "banned") "Banned" else "Restricted"} in $format",
                style = MaterialTheme.typography.bodySmall,
                color = if (card.status == "banned") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun RulesSection(mode: HomeMode, onMode: (HomeMode) -> Unit) {
    val uriHandler = LocalUriHandler.current
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Rulebook", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Quick references for each game mode, plus the full official rules. These summaries do not replace the rules or an event judge.",
                style = MaterialTheme.typography.bodySmall)
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Official rules library", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { uriHandler.openUri(HomeSources.BASICS) }) { Text("Learn to play / basic rules") }
                    TextButton(onClick = { uriHandler.openUri(HomeSources.RULES) }) { Text("Full Comprehensive Rules (PDF / text)") }
                    TextButton(onClick = { uriHandler.openUri(HomeSources.TOURNAMENTS) }) { Text("Tournament rules and policy") }
                    Text("Links open the publisher's current documents, not an outdated bundled copy.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { ModeSelector(HomeModes.all, mode, onMode) }
        item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(mode.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    mode.rules.forEachIndexed { index, rule -> Text("${index + 1}. $rule") }
                    TextButton(onClick = { uriHandler.openUri(mode.rulesUrl) }) { Text("Open official format rules") }
                    if (mode.legalityId == null) Text(
                        "Constructed ban lists do not define this mode's card pool. Follow the event's Limited pool or the underlying team format.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeSelector(modes: List<HomeMode>, selected: HomeMode, onSelect: (HomeMode) -> Unit) {
    var typeMenu by remember { mutableStateOf(false) }
    var formatMenu by remember { mutableStateOf(false) }
    val types = modes.map { it.matchType }.distinct()
    Column {
        Box {
            OutlinedButton(onClick = { typeMenu = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Match type: ${selected.matchType.label}")
            }
            DropdownMenu(typeMenu, { typeMenu = false }) {
                types.forEach { type: MatchType ->
                    DropdownMenuItem({ Text(type.label) }, {
                        typeMenu = false
                        if (type != selected.matchType) onSelect(modes.first { it.matchType == type })
                    })
                }
            }
        }
        Box {
            OutlinedButton(onClick = { formatMenu = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Format: ${selected.name}")
            }
            DropdownMenu(formatMenu, { formatMenu = false }) {
                modes.filter { it.matchType == selected.matchType }.forEach { mode ->
                    DropdownMenuItem({ Text(mode.name) }, { formatMenu = false; onSelect(mode) })
                }
            }
        }
    }
}

@Composable
private fun SectionHeading(title: String, loading: Boolean, refresh: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        TextButton(onClick = refresh, enabled = !loading) { Text("Refresh") }
    }
}

@Composable
private fun <T> SourceStatus(state: HomeLoadState<T>) {
    if (state.loading) {
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
        Text("Loading current source...", style = MaterialTheme.typography.bodySmall)
    }
    state.error?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (state.data != null) Text("Showing the previously fetched snapshot. Refresh to try again.",
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    state.fetchedAt?.let {
        Text("Last fetched: ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))}",
            style = MaterialTheme.typography.bodySmall)
    }
}
