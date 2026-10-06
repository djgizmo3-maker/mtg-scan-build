package com.mtgscanbuild.ui

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.RectF
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mtgscanbuild.data.CardData
import com.mtgscanbuild.data.CardNameIndex
import com.mtgscanbuild.data.frontName
import com.mtgscanbuild.scan.CardTextAnalyzer
import com.mtgscanbuild.scan.GuideRegion
import com.mtgscanbuild.scan.ScanMatch
import com.mtgscanbuild.scan.ScanParser
import com.mtgscanbuild.scan.ScanReading
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

class ScanViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = app.repo
    val settings = app.settings
    private val sounds = app.sounds

    var indexProblem by mutableStateOf<String?>("Loading card name database…")
    var current by mutableStateOf<CardData?>(null)
    var quantity by mutableIntStateOf(1)
    var foil by mutableStateOf(false)
    var autoAdd: Boolean
        get() = settings.autoAdd
        set(v) { settings.autoAdd = v }
    var paused by mutableStateOf(false)
    var torch by mutableStateOf(settings.startWithLight)
    var status by mutableStateOf("Hold a card inside the frame")
    val recent = mutableStateListOf<String>()
    var sessionCount by mutableIntStateOf(0)
    var canUndo by mutableStateOf(false)

    fun toggleSound() {
        settings.soundOn = !settings.soundOn
        if (settings.soundOn) {
            sounds.preview()
            if (sounds.mediaMuted) status = "Media volume is muted – turn it up to hear scan sounds"
        }
    }

    private data class Added(val card: CardData, val qty: Int, val foil: Boolean)
    private val history = ArrayDeque<Added>()
    /** Set by the screen to give haptic feedback when a card is added. */
    var onAddedFeedback: (() -> Unit)? = null

    private var pendingName: String? = null
    private var pendingCount = 0
    private var processing = false
    private var lastAddedName: String? = null
    private var lastSeen = 0L
    private val cache = HashMap<String, CardData>()

    init { loadIndex() }

    fun loadIndex() {
        viewModelScope.launch {
            indexProblem = if (repo.names.hasCache) "Loading card name list…" else "Downloading card name list (first run only)…"
            val ok = repo.names.ensureLoaded()
            indexProblem = if (ok) null else "Couldn't download the card name list. Check your internet connection and tap Retry." +
                (repo.names.lastError?.let { "\n($it)" } ?: "")
        }
    }

    fun onReading(r: ScanReading) {
        if (paused || processing || indexProblem != null) return
        processing = true
        viewModelScope.launch {
            try {
                val m = withContext(Dispatchers.Default) { ScanParser.parse(r, repo.names) }
                if (r.lines.isNotEmpty()) Log.d("MtgScan", "OCR ${r.lines.map { it.text }} -> $m")
                handle(m)
            } finally {
                processing = false
            }
        }
    }

    private fun same(a: String?, b: String?) =
        a != null && b != null && CardNameIndex.normalize(a) == CardNameIndex.normalize(b)

    private suspend fun handle(m: ScanMatch?) {
        val now = System.currentTimeMillis()
        if (m == null) {
            // Card removed from view: allow re-adding the same card (another copy).
            if (now - lastSeen > 1500) { lastAddedName = null; pendingName = null }
            return
        }
        lastSeen = now
        if (m.name == pendingName) pendingCount++ else { pendingName = m.name; pendingCount = 1 }
        // Confident reads need fewer agreeing frames than fuzzy ones; the counts come from Settings.
        val s = settings.sensitivity
        if (pendingCount < (if (m.score >= 0.9) s.confidentFrames else s.fuzzyFrames)) return

        var announced = false
        if (!same(current?.frontName, m.name)) {
            val card = lookup(m) ?: return
            current = card
            quantity = 1
            status = "Found: ${card.name}"
            sounds.play()
            announced = true
        }
        if (autoAdd && !same(lastAddedName, m.name)) add(playSound = !announced)
    }

    private suspend fun lookup(m: ScanMatch): CardData? {
        val key = "${m.name}|${m.setCode}|${m.collectorNumber}"
        cache[key]?.let { return it }
        status = "Looking up ${m.name}…"
        return try {
            var c: CardData? = null
            if (m.setCode != null && m.collectorNumber != null) {
                c = runCatching { repo.api.bySetNumber(m.setCode, m.collectorNumber) }.getOrNull()
                    ?.takeIf { same(it.frontName, m.name) }
            }
            c = c ?: repo.api.named(m.name)
            if (c == null) status = "Couldn't find ${m.name}"
            else cache[key] = c
            c
        } catch (e: Exception) {
            status = "Network error – ${e.message}"
            null
        }
    }

    fun setManual(card: CardData) {
        current = card
        quantity = 1
        status = "Selected: ${card.name}"
    }

    private val photoRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Recognizes a card from a saved photo (e.g. picked from the gallery). */
    fun scanPhoto(uri: Uri) {
        if (indexProblem != null) { status = "Card name list isn't ready yet"; return }
        viewModelScope.launch {
            status = "Reading photo…"
            try {
                val image = withContext(Dispatchers.IO) { InputImage.fromFilePath(getApplication(), uri) }
                val text = suspendCancellableCoroutine { cont ->
                    photoRecognizer.process(image)
                        .addOnSuccessListener { cont.resume(it) }
                        .addOnFailureListener { cont.resumeWithException(it) }
                }
                val reading = ScanReading.from(text, RectF(0f, 0f, image.width.toFloat(), image.height.toFloat()))
                val m = withContext(Dispatchers.Default) { ScanParser.parse(reading, repo.names) }
                Log.d("MtgScan", "Photo OCR ${reading.lines.map { it.text }} -> $m")
                if (m == null) { status = "No card name recognized in that photo"; return@launch }
                val card = lookup(m) ?: return@launch
                current = card
                quantity = 1
                status = "Found in photo: ${card.name}"
                sounds.play()
            } catch (e: Exception) {
                status = "Couldn't read photo – ${e.message}"
            }
        }
    }

    fun add(playSound: Boolean = true) {
        val c = current ?: return
        val q = quantity
        val f = foil
        lastAddedName = c.frontName
        viewModelScope.launch {
            repo.addCard(c, q, f)
            history.addFirst(Added(c, q, f))
            if (history.size > 50) history.removeLast()
            canUndo = true
            sessionCount += q
            recent.add(0, "$q× ${c.name} · ${c.setCode.uppercase()} #${c.collectorNumber}${if (f) " · foil" else ""}")
            if (recent.size > 50) recent.removeAt(recent.lastIndex)
            status = "Added $q× ${c.name}"
            quantity = 1
            if (playSound) sounds.play()
            if (settings.vibrate) onAddedFeedback?.invoke()
        }
    }

    fun undo() {
        val last = history.removeFirstOrNull() ?: return
        canUndo = history.isNotEmpty()
        viewModelScope.launch {
            repo.removeCard(last.card.scryfallId, last.qty, last.foil)
            sessionCount -= last.qty
            if (recent.isNotEmpty()) recent.removeAt(0)
            // Let the same card be recognized and added again if it is still in view.
            if (same(lastAddedName, last.card.frontName)) lastAddedName = null
            status = "Removed ${last.qty}× ${last.card.name}"
        }
    }

    override fun onCleared() {
        runCatching { photoRecognizer.close() }
    }
}

@Composable
fun ScanScreen(vm: ScanViewModel = viewModel()) {
    val ctx = LocalContext.current
    fun granted() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var hasPermission by remember { mutableStateOf(granted()) }
    var askedOnce by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
        askedOnce = true
    }
    LaunchedEffect(Unit) { if (!hasPermission) launcher.launch(Manifest.permission.CAMERA) }
    // Re-check when returning from system settings.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { hasPermission = granted() }
    val haptics = LocalHapticFeedback.current
    DisposableEffect(vm) {
        vm.onAddedFeedback = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
        onDispose { vm.onAddedFeedback = null }
    }
    var showSearch by remember { mutableStateOf(false) }
    var showPrintings by remember { mutableStateOf(false) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.scanPhoto(uri)
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {
            if (hasPermission) {
                CameraPreview(onReading = vm::onReading, torch = vm.torch, paused = vm.paused, keepScreenOn = vm.settings.keepScreenOn, modifier = Modifier.fillMaxSize())
            } else {
                Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Camera permission is needed to scan cards.", color = Color.White)
                    Spacer(Modifier.size(12.dp))
                    Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Grant permission") }
                    if (askedOnce) {
                        Text(
                            "If no prompt appears, enable Camera in the app's settings.",
                            Modifier.padding(top = 12.dp), color = Color.White, style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = {
                            ctx.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }) { Text("Open app settings") }
                    }
                }
            }
            Row(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledTonalButton(onClick = { vm.torch = !vm.torch }) { Text(if (vm.torch) "Light off" else "Light") }
                FilledTonalButton(onClick = { vm.paused = !vm.paused }) { Text(if (vm.paused) "Resume" else "Pause") }
                FilledTonalButton(onClick = { showSearch = true }) { Text("Search") }
                FilledTonalButton(onClick = {
                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text("Photo") }
            }
            vm.indexProblem?.let { msg ->
                Surface(Modifier.align(Alignment.BottomCenter).padding(8.dp), color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(msg, Modifier.weight(1f))
                        if (msg.startsWith("Couldn't")) TextButton(onClick = vm::loadIndex) { Text("Retry") }
                    }
                }
            }
        }
        Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(vm.status, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val c = vm.current
                if (c != null) {
                    Row(Modifier.padding(top = 8.dp)) {
                        CardThumb(c.imageUrlLarge ?: c.imageUrl, Modifier.width(78.dp).aspectRatio(63f / 88f))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${c.setName} (${c.setCode.uppercase()}) #${c.collectorNumber}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(c.typeLine, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(onClick = { if (vm.quantity > 1) vm.quantity-- }, Modifier.size(40.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("−") }
                                Text("${vm.quantity}", Modifier.padding(horizontal = 10.dp), fontWeight = FontWeight.Bold)
                                OutlinedButton(onClick = { vm.quantity++ }, Modifier.size(40.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("+") }
                                Spacer(Modifier.width(10.dp))
                                Switch(vm.foil, { vm.foil = it })
                                Text(" Foil", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.add() }, Modifier.weight(1f)) { Text("Add to collection") }
                        OutlinedButton(onClick = { showPrintings = true }) { Text("Set / printing") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(vm.autoAdd, { vm.autoAdd = it })
                    Text("  Auto-add", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = vm::toggleSound) {
                        Text(if (vm.settings.soundOn) "Sound on" else "Sound off")
                    }
                    TextButton(onClick = vm::undo, enabled = vm.canUndo) { Text("Undo") }
                }
                Text("${vm.sessionCount} card${if (vm.sessionCount == 1) "" else "s"} added this session", style = MaterialTheme.typography.bodySmall)
                vm.recent.take(3).forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    if (showSearch) SearchCardDialog(onPicked = { vm.setManual(it); showSearch = false }, onDismiss = { showSearch = false })
    val cur = vm.current
    if (showPrintings && cur != null) PrintingDialog(cur.name, onPick = { vm.setManual(it); showPrintings = false }, onDismiss = { showPrintings = false })
}

@Composable
fun CameraPreview(onReading: (ScanReading) -> Unit, torch: Boolean, paused: Boolean, keepScreenOn: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(onReading)
    val analyzer = remember { CardTextAnalyzer { latest(it) } }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val controller = remember { LifecycleCameraController(ctx) }
    val previewView = remember {
        PreviewView(ctx).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            this.controller = controller
        }
    }
    SideEffect { previewView.keepScreenOn = keepScreenOn }
    var error by remember { mutableStateOf<String?>(null) }
    var ready by remember { mutableStateOf(false) }

    DisposableEffect(owner) {
        controller.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        controller.imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
        controller.imageAnalysisResolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
            .build()
        controller.setImageAnalysisAnalyzer(executor, analyzer)
        controller.isTapToFocusEnabled = true
        controller.isPinchToZoomEnabled = true
        try {
            controller.bindToLifecycle(owner)
        } catch (e: Exception) {
            error = "Couldn't start the camera: ${e.message}"
        }
        val init = controller.initializationFuture
        init.addListener({
            try {
                init.get()
                if (!controller.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    if (controller.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) controller.cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
                    else error = "No camera was found on this device."
                }
                controller.cameraInfo?.cameraState?.observe(owner) { s ->
                    error = when (s.error?.code) {
                        null -> null
                        CameraState.ERROR_CAMERA_IN_USE, CameraState.ERROR_MAX_CAMERAS_IN_USE ->
                            "The camera is being used by another app. Close it and come back."
                        CameraState.ERROR_CAMERA_DISABLED -> "The camera is disabled on this device."
                        CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> "Turn off Do Not Disturb to use the camera."
                        else -> "Camera problem (code ${s.error?.code}). Trying to recover…"
                    }
                }
                ready = true
            } catch (e: Exception) {
                error = "Couldn't start the camera: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            controller.unbind()
            ready = false
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            analyzer.close()
            executor.shutdown()
        }
    }
    LaunchedEffect(paused) { analyzer.enabled = !paused }
    LaunchedEffect(torch, ready) { if (ready) controller.enableTorch(torch) }

    BoxWithConstraints(modifier) {
        val w = maxWidth
        val h = maxHeight
        // Card-shaped guide (63 × 88 mm) that always fits the preview area.
        val gw = minOf(w * 0.72f, h * 0.88f * (63f / 88f))
        val gh = gw * (88f / 63f)
        SideEffect {
            val fx = (gw / w) / 2f
            val fy = (gh / h) / 2f
            analyzer.guide = GuideRegion(0.5f - fx, 0.5f - fy, 0.5f + fx, 0.5f + fy, w / h)
        }
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        Box(
            Modifier.align(Alignment.Center).size(gw, gh)
                .border(2.dp, if (paused) Color.Gray else Color.White.copy(alpha = 0.85f), RoundedCornerShape(14.dp))
        )
        Text(
            if (paused) "Paused" else "Fit the card in the frame · tap to focus · pinch to zoom",
            Modifier.align(Alignment.TopCenter).padding(top = 64.dp)
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
            color = Color.White, style = MaterialTheme.typography.labelSmall,
        )
        error?.let {
            Surface(Modifier.align(Alignment.Center).padding(24.dp), color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(8.dp)) {
                Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}
