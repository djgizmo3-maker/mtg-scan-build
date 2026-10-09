package com.mtgscanbuild.ui

import android.app.Application
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.mtgscanbuild.data.CollectionFolder
import com.mtgscanbuild.data.formatUsd
import com.mtgscanbuild.data.frontType
import com.mtgscanbuild.data.totalPrice
import com.mtgscanbuild.data.unitPrice
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

class CollectionViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = app.repo
    private val settings = app.settings
    val items = repo.collection.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val folders = repo.collectionFolders.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    var busy by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)

    init {
        // TCGplayer prices move daily; refresh them in the background at most once a day.
        viewModelScope.launch {
            val stale = System.currentTimeMillis() - settings.lastPriceRefresh > 24 * 60 * 60 * 1000L
            if (settings.autoRefreshPrices && stale && repo.allCards().isNotEmpty()) refresh(auto = true)
        }
    }

    fun setQty(c: CollectionCard, q: Int) = viewModelScope.launch { repo.setQuantity(c, q) }
    fun add(card: CardData) = viewModelScope.launch { repo.addCard(card, 1, false); message = "Added ${card.name}" }

    private fun work(label: String, block: suspend () -> String) = viewModelScope.launch {
        busy = label
        try {
            message = block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message = "Failed: ${e.message}"
        } finally {
            busy = null
        }
    }

    fun export(uri: Uri) = work("Exporting…") { "Exported ${repo.exportCsv(uri)} entries" }
    fun import(uri: Uri) = work("Importing…") {
        val r = repo.importCsv(uri) { busy = it }
        "Imported ${r.imported} cards" + if (r.failed.isNotEmpty())
            ". Not found (${r.failed.size}): " + r.failed.take(10).joinToString(", ") else ""
    }
    fun refresh(auto: Boolean = false) = work(if (auto) "Updating TCGplayer prices…" else "Refreshing card data…") {
        val n = repo.refreshCardData { d, t -> busy = "${if (auto) "Updating TCGplayer prices" else "Refreshing card data"} $d / $t…" }
        settings.lastPriceRefresh = System.currentTimeMillis()
        if (auto) "TCGplayer prices updated for $n cards" else "Updated $n cards (TCGplayer prices and legalities are now current)"
    }
    fun clear() = work("Clearing…") { repo.clearCollection(); "Collection cleared" }
    fun saveFolder(folder: CollectionFolder) = work("Saving folder...") {
        repo.saveFolder(folder); "Saved ${folder.name.trim()}"
    }
    fun deleteFolder(folder: CollectionFolder) = work("Deleting folder...") {
        repo.deleteFolder(folder.id); "Deleted ${folder.name}; its cards are now unfiled"
    }
    fun assignFolder(ids: List<Long>, folderId: Long?) = work("Moving cards...") {
        repo.assignFolder(ids, folderId); "Moved ${ids.size} card entries"
    }
}

private const val SORT_SET = "Set (A–Z)"
private const val SORT_SET_NEW = "Set (newest first)"
private val sortModes = listOf("Name", "Recently added", "Mana value", "Quantity", "Value (TCGplayer)", SORT_SET, SORT_SET_NEW)
private val typeFilters = listOf("All", "Creature", "Instant", "Sorcery", "Artifact", "Enchantment", "Planeswalker", "Land", "Battle")

private data class SetInfo(val code: String, val name: String, val releasedAt: String)

private fun collectorKey(n: String) = n.takeWhile { it.isDigit() }.padStart(6, '0') + n.dropWhile { it.isDigit() }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CollectionScreen(onOpen: (Long) -> Unit, onPro: () -> Unit, vm: CollectionViewModel = viewModel()) {
    val access = rememberRepo().access
    val items by vm.items.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    var userSets by rememberSaveable { mutableStateOf(false) }
    var folderView by rememberSaveable { mutableStateOf(true) }
    var selectedFolder by rememberSaveable { mutableStateOf<Long?>(null) }
    var showFolderCards by remember { mutableStateOf(false) }
    val activeFolder = selectedFolder?.let { id -> folders.find { it.id == id } }
    val inFolder = userSets && folderView && (selectedFolder == 0L || activeFolder != null)
    val sourceItems = if (inFolder) items.filter { it.folderId == activeFolder?.id } else items
    BackHandler(enabled = inFolder) { selectedFolder = null }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf("Name") }
    var type by remember { mutableStateOf("All") }
    var setFilter by remember { mutableStateOf<String?>(null) }
    var colorFilter by remember { mutableStateOf(setOf<Char>()) }
    var menu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var typeMenu by remember { mutableStateOf(false) }
    var setMenu by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { it?.let(vm::export) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::import) }

    val sets = remember(items) {
        items.groupBy { it.card.setCode }.map { (code, list) ->
            SetInfo(code, list.first().card.setName, list.first().card.releasedAt) to list.sumOf { it.quantity }
        }.sortedBy { it.first.name }
    }
    val totalValue = remember(items) { items.sumOf { it.totalPrice } }
    val bySet = sort == SORT_SET || sort == SORT_SET_NEW
    val setRelease = remember(items) {
        items.groupBy { it.card.setCode }.mapValues { e -> e.value.mapNotNull { it.card.releasedAt.ifEmpty { null } }.minOrNull() ?: "" }
    }

    val filtered = remember(sourceItems, query, sort, type, colorFilter, setFilter) {
        val q = query.trim().lowercase()
        sourceItems.asSequence()
            .filter { q.isEmpty() || it.card.name.lowercase().contains(q) || it.card.typeLine.lowercase().contains(q) || it.card.oracleText.lowercase().contains(q) }
            .filter { type == "All" || it.card.frontType.contains(type) }
            .filter { setFilter == null || it.card.setCode == setFilter }
            .filter { c ->
                colorFilter.isEmpty() || colorFilter.any { f ->
                    if (f == 'C') c.card.colorIdentity.isEmpty() else f in c.card.colorIdentity
                }
            }
            .let { s ->
                val inSet = compareBy<CollectionCard>({ collectorKey(it.card.collectorNumber) }, { it.card.name })
                when (sort) {
                    "Recently added" -> s.sortedByDescending { it.addedAt }
                    "Mana value" -> s.sortedWith(compareBy({ it.card.cmc }, { it.card.name }))
                    "Quantity" -> s.sortedByDescending { it.quantity }
                    "Value (TCGplayer)" -> s.sortedWith(compareByDescending<CollectionCard> { it.unitPrice ?: -1.0 }.thenBy { it.card.name })
                    SORT_SET -> s.sortedWith(compareBy<CollectionCard>({ it.card.setName }, { it.card.setCode }).then(inSet))
                    SORT_SET_NEW -> s.sortedWith(compareByDescending<CollectionCard> { setRelease[it.card.setCode] ?: "" }
                        .thenBy { it.card.setName }.thenBy { it.card.setCode }.then(inSet))
                    else -> s.sortedBy { it.card.name }
                }
            }.toList()
    }
    val groups = remember(filtered, bySet) {
        if (!bySet) emptyList() else
            filtered.groupBy { it.card.setCode }.map { (code, list) ->
                SetInfo(code, list.first().card.setName, setRelease[code] ?: "") to list
            }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(if (inFolder) activeFolder?.name ?: "Unfiled" else "Collection")
                    Text("${items.sumOf { it.quantity }.let { "$it card${if (it == 1) "" else "s"}" }} · ${items.map { it.card.name }.distinct().size} unique · ${formatUsd(totalValue)}",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            actions = {
                IconButton(onClick = { showAdd = true }) { Icon(Icons.Filled.Add, "Add card") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Import CSV / list${if (access.hasPro) "" else " (Pro)"}") }, {
                            menu = false
                            if (access.hasPro) importLauncher.launch(arrayOf("text/*", "application/*")) else onPro()
                        })
                        DropdownMenuItem({ Text("Export CSV${if (access.hasPro) "" else " (Pro)"}") }, {
                            menu = false
                            if (access.hasPro) exportLauncher.launch("mtg-collection.csv") else onPro()
                        })
                        DropdownMenuItem({ Text("Refresh TCGplayer prices & legality") }, { menu = false; vm.refresh() })
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
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!userSets, { userSets = false }, label = { Text("Collection") })
            FilterChip(userSets, { userSets = true }, label = { Text("User Created Sets") })
        }
        if (userSets) {
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!folderView, { folderView = false }, label = { Text("Full list") })
                FilterChip(folderView, { folderView = true }, label = { Text("Folders") })
            }
            if (folderView && !inFolder) {
                FolderBrowser(items, folders, onOpen = { selectedFolder = it },
                    onSave = vm::saveFolder, onDelete = vm::deleteFolder,
                    onAll = { folderView = false }, canCreate = access.canCreateFolder(folders.size), onPro = onPro)
                return@Column
            }
            if (inFolder) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { selectedFolder = null }) { Text("Back to folders") }
                    Text(formatUsd(sourceItems.sumOf { it.totalPrice }), Modifier.weight(1f))
                    if (activeFolder != null) TextButton(onClick = { showFolderCards = true }) { Text("Add cards") }
                }
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
            Box {
                OutlinedButton(onClick = { setMenu = true }) {
                    Text(setFilter?.let { code -> "Set: ${code.uppercase()}" } ?: "Set: All", maxLines = 1)
                }
                DropdownMenu(setMenu, { setMenu = false }) {
                    DropdownMenuItem({ Text("All sets (${sets.size})") }, { setFilter = null; setMenu = false })
                    sets.forEach { (info, count) ->
                        DropdownMenuItem(
                            { Text("${info.name} (${info.code.uppercase()}) · $count") },
                            { setFilter = info.code; setMenu = false }
                        )
                    }
                }
            }
        }
        if (sourceItems.isEmpty()) {
            Text(
                if (inFolder) "No cards in this folder. Move existing cards here with Add cards or from a card's details."
                else "Your collection is empty. Scan cards on the Scan tab, add them by name with +, or import a CSV.",
                Modifier.padding(24.dp)
            )
        } else if (filtered.isEmpty()) {
            Text("No cards match your filters.", Modifier.padding(24.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (bySet) {
                groups.forEach { (info, list) ->
                    stickyHeader(key = "set_${info.code}") {
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("${info.name} (${info.code.uppercase()})", fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        listOfNotNull(info.releasedAt.takeIf { it.isNotEmpty() }?.take(4),
                                            "${list.sumOf { it.quantity }} cards").joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Text(formatUsd(list.sumOf { it.totalPrice }), style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                    items(list, key = { it.id }) { c -> CollectionRow(c, onOpen, vm) }
                }
            } else {
                items(filtered, key = { it.id }) { c -> CollectionRow(c, onOpen, vm) }
            }
        }
    }

    if (showAdd) SearchCardDialog(onPicked = { vm.add(it); showAdd = false }, onDismiss = { showAdd = false })
    if (showFolderCards && activeFolder != null) FolderCardsDialog(
        items, folders, activeFolder,
        onMove = { ids -> vm.assignFolder(ids, activeFolder.id); showFolderCards = false },
        onDismiss = { showFolderCards = false }
    )
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear collection?") },
        text = { Text("This removes every card from your inventory and cannot be undone. " +
            if (access.hasPro) "Export a CSV first if you want a backup." else "No CSV backup is available on Basic.") },
        confirmButton = { TextButton(onClick = { confirmClear = false; vm.clear() }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}

@Composable
private fun CollectionRow(c: CollectionCard, onOpen: (Long) -> Unit, vm: CollectionViewModel) {
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
        Column(horizontalAlignment = Alignment.End) {
            QtyControl(c.quantity, onChange = { vm.setQty(c, it) })
            Text(formatUsd(c.unitPrice), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider()
}

@Composable
fun QtyControl(qty: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { onChange(qty - 1) }, Modifier.size(34.dp), contentPadding = PaddingValues(0.dp)) { Text("−") }
        Text("$qty", Modifier.padding(horizontal = 8.dp), fontWeight = FontWeight.Bold)
        OutlinedButton(onClick = { onChange(qty + 1) }, Modifier.size(34.dp), contentPadding = PaddingValues(0.dp)) { Text("+") }
    }
}
