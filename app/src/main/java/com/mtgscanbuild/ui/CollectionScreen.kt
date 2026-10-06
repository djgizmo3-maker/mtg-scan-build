package com.mtgscanbuild.ui

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mtgscanbuild.data.CardData
import com.mtgscanbuild.data.CollectionCard
import com.mtgscanbuild.data.frontType
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CollectionViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = app.repo
    val items = repo.collection.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    var busy by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)

    fun setQty(c: CollectionCard, q: Int) = viewModelScope.launch { repo.setQuantity(c, q) }
    fun add(card: CardData) = viewModelScope.launch { repo.addCard(card, 1, false); message = "Added ${card.name}" }

    private fun work(label: String, block: suspend () -> String) = viewModelScope.launch {
        busy = label
        message = try { block() } catch (e: Exception) { "Failed: ${e.message}" }
        busy = null
    }

    fun export(uri: Uri) = work("Exporting…") { "Exported ${repo.exportCsv(uri)} entries" }
    fun import(uri: Uri) = work("Importing…") {
        val r = repo.importCsv(uri) { busy = it }
        "Imported ${r.imported} cards" + if (r.failed.isNotEmpty())
            ". Not found (${r.failed.size}): " + r.failed.take(10).joinToString(", ") else ""
    }
    fun refresh() = work("Refreshing card data…") {
        "Updated ${repo.refreshCardData { d, t -> busy = "Refreshing card data $d / $t…" }} cards (legalities are now current)"
    }
    fun clear() = work("Clearing…") { repo.clearCollection(); "Collection cleared" }
}

private val sortModes = listOf("Name", "Recently added", "Mana value", "Quantity", "Set")
private val typeFilters = listOf("All", "Creature", "Instant", "Sorcery", "Artifact", "Enchantment", "Planeswalker", "Land", "Battle")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionScreen(onOpen: (Long) -> Unit, vm: CollectionViewModel = viewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf("Name") }
    var type by remember { mutableStateOf("All") }
    var colorFilter by remember { mutableStateOf(setOf<Char>()) }
    var menu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var typeMenu by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { it?.let(vm::export) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::import) }

    val filtered = remember(items, query, sort, type, colorFilter) {
        val q = query.trim().lowercase()
        items.asSequence()
            .filter { q.isEmpty() || it.card.name.lowercase().contains(q) || it.card.typeLine.lowercase().contains(q) || it.card.oracleText.lowercase().contains(q) }
            .filter { type == "All" || it.card.frontType.contains(type) }
            .filter { c ->
                colorFilter.isEmpty() || colorFilter.any { f ->
                    if (f == 'C') c.card.colorIdentity.isEmpty() else f in c.card.colorIdentity
                }
            }
            .let { s ->
                when (sort) {
                    "Recently added" -> s.sortedByDescending { it.addedAt }
                    "Mana value" -> s.sortedWith(compareBy({ it.card.cmc }, { it.card.name }))
                    "Quantity" -> s.sortedByDescending { it.quantity }
                    "Set" -> s.sortedWith(compareBy({ it.card.setCode }, { it.card.collectorNumber.padStart(5, '0') }))
                    else -> s.sortedBy { it.card.name }
                }
            }.toList()
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text("Collection")
                    Text("${items.sumOf { it.quantity }.let { "$it card${if (it == 1) "" else "s"}" }} · ${items.map { it.card.name }.distinct().size} unique",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            actions = {
                IconButton(onClick = { showAdd = true }) { Icon(Icons.Filled.Add, "Add card") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Import CSV / list") }, { menu = false; importLauncher.launch(arrayOf("text/*", "application/*")) })
                        DropdownMenuItem({ Text("Export CSV") }, { menu = false; exportLauncher.launch("mtg-collection.csv") })
                        DropdownMenuItem({ Text("Refresh card data & legality") }, { menu = false; vm.refresh() })
                        DropdownMenuItem({ Text("Clear collection") }, { menu = false; confirmClear = true })
                    }
                }
            }
        )
        vm.busy?.let {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(it, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
        }
        vm.message?.let {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { vm.message = null }) { Text("OK") }
            }
        }
        OutlinedTextField(
            query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            singleLine = true, placeholder = { Text("Search name, type or rules text") }
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            "WUBRGC".forEach { c ->
                FilterChip(selected = c in colorFilter, onClick = {
                    colorFilter = if (c in colorFilter) colorFilter - c else colorFilter + c
                }, label = { Text(c.toString()) })
            }
            Box {
                OutlinedButton(onClick = { typeMenu = true }) { Text(type) }
                DropdownMenu(typeMenu, { typeMenu = false }) {
                    typeFilters.forEach { t -> DropdownMenuItem({ Text(t) }, { type = t; typeMenu = false }) }
                }
            }
            Box {
                OutlinedButton(onClick = { sortMenu = true }) { Text("Sort: $sort") }
                DropdownMenu(sortMenu, { sortMenu = false }) {
                    sortModes.forEach { s -> DropdownMenuItem({ Text(s) }, { sort = s; sortMenu = false }) }
                }
            }
        }
        if (items.isEmpty()) {
            Text(
                "Your collection is empty. Scan cards on the Scan tab, add them by name with +, or import a CSV.",
                Modifier.padding(24.dp)
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(filtered, key = { it.id }) { c ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(c.id) }.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CardThumb(c.card.imageUrl)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.card.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${c.card.setCode.uppercase()} #${c.card.collectorNumber} · ${compactCost(c.card.manaCost)}${if (c.foil) " · FOIL" else ""}",
                            style = MaterialTheme.typography.bodySmall, maxLines = 1
                        )
                        Text(c.card.typeLine, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    QtyControl(c.quantity, onChange = { vm.setQty(c, it) })
                }
                HorizontalDivider()
            }
        }
    }

    if (showAdd) SearchCardDialog(onPicked = { vm.add(it); showAdd = false }, onDismiss = { showAdd = false })
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear collection?") },
        text = { Text("This removes every card from your inventory. Export a CSV first if you want a backup.") },
        confirmButton = { TextButton(onClick = { confirmClear = false; vm.clear() }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}

@Composable
fun QtyControl(qty: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { onChange(qty - 1) }, Modifier.size(34.dp), contentPadding = PaddingValues(0.dp)) { Text("−") }
        Text("$qty", Modifier.padding(horizontal = 8.dp), fontWeight = FontWeight.Bold)
        OutlinedButton(onClick = { onChange(qty + 1) }, Modifier.size(34.dp), contentPadding = PaddingValues(0.dp)) { Text("+") }
    }
}
