package com.mtgscanbuild.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mtgscanbuild.data.CollectionCard
import com.mtgscanbuild.data.CollectionFolder
import com.mtgscanbuild.data.folderSummary
import com.mtgscanbuild.data.formatUsd
import com.mtgscanbuild.data.totalPrice
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private fun wheelSelection(point: Offset, width: Int, height: Int): Pair<Float, Float> {
    val x = point.x - width / 2f
    val y = point.y - height / 2f
    return ((Math.toDegrees(atan2(y, x).toDouble()) + 360) % 360).toFloat() to
        (hypot(x, y) / (minOf(width, height) / 2f)).coerceIn(0f, 1f)
}

@Composable
fun FolderBrowser(
    cards: List<CollectionCard>,
    folders: List<CollectionFolder>,
    onOpen: (Long) -> Unit,
    onSave: (CollectionFolder) -> Unit,
    onDelete: (CollectionFolder) -> Unit,
    onAll: () -> Unit,
    canCreate: Boolean,
    onPro: () -> Unit,
) {
    var editor by remember { mutableStateOf<CollectionFolder?>(null) }
    var deleting by remember { mutableStateOf<CollectionFolder?>(null) }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "overview") { FolderOverview(cards, folders) }
            item(key = "all") {
                FilledTonalButton(onClick = onAll, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(18.dp)) {
                    Text("All collection", style = MaterialTheme.typography.titleMedium)
                }
            }
            item(key = "create") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Your folders", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = {
                        if (canCreate) editor = CollectionFolder(name = "", color = 0xFF6750A4.toInt()) else onPro()
                    }) {
                        Text(if (canCreate) "+ Create folder" else "More folders (Pro)")
                    }
                }
                if (folders.isEmpty()) Text("Create your own sets to organize your collection.",
                    style = MaterialTheme.typography.bodySmall)
            }
            item(key = "unfiled") {
                FolderTile("Unfiled", MaterialTheme.colorScheme.outline, cards, null, { onOpen(0L) })
            }
            items(folders, key = { it.id }) { folder ->
                FolderTile(folder.name, Color(folder.color), cards, folder.id, { onOpen(folder.id) }) {
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Options for ${folder.name}") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Rename / change color") }, { menu = false; editor = folder })
                            DropdownMenuItem({ Text("Delete folder") }, { menu = false; deleting = folder })
                        }
                    }
                }
            }
        }
    }
    editor?.let { folder ->
        FolderEditor(folder, onSave = { onSave(it); editor = null }, onDismiss = { editor = null })
    }
    deleting?.let { folder ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${folder.name}?") },
            text = { Text("Only the folder is deleted. Its cards stay in your collection and become unfiled.") },
            confirmButton = { TextButton(onClick = { onDelete(folder); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun FolderOverview(cards: List<CollectionCard>, folders: List<CollectionFolder>) {
    val unfiledColor = MaterialTheme.colorScheme.outline
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val total = remember(cards) { cards.sumOf { it.totalPrice } }
    val values = remember(cards, folders, unfiledColor) {
        val grouped = cards.groupBy { it.folderId }.mapValues { (_, contents) -> contents.sumOf { it.totalPrice } }
        folders.map { Color(it.color) to (grouped[it.id] ?: 0.0) } +
            (unfiledColor to (grouped[null] ?: 0.0))
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().padding(8.dp)) {
                val stroke = Stroke(12.dp.toPx())
                drawCircle(trackColor, style = stroke)
                var start = -90f
                if (total > 0) values.forEach { (color, value) ->
                    val sweep = (value / total * 360).toFloat()
                    if (sweep > 0) drawArc(color, start, sweep, useCenter = false, style = stroke)
                    start += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Estimated value", style = MaterialTheme.typography.labelMedium)
                Text(formatUsd(total), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("${cards.sumOf { it.quantity }} cards", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text("Value by folder", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun FolderTile(
    name: String, color: Color, cards: List<CollectionCard>, folderId: Long?, onOpen: () -> Unit,
    actions: @Composable () -> Unit = {},
) {
    val summary = remember(cards, folderId) { folderSummary(cards, folderId) }
    Surface(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onOpen),
        color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(8.dp).fillMaxHeight().background(color))
            Row(Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("${summary.quantity} cards", style = MaterialTheme.typography.bodyMedium)
                    if (summary.unpricedQuantity > 0) Text(
                        "${summary.unpricedQuantity} unpriced (excluded)",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(formatUsd(summary.estimatedValue), style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold)
                    Text("Estimated", style = MaterialTheme.typography.labelSmall)
                }
            }
            actions()
        }
    }
}

@Composable
private fun FolderEditor(folder: CollectionFolder, onSave: (CollectionFolder) -> Unit, onDismiss: () -> Unit) {
    var name by remember(folder) { mutableStateOf(folder.name) }
    val initial = remember(folder) { FloatArray(3).also { android.graphics.Color.colorToHSV(folder.color, it) } }
    var hue by remember(folder) { mutableFloatStateOf(initial[0]) }
    var saturation by remember(folder) { mutableFloatStateOf(initial[1]) }
    var brightness by remember(folder) { mutableFloatStateOf(initial[2]) }
    val color = Color.hsv(hue, saturation, brightness)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (folder.id == 0L) "Create folder" else "Edit folder") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                OutlinedTextField(name, { name = it }, label = { Text("Folder name") }, singleLine = true,
                    isError = name.isBlank(), supportingText = { if (name.isBlank()) Text("Enter a folder name") })
                Spacer(Modifier.height(12.dp))
                ColorWheel(hue, saturation, brightness) { h, s -> hue = h; saturation = s }
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(32.dp).background(color, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(String.format("#%06X", color.toArgb() and 0xFFFFFF))
                }
                Text("Hue")
                Slider(hue, { hue = it }, valueRange = 0f..359.99f,
                    modifier = Modifier.semantics { contentDescription = "Folder color hue" })
                Text("Saturation")
                Slider(saturation, { saturation = it },
                    modifier = Modifier.semantics { contentDescription = "Folder color saturation" })
                Text("Brightness")
                Slider(brightness, { brightness = it },
                    modifier = Modifier.semantics { contentDescription = "Folder color brightness" })
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(folder.copy(name = name.trim(), color = color.toArgb())) },
                enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ColorWheel(hue: Float, saturation: Float, brightness: Float, onPick: (Float, Float) -> Unit) {
    val pick by rememberUpdatedState(onPick)
    Canvas(
        Modifier.size(200.dp)
            .semantics { contentDescription = "Folder color wheel; drag to choose hue and saturation, or use the sliders" }
            .pointerInput(Unit) {
                detectTapGestures {
                    val (h, s) = wheelSelection(it, size.width, size.height)
                    pick(h, s)
                }
            }
            .pointerInput(Unit) {
                fun select(point: Offset) {
                    val (h, s) = wheelSelection(point, size.width, size.height)
                    pick(h, s)
                }
                detectDragGestures(onDragStart = { select(it) }) { change, _ ->
                    change.consume()
                    select(change.position)
                }
            }
    ) {
        val radius = size.minDimension / 2f
        val hues = (0..6).map { Color.hsv((it * 60f) % 360f, 1f, brightness) }
        drawCircle(Brush.sweepGradient(hues, center))
        drawCircle(Brush.radialGradient(listOf(Color.hsv(0f, 0f, brightness), Color.Transparent), center, radius))
        val angle = Math.toRadians(hue.toDouble())
        val marker = center + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * (radius * saturation)
        drawCircle(Color.Black, 7.dp.toPx(), marker, style = Stroke(3.dp.toPx()))
        drawCircle(Color.White, 7.dp.toPx(), marker, style = Stroke(1.5.dp.toPx()))
    }
}

@Composable
fun FolderCardsDialog(
    cards: List<CollectionCard>, folders: List<CollectionFolder>, target: CollectionFolder,
    onMove: (List<Long>) -> Unit, onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    val available = cards.filter { it.folderId != target.id }
    val visible = available.filter { it.card.name.contains(query.trim(), ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add cards to ${target.name}") },
        text = {
            Column {
                Text("Moves entire card entries, including all copies, from their current folder.")
                OutlinedTextField(query, { query = it }, label = { Text("Search cards") }, singleLine = true)
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    if (visible.isEmpty()) item { Text("No cards available.", Modifier.padding(12.dp)) }
                    items(visible, key = { it.id }) { card ->
                        val toggle = { selected = if (card.id in selected) selected - card.id else selected + card.id }
                        Row(Modifier.fillMaxWidth().clickable(onClick = toggle).padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(card.id in selected, { toggle() })
                            Column {
                                Text("${card.quantity}x ${card.card.name}${if (card.foil) " (foil)" else ""}")
                                Text("${card.card.setCode.uppercase()} #${card.card.collectorNumber} - " +
                                    (folders.find { it.id == card.folderId }?.name ?: "Unfiled"),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onMove(available.filter { it.id in selected }.map { it.id }) },
                enabled = available.any { it.id in selected }) { Text("Move (${available.count { it.id in selected }})") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun FolderPickerDialog(
    folders: List<CollectionFolder>, currentId: Long?, onPick: (Long?) -> Unit, onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to folder") },
        text = {
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                item {
                    TextButton(onClick = { onPick(null) }, enabled = currentId != null) { Text("Unfiled") }
                }
                items(folders, key = { it.id }) { folder ->
                    TextButton(onClick = { onPick(folder.id) }, enabled = currentId != folder.id) {
                        Box(Modifier.size(16.dp).background(Color(folder.color), CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(folder.name)
                    }
                }
                if (folders.isEmpty()) item { Text("Create folders in Collection > User Created Sets.") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
