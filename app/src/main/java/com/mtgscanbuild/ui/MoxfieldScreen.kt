package com.mtgscanbuild.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mtgscanbuild.data.MoxDeck
import com.mtgscanbuild.data.MoxDeckSummary
import com.mtgscanbuild.data.MoxfieldApi
import com.mtgscanbuild.data.formatUsd
import com.mtgscanbuild.deck.ComparedCard
import com.mtgscanbuild.deck.DeckCompare
import com.mtgscanbuild.deck.DeckComparison
import com.mtgscanbuild.deck.Format
import com.mtgscanbuild.deck.Formats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A Moxfield search hit; [deck] is filled in once its card list has been downloaded. */
data class MoxResult(val summary: MoxDeckSummary, val deck: MoxDeck? = null, val error: String? = null)

class MoxfieldViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = app.repo
    val collection = repo.collection.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    var format by mutableStateOf(Formats.byId(app.settings.defaultFormat))
    var filter by mutableStateOf("")
    var link by mutableStateOf("")
    var results by mutableStateOf<List<MoxResult>>(emptyList())
    var status by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var searched by mutableStateOf(false)
    var selected by mutableStateOf<MoxDeck?>(null)
    private var page = 1
    private var job: Job? = null

    fun search() {
        page = 1
        results = emptyList()
        load()
    }

    fun loadMore() { page++; load() }

    private fun load() {
        job?.cancel()
        job = viewModelScope.launch {
            val me = coroutineContext[Job]
            busy = true; error = null; searched = true
            try {
                status = "Searching Moxfield…"
                val found = repo.moxfield.search(format.id, filter, page)
                val known = results.map { it.summary.publicId }.toSet()
                results = results + found.filter { it.publicId !in known }.map { MoxResult(it) }
                // Moxfield search results don't include card lists, so fetch each deck (rate-limited).
                val pending = results.filter { it.deck == null && it.error == null }
                pending.forEachIndexed { i, r ->
                    status = "Comparing deck ${i + 1} of ${pending.size} with your collection…"
                    val updated = try {
                        val d = repo.moxfield.deck(r.summary.publicId)
                        if (d == null) r.copy(error = "Deck not available") else r.copy(deck = d)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        r.copy(error = e.message ?: "Network error")
                    }
                    results = results.map { if (it.summary.publicId == r.summary.publicId) updated else it }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Network error"
            } finally {
                if (job === me) { status = null; busy = false }
            }
        }
    }

    fun openLink() {
        val id = MoxfieldApi.extractId(link)
        if (id == null) { error = "That doesn't look like a Moxfield deck link."; return }
        job?.cancel()
        job = viewModelScope.launch {
            val me = coroutineContext[Job]
            busy = true; error = null; status = "Loading deck from Moxfield…"
            try {
                val d = repo.moxfield.deck(id)
                if (d == null) error = "Deck not found (it may be private)." else selected = d
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Network error"
            } finally {
                if (job === me) { status = null; busy = false }
            }
        }
    }

    /** App format for a Moxfield deck, falling back to the selected one. */
    fun appFormatFor(deck: MoxDeck): Format =
        Formats.all.firstOrNull { MoxfieldApi.formatFor(it.id).equals(deck.format, ignoreCase = true) } ?: format

    fun save(deck: MoxDeck, onSaved: (Long) -> Unit) = viewModelScope.launch {
        onSaved(repo.saveMoxfieldDeck(deck, appFormatFor(deck).id))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("DEPRECATION")
fun MoxfieldScreen(onBack: () -> Unit, onSaved: (Long) -> Unit, vm: MoxfieldViewModel = viewModel()) {
    val collection by vm.collection.collectAsStateWithLifecycle()
    val index = remember(collection) { DeckCompare.OwnedIndex(collection) }
    val sel = vm.selected
    if (sel != null) {
        BackHandler { vm.selected = null }
        MoxDeckDetail(sel, remember(sel, index) { DeckCompare.compare(sel, index) }, vm.appFormatFor(sel),
            onBack = { vm.selected = null }, onSave = { vm.save(sel, onSaved) })
        return
    }

    var formatMenu by remember { mutableStateOf(false) }
    var sortByOwned by remember { mutableStateOf(true) }
    val compared = remember(vm.results, index) {
        vm.results.map { r -> r to r.deck?.let { DeckCompare.compare(it, index) } }
    }
    val shown = if (sortByOwned) compared.sortedWith(
        compareByDescending<Pair<MoxResult, DeckComparison?>> { it.second?.percentOwned ?: -1 }
            .thenBy { it.second?.missingCost ?: Double.MAX_VALUE }
    ) else compared

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Compare with Moxfield") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") } }
        )
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                Text("Find popular Moxfield decks and see how much of each you already own.",
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Text("Format", style = MaterialTheme.typography.labelLarge)
                Box {
                    OutlinedButton(onClick = { formatMenu = true }, Modifier.fillMaxWidth()) { Text(vm.format.name) }
                    DropdownMenu(formatMenu, { formatMenu = false }) {
                        Formats.all.forEach { f ->
                            DropdownMenuItem({ Text(f.name) }, { formatMenu = false; vm.format = f })
                        }
                    }
                }
                OutlinedTextField(
                    vm.filter, { vm.filter = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Commander, card or deck name (optional)") }
                )
                Spacer(Modifier.height(6.dp))
                Button(onClick = vm::search, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Search Moxfield & compare")
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    vm.link, { vm.link = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("…or paste a Moxfield deck link") },
                    placeholder = { Text("https://moxfield.com/decks/…") }
                )
                OutlinedButton(onClick = vm::openLink, enabled = !vm.busy && vm.link.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text("Compare this deck")
                }
                vm.status?.let {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (vm.results.isNotEmpty()) {
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(sortByOwned, { sortByOwned = true }, label = { Text("Most owned first") })
                        FilterChip(!sortByOwned, { sortByOwned = false }, label = { Text("Most viewed first") })
                    }
                } else if (vm.searched && !vm.busy && vm.error == null) {
                    Text("No public decks found.", style = MaterialTheme.typography.bodySmall)
                }
            }
            items(shown, key = { it.first.summary.publicId }) { (r, cmp) ->
                MoxResultCard(r, cmp) { r.deck?.let { vm.selected = it } }
            }
            if (vm.results.isNotEmpty() && !vm.busy) item {
                OutlinedButton(onClick = vm::loadMore, Modifier.fillMaxWidth()) { Text("Load more decks") }
            }
            item {
                Text("Deck lists from Moxfield. Prices are TCGplayer market prices.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp))
            }
        }
    }
}

@Composable
private fun MoxResultCard(r: MoxResult, cmp: DeckComparison?, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(enabled = r.deck != null, onClick = onOpen)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.summary.name, Modifier.weight(1f), fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                ColorPips(r.summary.colorIdentity)
            }
            Text("by ${r.summary.author} · ${"%,d".format(r.summary.views)} views · ${r.summary.likes} likes",
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            when {
                cmp != null -> {
                    LinearProgressIndicator(progress = { cmp.percentOwned / 100f }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "You own ${cmp.percentOwned}% (${cmp.have}/${cmp.total})" +
                            if (cmp.missing > 0) " · ${cmp.missing} missing · ${formatUsd(cmp.missingCost)} to complete" else " · complete!",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (cmp.missing == 0) Color(0xFF66BB6A) else Color.Unspecified
                    )
                }
                r.error != null -> Text(r.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                else -> Text("Waiting to compare…", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("DEPRECATION")
private fun MoxDeckDetail(deck: MoxDeck, cmp: DeckComparison, format: Format, onBack: () -> Unit, onSave: () -> Unit) {
    val ctx = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var missingOnly by remember { mutableStateOf(false) }
    val list = if (missingOnly) cmp.missingCards else cmp.cards
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(deck.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${format.name} · by ${deck.author}", style = MaterialTheme.typography.bodySmall)
                }
            },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") } }
        )
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                LinearProgressIndicator(progress = { cmp.percentOwned / 100f }, modifier = Modifier.fillMaxWidth())
                Text("You own ${cmp.percentOwned}% · ${cmp.have} of ${cmp.total} cards", fontWeight = FontWeight.Bold)
                Text("Deck value ${formatUsd(cmp.deckValue)} · Cost to complete ${formatUsd(cmp.missingCost)} (TCGplayer)",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    Button(onClick = onSave) { Text("Save to my decks") }
                    OutlinedButton(onClick = { uriHandler.openUri(deck.url) }) { Text("Moxfield") }
                }
                if (cmp.missing > 0) OutlinedButton(onClick = {
                    val text = cmp.missingCards.groupBy { it.name }.entries.sortedBy { it.key }
                        .joinToString("\n") { (name, rows) -> "${rows.sumOf { it.missing }} $name" }
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("missing", text))
                    Toast.makeText(ctx, "Missing cards copied (paste into TCGplayer Mass Entry)", Toast.LENGTH_SHORT).show()
                }, Modifier.fillMaxWidth()) { Text("Copy missing cards list") }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(!missingOnly, { missingOnly = false }, label = { Text("All cards") })
                    FilterChip(missingOnly, { missingOnly = true }, label = { Text("Missing only (${cmp.missing})") })
                }
            }
            val groups = list.groupBy { deckCategory(it.section, it.typeLine) }
            categoryOrder.forEach { cat ->
                val cards = groups[cat] ?: return@forEach
                item(key = "h_$cat") { SectionHeader("$cat (${cards.sumOf { it.quantity }})") }
                items(cards.sortedWith(compareBy({ it.cmc }, { it.name }))) { c ->
                    ComparedRow(c)
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ComparedRow(c: ComparedCard) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${c.quantity}", Modifier.width(28.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (c.missing > 0) Color(0xFFFFB74D) else Color.Unspecified)
            Text(
                when {
                    c.printings.isNotEmpty() -> "Yours: " + c.printings.joinToString(", ") { it.label }
                    c.missing == 0 -> "Basic land"
                    else -> "Not in your collection"
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(if (c.missing > 0) "Need ${c.missing}" else "✓", style = MaterialTheme.typography.labelMedium,
                color = if (c.missing > 0) Color(0xFFFFB74D) else Color(0xFF66BB6A))
            Text(formatUsd(c.unitPrice), style = MaterialTheme.typography.labelSmall)
        }
    }
    HorizontalDivider()
}
