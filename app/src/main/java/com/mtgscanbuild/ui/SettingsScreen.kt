package com.mtgscanbuild.ui

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import com.mtgscanbuild.data.Accent
import com.mtgscanbuild.data.ScanSensitivity
import com.mtgscanbuild.data.ThemeMode
import com.mtgscanbuild.deck.Formats
import com.mtgscanbuild.scan.ScanSound
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoilApi::class)
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as Application
    val s = remember { app.settings }
    val sounds = remember { app.sounds }
    val repo = remember { app.repo }
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<String?>(null) }
    var picker by remember { mutableStateOf<String?>(null) }

    fun preview() {
        sounds.preview()
        note = if (sounds.mediaMuted) "Your media volume is at 0 – turn it up with the volume keys to hear scan sounds." else null
    }

    val soundFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { sounds.setCustom(uri); preview() }
    }
    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        Section("Scan sound")
        SwitchRow("Play a sound when a card is scanned", s.soundOn) { s.soundOn = it; if (it) preview() }
        ChoiceRow("Sound", soundLabel(s.sound, s.customSoundName), enabled = s.soundOn) { picker = "sound" }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Volume", Modifier.width(80.dp), style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = s.soundVolume, onValueChange = { s.soundVolume = it }, onValueChangeFinished = { preview() },
                enabled = s.soundOn, modifier = Modifier.weight(1f),
            )
            Text("${(s.soundVolume * 100).toInt()}%", Modifier.width(48.dp), style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { preview() }) { Text("Test sound") }
            OutlinedButton(onClick = { soundFile.launch(arrayOf("audio/*")) }) { Text("Use my own sound…") }
        }
        Text(
            "Sounds play at your phone's media volume, so they still work on vibrate.",
            Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        note?.let { Text(it, Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        Section("Scanning")
        SwitchRow("Auto-add scanned cards", s.autoAdd, "Add each recognized card to the collection without tapping Add") { s.autoAdd = it }
        SwitchRow("Vibrate when a card is added", s.vibrate) { s.vibrate = it }
        SwitchRow("Keep screen on while scanning", s.keepScreenOn) { s.keepScreenOn = it }
        SwitchRow("Turn on the light when scanning starts", s.startWithLight) { s.startWithLight = it }
        ChoiceRow("Recognition", s.sensitivity.label) { picker = "sensitivity" }

        Section("Appearance")
        ChoiceRow("Theme", s.themeMode.label) { picker = "theme" }
        ChoiceRow("Accent color", s.accent.label) { picker = "accent" }
        SwitchRow("Show card pictures in lists", s.showImages, "Turn off to save mobile data") { s.showImages = it }

        Section("Deck builder")
        ChoiceRow("Default format", Formats.byId(s.defaultFormat).name) { picker = "format" }
        SwitchRow("I have plenty of basic lands", s.assumeBasics, "Default for new decks: basics don't need to be in your collection") { s.assumeBasics = it }

        Section("Data")
        SwitchRow("Update TCGplayer prices daily", s.autoRefreshPrices,
            "Refreshes market prices for your collection once a day when you open it") { s.autoRefreshPrices = it }
        ChoiceRow("Update card name list", "Download the newest names (after a new set releases)") {
            note = "Downloading card names…"
            scope.launch { note = if (repo.names.refresh()) "Card name list updated (${repo.names.size} names)." else "Couldn't download – check your connection." }
        }
        ChoiceRow("Clear image cache", "Frees storage; pictures re-download when needed") {
            scope.launch {
                withContext(Dispatchers.IO) { ctx.imageLoader.diskCache?.clear() }
                ctx.imageLoader.memoryCache?.clear()
                note = "Image cache cleared."
            }
        }

        Section("About")
        Text("MTG Scan & Build $version", fontWeight = FontWeight.Bold)
        Text(
            "Card data and images from Scryfall. Magic: The Gathering is © Wizards of the Coast. " +
                "This app is unofficial and not affiliated with Wizards of the Coast.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(24.dp))
    }

    when (picker) {
        "sound" -> PickerDialog("Scan sound", ScanSound.entries, s.sound, { soundLabel(it, s.customSoundName) }, onDismiss = { picker = null }) {
            if (it == ScanSound.CUSTOM && s.customSoundUri == null) soundFile.launch(arrayOf("audio/*"))
            else { s.sound = it; preview() }
        }
        "sensitivity" -> PickerDialog("Recognition", ScanSensitivity.entries, s.sensitivity, { it.label }, onDismiss = { picker = null }) {
            s.sensitivity = it; picker = null
        }
        "theme" -> PickerDialog("Theme", ThemeMode.entries, s.themeMode, { it.label }, onDismiss = { picker = null }) {
            s.themeMode = it; picker = null
        }
        "accent" -> PickerDialog("Accent color", Accent.entries, s.accent, { it.label }, onDismiss = { picker = null }) {
            s.accent = it; picker = null
        }
        "format" -> PickerDialog("Default format", Formats.all, Formats.byId(s.defaultFormat), { it.name }, onDismiss = { picker = null }) {
            s.defaultFormat = it.id; picker = null
        }
    }
}

private fun soundLabel(s: ScanSound, customName: String?) =
    if (s == ScanSound.CUSTOM) "My own sound${customName?.let { " ($it)" } ?: ""}" else s.label

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 20.dp, bottom = 8.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun ChoiceRow(title: String, value: String, enabled: Boolean = true, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled, onClick = onClick).padding(vertical = 6.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        val alpha = if (enabled) 1f else 0.4f
        Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary.copy(alpha = alpha), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun <T> PickerDialog(
    title: String, options: List<T>, selected: T, label: (T) -> String,
    onDismiss: () -> Unit, onPick: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { o ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = o == selected, role = Role.RadioButton) { onPick(o) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = o == selected, onClick = null)
                        Text(label(o), Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
