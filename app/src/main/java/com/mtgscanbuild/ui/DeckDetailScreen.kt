package com.mtgscanbuild.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mtgscanbuild.data.CollectionCard
import com.mtgscanbuild.data.DeckCardEntity
import com.mtgscanbuild.data.formatUsd
import com.mtgscanbuild.data.legalityMap
import com.mtgscanbuild.deck.DeckCompare
import com.mtgscanbuild.deck.DeckTools
import com.mtgscanbuild.deck.Formats
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("DEPRECATION")
fun DeckDetailScreen(id: Long, onBack: () -> Unit) {
    val repo = rememberRepo()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val deck by remember(id) { repo.observeDeck(id) }.collectAsState(initial = null)
    val cards by remember(id) { repo.observeDeckCards(id) }.collectAsState(initial = emptyList())
    val collection by remember { repo.collection }.collectAsState(initial = emptyList())
    var menu by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<DeckCardEntity?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val d = deck
    val format = Formats.byId(d?.format ?: "commander")
    val issues = remember(cards, collection, d) { if (d == null) emptyList() else DeckTools.validate(format, cards, collection) }
    val index = remember(collection) { DeckCompare.OwnedIndex(collection) }
    fun unitPrice(c: DeckCardEntity) = index.cheapestPrice(c.name) ?: c.priceUsd
    val missingByName = remember(cards, index) {
        cards.groupBy { it.name }.mapValues { (name, rows) ->
            if (DeckTools.isBasic(name)) 0 else (rows.sumOf { it.quantity } - index.count(name)).coerceAtLeast(0)
        }
    }
    val deckValue = remember(cards, index) { cards.sumOf { (unitPrice(it) ?: 0.0) * it.quantity } }
    val missingCost = remember(cards, index) {
        cards.distinctBy { it.name }.sumOf { (unitPrice(it) ?: 0.0) * (missingByName[it.name] ?: 0) }
    }
    val uriHandler = LocalUriHandler.current

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(d?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${format.name} · ${cards.filter { it.section != "side" }.sumOf { it.quantity }} cards", style = MaterialTheme.typography.bodySmall)
                }
            },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = { showAdd = true }) { Icon(Icons.Filled.Add, "Add card") }
                IconButton(onClick = {
                    val text = DeckTools.exportText(cards)
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, d?.name)
                        putExtra(Intent.EXTRA_TEXT, text)
                    }, "Share deck list"))
                }) { Icon(Icons.Filled.Share, "Share") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Copy list (Arena/MTGO format)") }, {
                            menu = false
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("deck", DeckTools.exportText(cards)))
                        })
                        DropdownMenuItem({ Text("Copy missing cards (for TCGplayer Mass Entry)") }, {
                            menu = false
                            val text = missingByName.filter { it.value > 0 }.entries.sortedBy { it.key }
                                .joinToString("\n") { "${it.value} ${it.key}" }
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("missing", text))
                            Toast.makeText(ctx, if (text.isEmpty()) "You own every card" else "Missing cards copied", Toast.LENGTH_SHORT).show()
                        })
                        d?.sourceUrl?.let { url ->
                            DropdownMenuItem({ Text("Open on Moxfield") }, { menu = false; uriHandler.openUri(url) })
                        }
                        DropdownMenuItem({ Text("Rename") }, { menu = false; renaming = true })
                        DropdownMenuItem({ Text("Delete deck") }, { menu = false; confirmDelete = true })
                    }
                }
            }
        )
        if (d == null) return@Column
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                if (d.description.isNotBlank()) Text(d.description, style = MaterialTheme.typography.bodySmall)
                Text(
                    "Deck value ${formatUsd(deckValue)}" +
                        if (missingCost > 0) " · Cost to complete ${formatUsd(missingCost)}" else "",
                    style = MaterialTheme.typography.labelLarge
                )
                Text("TCGplayer market prices", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (issues.isEmpty()) Text("✓ Legal and fully owned", color = Color(0xFF66BB6A), style = MaterialTheme.typography.labelLarge)
                else issues.take(12).forEach { Text("• $it", color = Color(0xFFFFB74D), style = MaterialTheme.typography.bodySmall) }
                CurveBar(cards)
            }
            val groups = cards.groupBy { deckCategory(it.section, it.typeLine) }
            categoryOrder.forEach { cat ->
                val list = groups[cat] ?: return@forEach
                item(key = "h_$cat") { SectionHeader("$cat (${list.sumOf { it.quantity }})") }
                items(list, key = { it.id }) { c ->
                    val have = index.count(c.name)
                    val short = c.quantity > have && !DeckTools.isBasic(c.name)
                    val printings = index.printings(c.name)
                    Row(
                        Modifier.fillMaxWidth().clickable { preview = c }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(c.name, color = if (short) Color(0xFFFFB74D) else Color.Unspecified,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                when {
                                    printings.isNotEmpty() -> printings.joinToString(", ") { it.label }
                                    DeckTools.isBasic(c.name) -> "Basic land"
                                    else -> "Not owned"
                                } + " · ${formatUsd(unitPrice(c))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(compactCost(c.manaCost), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 6.dp))
                        QtyControl(c.quantity) { q -> scope.launch { repo.setDeckCardQuantity(c, q) } }
                    }
                }
            }
            item { Spacer(Modifier.padding(24.dp)) }
        }
    }

    preview?.let { c ->
        AlertDialog(
            onDismissRequest = { preview = null },
            confirmButton = { TextButton(onClick = { preview = null }) { Text("Close") } },
            dismissButton = {
                TextButton(onClick = { scope.launch { repo.setDeckCardQuantity(c, 0) }; preview = null }) { Text("Remove from deck") }
            },
            title = { Text(c.name) },
            text = {
                Column {
                    val img = c.imageUrl ?: collection.firstOrNull { it.card.name == c.name }?.card?.imageUrlLarge
                    if (img != null) AsyncImage(img, c.name, Modifier.fillMaxWidth().aspectRatio(63f / 88f))
                    Text(c.typeLine, style = MaterialTheme.typography.bodySmall)
                    Text("You own ${index.count(c.name)} · ${formatUsd(unitPrice(c))} each (TCGplayer)", style = MaterialTheme.typography.bodySmall)
                    index.printings(c.name).forEach { p ->
                        Text("• ${p.setName} (${p.label})", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        )
    }
    if (showAdd && d != null) AddFromCollectionDialog(
        collection = collection, formatId = format.id,
        identity = if (format.hasCommander) d.colors.toSet() else null,
        onPick = { card -> scope.launch { repo.addCardToDeck(id, card) }; showAdd = false },
        onDismiss = { showAdd = false }
    )
    if (renaming && d != null) {
        var name by remember { mutableStateOf(d.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename deck") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { scope.launch { repo.renameDeck(d, name) }; renaming = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete deck?") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; scope.launch { repo.deleteDeck(id); onBack() } }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

@Composable
private fun CurveBar(cards: List<DeckCardEntity>) {
    val spells = cards.filter { it.section == "main" && !it.typeLine.substringBefore(" // ").contains("Land") }
    if (spells.isEmpty()) return
    val counts = IntArray(8)
    spells.forEach { counts[it.cmc.toInt().coerceIn(0, 7)] += it.quantity }
    val max = counts.max().coerceAtLeast(1)
    val barColor = MaterialTheme.colorScheme.primary
    SectionHeader("Mana curve")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        counts.forEachIndexed { i, n ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$n", style = MaterialTheme.typography.labelSmall)
                Box(Modifier.width(18.dp).height((2 + 50 * n / max).dp).background(barColor))
                Text(if (i == 7) "7+" else "$i", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** Pick a card you own to add to a deck (filtered to format legality / commander colors). */
@Composable
private fun AddFromCollectionDialog(
    collection: List<CollectionCard>, formatId: String, identity: Set<Char>?,
    onPick: (com.mtgscanbuild.data.CardData) -> Unit, onDismiss: () -> Unit,
) {
    var q by remember { mutableStateOf("") }
    val options = remember(collection, formatId, identity) {
        collection.distinctBy { it.card.name }.filter {
            val l = it.card.legalityMap()[formatId]
            (l == "legal" || l == "restricted") && (identity == null || identity.containsAll(it.card.colorIdentity.toSet()))
        }.sortedBy { it.card.name }
    }
    val shown = options.filter { q.isBlank() || it.card.name.contains(q, true) }.take(100)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Add from collection") },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, singleLine = true, label = { Text("Search") }, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(shown, key = { it.id }) { c ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(c.card) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            CardThumb(c.card.imageUrl)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(c.card.name, fontWeight = FontWeight.SemiBold)
                                Text(c.card.typeLine, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    )
}
