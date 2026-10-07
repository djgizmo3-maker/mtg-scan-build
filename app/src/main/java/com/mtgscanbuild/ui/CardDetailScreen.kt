package com.mtgscanbuild.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mtgscanbuild.data.formatUsd
import com.mtgscanbuild.data.legalityMap
import com.mtgscanbuild.data.totalPrice
import com.mtgscanbuild.data.unitPrice
import com.mtgscanbuild.deck.Formats
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("DEPRECATION")
fun CardDetailScreen(id: Long, onBack: () -> Unit) {
    val repo = rememberRepo()
    val scope = rememberCoroutineScope()
    val item by remember(id) { repo.observeCard(id) }.collectAsState(initial = null)
    val uriHandler = LocalUriHandler.current
    var loaded by remember { mutableStateOf(false) }
    var showPrintings by remember { mutableStateOf(false) }
    LaunchedEffect(item) { if (item != null) loaded = true else if (loaded) onBack() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(item?.card?.name ?: "") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = { item?.let { scope.launch { repo.setQuantity(it, 0) } } }) {
                    Icon(Icons.Filled.Delete, "Remove")
                }
            }
        )
        val c = item ?: return@Column
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            AsyncImage(
                model = c.card.imageUrlLarge ?: c.card.imageUrl, contentDescription = c.card.name,
                modifier = Modifier.fillMaxWidth(0.8f).aspectRatio(63f / 88f).align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Quantity", Modifier.weight(1f))
                QtyControl(c.quantity) { q -> scope.launch { repo.setQuantity(c, q) } }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Foil", Modifier.weight(1f))
                Switch(c.foil, { f -> scope.launch { repo.setFoil(c, f) } })
            }
            OutlinedButton(onClick = { showPrintings = true }, Modifier.fillMaxWidth()) {
                Text("${c.card.setName} (${c.card.setCode.uppercase()}) #${c.card.collectorNumber} — change")
            }
            Spacer(Modifier.height(8.dp))
            SectionHeader("TCGplayer market price")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${formatUsd(c.unitPrice)} each${if (c.foil) " (foil)" else ""}" +
                            if (c.quantity > 1) " · ${formatUsd(c.totalPrice)} for ${c.quantity}" else "",
                        fontWeight = FontWeight.Bold
                    )
                    Text("Normal ${formatUsd(c.card.priceUsd)} · Foil ${formatUsd(c.card.priceUsdFoil)}",
                        style = MaterialTheme.typography.bodySmall)
                }
                val url = c.card.tcgplayerUrl
                    ?: "https://www.tcgplayer.com/search/magic/product?productLineName=magic&q=${Uri.encode(c.card.name)}"
                OutlinedButton(onClick = { uriHandler.openUri(url) }) { Text("TCGplayer") }
            }
            if (c.unitPrice == null) Text("No price yet — use Refresh TCGplayer prices on the Collection tab.",
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Text("${c.card.name}  ${compactCost(c.card.manaCost)}", fontWeight = FontWeight.Bold)
            Text(c.card.typeLine, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            Text(c.card.oracleText, style = MaterialTheme.typography.bodyMedium)
            if (c.card.power != null) Text("${c.card.power}/${c.card.toughness}", fontWeight = FontWeight.Bold)
            if (c.card.loyalty != null) Text("Loyalty ${c.card.loyalty}", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            SectionHeader("Format legality")
            val legal = c.card.legalityMap()
            Formats.all.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { f ->
                        val l = legal[f.id] ?: "not_legal"
                        val color = when (l) {
                            "legal" -> Color(0xFF66BB6A); "restricted" -> Color(0xFFFFB74D)
                            "banned" -> Color(0xFFE57373); else -> Color(0xFF888888)
                        }
                        Text("${f.name}: ${l.replace('_', ' ')}", Modifier.weight(1f), color = color,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        if (showPrintings) PrintingDialog(c.card.name, onPick = { p ->
            showPrintings = false
            scope.launch { repo.changePrinting(c, p) }
        }, onDismiss = { showPrintings = false })
    }
}
