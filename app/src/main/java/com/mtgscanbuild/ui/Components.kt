package com.mtgscanbuild.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mtgscanbuild.MtgApp
import com.mtgscanbuild.data.CardData
import com.mtgscanbuild.data.Repository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun rememberRepo(): Repository {
    val ctx = LocalContext.current
    return remember { (ctx.applicationContext as MtgApp).repo }
}

@Composable
fun CardThumb(url: String?, modifier: Modifier = Modifier.size(width = 48.dp, height = 67.dp)) {
    val ctx = LocalContext.current
    val show = remember { (ctx.applicationContext as MtgApp).settings }.showImages
    if (show) AsyncImage(model = url, contentDescription = null, modifier = modifier.clip(RoundedCornerShape(4.dp)))
    else Box(modifier.clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
}

fun compactCost(cost: String) = cost.replace("}{", " ").replace("{", "").replace("}", "")

/** Card picture by name from Scryfall, used when a deck entry has no stored image. */
fun scryfallImageByName(name: String) =
    "https://api.scryfall.com/cards/named?format=image&version=normal&exact=" + android.net.Uri.encode(name)

/** Large picture of a card plus a few lines of info. */
@Composable
fun CardPreviewDialog(
    name: String,
    imageUrl: String?,
    typeLine: String,
    details: List<String>,
    onDismiss: () -> Unit,
    extraButton: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = extraButton,
        title = { Text(name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                AsyncImage(
                    model = imageUrl ?: scryfallImageByName(name), contentDescription = name,
                    modifier = Modifier.fillMaxWidth().aspectRatio(63f / 88f).clip(RoundedCornerShape(12.dp))
                )
                Spacer(Modifier.height(8.dp))
                if (typeLine.isNotBlank()) Text(typeLine, style = MaterialTheme.typography.bodySmall)
                details.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    )
}

/** Lets the user choose a specific printing (set) of a card. */
@Composable
fun PrintingDialog(name: String, onPick: (CardData) -> Unit, onDismiss: () -> Unit) {
    val repo = rememberRepo()
    var prints by remember { mutableStateOf<List<CardData>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(name) {
        try { prints = repo.api.prints(name) } catch (e: Exception) { error = e.message ?: "Network error" }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Choose printing") },
        text = {
            when {
                error != null -> Text(error!!)
                prints == null -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    items(prints!!) { c ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(c) }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CardThumb(c.imageUrl)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(c.setName, fontWeight = FontWeight.SemiBold)
                                Text("${c.setCode.uppercase()} #${c.collectorNumber} · ${c.rarity}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    )
}

/** Search any Magic card by name (Scryfall autocomplete) and return its data. */
@Composable
fun SearchCardDialog(title: String = "Add card by name", onPicked: (CardData) -> Unit, onDismiss: () -> Unit) {
    val repo = rememberRepo()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(query) {
        if (query.length < 2) { suggestions = emptyList(); return@LaunchedEffect }
        delay(300)
        try { suggestions = repo.api.autocomplete(query); error = null } catch (e: Exception) { error = e.message }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("Card name") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                if (loading) CircularProgressIndicator()
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(suggestions) { n ->
                        Text(n, Modifier.fillMaxWidth().clickable {
                            loading = true
                            scope.launch {
                                try {
                                    val c = repo.api.named(n)
                                    if (c != null) onPicked(c) else error = "Not found"
                                } catch (e: Exception) { error = e.message }
                                loading = false
                            }
                        }.padding(vertical = 10.dp))
                        HorizontalDivider()
                    }
                }
            }
        }
    )
}

/** Display grouping used by deck lists. */
fun deckCategory(section: String, typeLine: String): String {
    val t = typeLine.substringBefore(" // ")
    return when {
        section == "commander" -> "Commander"
        section == "side" -> "Sideboard"
        t.contains("Land") -> "Lands"
        t.contains("Creature") -> "Creatures"
        t.contains("Planeswalker") -> "Planeswalkers"
        t.contains("Instant") -> "Instants"
        t.contains("Sorcery") -> "Sorceries"
        t.contains("Artifact") -> "Artifacts"
        t.contains("Enchantment") -> "Enchantments"
        t.contains("Battle") -> "Battles"
        else -> "Other"
    }
}

val categoryOrder = listOf("Commander", "Creatures", "Planeswalkers", "Instants", "Sorceries", "Artifacts",
    "Enchantments", "Battles", "Other", "Lands", "Sideboard")

@Composable
fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
fun LabeledRow(content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}
