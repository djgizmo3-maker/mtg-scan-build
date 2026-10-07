package com.mtgscanbuild.ui

import android.app.Application
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mtgscanbuild.data.formatUsd
import com.mtgscanbuild.deck.BuildOptions
import com.mtgscanbuild.deck.BuiltDeck
import com.mtgscanbuild.deck.DeckBuilder
import com.mtgscanbuild.deck.DeckCompare
import com.mtgscanbuild.deck.Format
import com.mtgscanbuild.deck.Formats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------- saved decks list

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DecksScreen(onBuild: () -> Unit, onOpen: (Long) -> Unit, onMoxfield: () -> Unit) {
    val repo = rememberRepo()
    val decks by remember { repo.savedDecks }.collectAsState(initial = emptyList())
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Decks") }, actions = {
                TextButton(onClick = onMoxfield) { Text("Moxfield") }
            })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onBuild, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Build a deck") })
        }
    ) { pad ->
        if (decks.isEmpty()) {
            Text(
                "No saved decks yet.\n\nTap \"Build a deck\" to generate playable decks for any format using only the cards in your collection, or tap \"Moxfield\" to compare your collection with popular Moxfield decks.",
                Modifier.padding(pad).padding(24.dp)
            )
        }
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            items(decks, key = { it.id }) { d ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(d.id) }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(d.name, fontWeight = FontWeight.SemiBold)
                        Text(Formats.byId(d.format).name, style = MaterialTheme.typography.bodySmall)
                    }
                    ColorPips(d.colors)
                }
                HorizontalDivider()
            }
        }
    }
}

// ---------------------------------------------------------------- builder

class BuilderViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = app.repo
    private val settings = app.settings
    var format by mutableStateOf(Formats.byId(settings.defaultFormat))
    var colors by mutableStateOf(setOf<Char>())
    var commander by mutableStateOf<String?>(null)
    var buildAround by mutableStateOf("")
    var assumeBasics by mutableStateOf(settings.assumeBasics)
    var commanderOptions by mutableStateOf<List<String>>(emptyList())
    var results by mutableStateOf<List<BuiltDeck>?>(null)
    var building by mutableStateOf(false)
    var legalCount by mutableStateOf(0)
    var owned by mutableStateOf(DeckCompare.OwnedIndex(emptyList()))
    private var job: Job? = null

    init { selectFormat(format) }

    fun selectFormat(f: Format) {
        format = f
        commander = null
        results = null
        viewModelScope.launch {
            val all = repo.allCards()
            val (cmds, legal) = withContext(Dispatchers.Default) {
                val b = DeckBuilder(all)
                val pool = b.pool(f)
                (if (f.hasCommander) b.commanderCandidates(f).map { it.name } else emptyList()) to pool.size
            }
            commanderOptions = cmds
            legalCount = legal
        }
    }

    fun build() {
        job?.cancel()
        job = viewModelScope.launch {
            building = true
            results = null
            val all = repo.allCards()
            val opts = BuildOptions(colors, commander, buildAround.trim().ifEmpty { null }, assumeBasics)
            val f = format
            val (index, built) = withContext(Dispatchers.Default) {
                DeckCompare.OwnedIndex(all) to DeckBuilder(all).build(f, opts)
            }
            owned = index
            results = built
            building = false
        }
    }

    fun save(d: BuiltDeck, onSaved: (Long) -> Unit) = viewModelScope.launch { onSaved(repo.saveDeck(d)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("DEPRECATION")
fun BuilderScreen(onBack: () -> Unit, onSaved: (Long) -> Unit, onMoxfield: () -> Unit, vm: BuilderViewModel = viewModel()) {
    var formatMenu by remember { mutableStateOf(false) }
    var cmdMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Build a deck") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") } }
        )
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                Text("Format", style = MaterialTheme.typography.labelLarge)
                Box {
                    OutlinedButton(onClick = { formatMenu = true }, Modifier.fillMaxWidth()) { Text(vm.format.name) }
                    DropdownMenu(formatMenu, { formatMenu = false }) {
                        Formats.all.forEach { f ->
                            DropdownMenuItem(
                                text = { Column { Text(f.name); Text(f.note, style = MaterialTheme.typography.bodySmall) } },
                                onClick = { formatMenu = false; vm.selectFormat(f) }
                            )
                        }
                    }
                }
                Text("${vm.legalCount} unique card${if (vm.legalCount == 1) "" else "s"} in your collection ${if (vm.legalCount == 1) "is" else "are"} legal in ${vm.format.name}.",
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))

                Text(
                    if (vm.format.hasCommander) "Color identity (optional, exact match)" else "Colors (optional — empty tries every 1-3 color combination)",
                    style = MaterialTheme.typography.labelLarge
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    "WUBRG".forEach { c ->
                        FilterChip(selected = c in vm.colors, onClick = {
                            vm.colors = if (c in vm.colors) vm.colors - c else vm.colors + c
                        }, label = { Text(c.toString()) })
                    }
                }

                if (vm.format.hasCommander) {
                    Spacer(Modifier.height(8.dp))
                    Text(if (vm.format.id == "oathbreaker") "Oathbreaker" else "Commander", style = MaterialTheme.typography.labelLarge)
                    Box {
                        OutlinedButton(onClick = { cmdMenu = true }, Modifier.fillMaxWidth()) {
                            Text(vm.commander ?: "Automatic (${vm.commanderOptions.size} possible commander${if (vm.commanderOptions.size == 1) "" else "s"})")
                        }
                        DropdownMenu(cmdMenu, { cmdMenu = false }) {
                            DropdownMenuItem({ Text("Automatic") }, { vm.commander = null; cmdMenu = false })
                            vm.commanderOptions.forEach { n ->
                                DropdownMenuItem({ Text(n) }, { vm.commander = n; cmdMenu = false })
                            }
                        }
                    }
                    if (vm.commanderOptions.isEmpty())
                        Text("No eligible commanders in your collection for this format yet.",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    vm.buildAround, { vm.buildAround = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Build around a card (optional)") },
                    placeholder = { Text("Exact card name from your collection") }
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                    Switch(vm.assumeBasics, { vm.assumeBasics = it })
                    Text("  I have plenty of basic lands", style = MaterialTheme.typography.bodyMedium)
                }
                Button(onClick = vm::build, enabled = !vm.building, modifier = Modifier.fillMaxWidth()) {
                    Text(if (vm.building) "Building…" else "Generate decks from my collection")
                }
                OutlinedButton(onClick = onMoxfield, modifier = Modifier.fillMaxWidth()) {
                    Text("Compare my collection with Moxfield decks")
                }
                if (vm.building) Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                Spacer(Modifier.height(12.dp))
                val r = vm.results
                if (r != null && r.isEmpty()) Text(
                    "Couldn't build a deck from your collection with these settings. Scan more cards, or try a different format or colors.",
                    color = MaterialTheme.colorScheme.error
                )
            }
            items(vm.results ?: emptyList()) { d -> BuiltDeckCard(d, vm.owned, onSave = { vm.save(d, onSaved) }) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun BuiltDeckCard(d: BuiltDeck, owned: DeckCompare.OwnedIndex, onSave: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val value = remember(d, owned) { d.entries.sumOf { e -> (owned.cheapestPrice(e.name) ?: e.priceUsd ?: 0.0) * e.quantity } }
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d.name, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                ColorPips(d.colors)
            }
            Text(
                (if (d.complete) "Playable · ${d.cardCount} cards" else "Incomplete · ${d.cardCount} cards") +
                    " · ${formatUsd(value)}",
                color = if (d.complete) Color(0xFF66BB6A) else Color(0xFFFFB74D),
                style = MaterialTheme.typography.labelMedium
            )
            Text(d.description, style = MaterialTheme.typography.bodySmall)
            d.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFB74D)) }
            Row {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide list" else "Show list") }
                Spacer(Modifier.weight(1f))
                Button(onClick = onSave) { Text("Save deck") }
            }
            if (expanded) {
                val groups = d.entries.groupBy { deckCategory(it.section, it.typeLine) }
                categoryOrder.forEach { cat ->
                    val list = groups[cat] ?: return@forEach
                    SectionHeader("$cat (${list.sumOf { it.quantity }})")
                    list.sortedWith(compareBy({ it.cmc }, { it.name })).forEach { e ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${e.quantity}", Modifier.width(28.dp))
                            Column(Modifier.weight(1f)) {
                                Text(e.name, style = MaterialTheme.typography.bodyMedium)
                                val sets = owned.printings(e.name)
                                if (sets.isNotEmpty()) Text(sets.joinToString(", ") { it.label },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            Text(compactCost(e.manaCost), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
