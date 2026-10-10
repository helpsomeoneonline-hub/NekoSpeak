package com.nekospeak.tts.ui.screens

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.nekospeak.tts.utils.PreviewAudioCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.text.SimpleDateFormat
import java.util.Date
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.nekospeak.tts.data.PrefsManager
import com.nekospeak.tts.ui.components.VoiceCard
import com.nekospeak.tts.ui.viewmodel.VoicesViewModel
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicesScreen(
    navController: NavController,
    viewModel: VoicesViewModel = viewModel(),
    pendingVoiceCloneData: Triple<String, String, String>? = null, // (path, name, transcript)
    onVoiceCloneHandled: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { PrefsManager(context) }
    val snackbarHostState = remember { SnackbarHostState() }
    
    // A finished preview is stored once, then reused for saving and sharing.
    val audioCapture = remember(context) { PreviewAudioCapture(context) }
    var lastAudioFile by remember { mutableStateOf(audioCapture.lastCompleted()) }
    var activePreviewId by remember { mutableStateOf<String?>(null) }
    var pendingSaveFile by remember { mutableStateOf<File?>(null) }

    val saveWavLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/wav")
    ) { destination ->
        val source = pendingSaveFile
        pendingSaveFile = null
        if (destination != null && source?.isFile == true) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(destination, "w")?.use { output ->
                            source.inputStream().use { it.copyTo(output) }
                        } ?: error("Could not open the selected destination")
                    }
                }
                snackbarHostState.showSnackbar(
                    if (result.isSuccess) "WAV saved successfully" else
                        "Save failed: ${result.exceptionOrNull()?.message ?: "unknown error"}"
                )
            }
        }
    }

    val audioListener = remember(audioCapture, scope) {
        object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit

            override fun onBeginSynthesis(
                utteranceId: String, sampleRateInHz: Int,
                audioFormat: Int, channelCount: Int
            ) {
                audioCapture.begin(utteranceId, sampleRateInHz, audioFormat, channelCount)
            }

            override fun onAudioAvailable(utteranceId: String, audio: ByteArray) {
                audioCapture.addAudio(utteranceId, audio)
            }

            override fun onDone(utteranceId: String) {
                val file = audioCapture.finish(utteranceId)
                scope.launch {
                    if (activePreviewId == utteranceId) {
                        activePreviewId = null
                        if (file != null) {
                            lastAudioFile = file
                        } else {
                            snackbarHostState.showSnackbar("Audio played but could not be saved")
                        }
                    }
                }
            }

            @Deprecated("The Android framework still calls this for older engines")
            override fun onError(utteranceId: String) {
                failed(utteranceId)
            }

            override fun onError(utteranceId: String, errorCode: Int) {
                failed(utteranceId)
            }

            override fun onStop(utteranceId: String, interrupted: Boolean) {
                audioCapture.abort(utteranceId)
                scope.launch {
                    if (activePreviewId == utteranceId) activePreviewId = null
                }
            }

            private fun failed(utteranceId: String) {
                audioCapture.abort(utteranceId)
                scope.launch {
                    if (activePreviewId == utteranceId) {
                        activePreviewId = null
                        snackbarHostState.showSnackbar("Speech synthesis failed")
                    }
                }
            }
        }
    }

    // Test Speech State
    var testText by remember { mutableStateOf("Hello, I am NekoSpeak.") }
    var speechRate by remember { mutableFloatStateOf(1.0f) }
    var tts: TextToSpeech? by remember { mutableStateOf(null) }
    var showLanguageModal by remember { mutableStateOf(false) }
    var showRegionModal by remember { mutableStateOf(false) }
    var showGenderModal by remember { mutableStateOf(false) }
    var showQualityModal by remember { mutableStateOf(false) }
    
    // Voice cloning state
    var showCloneOptionsSheet by remember { mutableStateOf(false) }
    var showVoiceNameDialog by remember { mutableStateOf(false) }
    var voiceClonePath by remember { mutableStateOf<String?>(null) }
    var voiceCloneName by remember { mutableStateOf("") }
    var voiceCloneTranscript by remember { mutableStateOf("") } // Kept for API compat but not used
    var isCloning by remember { mutableStateOf(false) }
    
    DisposableEffect(Unit) {
        // Bind to this fork's own TTS service, not the user's default engine.
        tts = TextToSpeech(context, { _ -> }, context.packageName).also {
            it.setOnUtteranceProgressListener(audioListener)
        }
        onDispose {
            tts?.shutdown()
        }
    }
    
    // Sync ViewModel selection with Prefs
    LaunchedEffect(uiState.selectedVoiceId) {
        uiState.selectedVoiceId?.let { 
             prefs.currentVoice = it
             // Auto-update test text based on language
             testText = viewModel.getSampleTextForVoice(it)
        }
    }
    
    // Load initial selection and voice list from prefs
    LaunchedEffect(Unit) {
        viewModel.loadVoices() // Refresh list (in case model changed)
        viewModel.selectVoice(prefs.currentVoice)
    }
    
    // Handle pending voice clone from VoiceRecorderScreen
    LaunchedEffect(pendingVoiceCloneData) {
        pendingVoiceCloneData?.let { (path, name, transcript) ->
            if (name.isNotEmpty()) {
                // Clone directly with recorded audio and transcript
                viewModel.cloneVoice(path, name, transcript)
            } else {
                voiceClonePath = path
                voiceCloneTranscript = transcript
                showVoiceNameDialog = true
            }
            onVoiceCloneHandled()
        }
    }

    LaunchedEffect(uiState.cloneErrorMessage) {
        val message = uiState.cloneErrorMessage
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearCloneError()
        }
    }
    
    // File picker for audio upload
    val audioPickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            // Copy to cache and get path
            val inputStream = context.contentResolver.openInputStream(uri)
            val cacheFile = java.io.File(context.cacheDir, "voice_upload_${System.currentTimeMillis()}.wav")
            inputStream?.use { input ->
                cacheFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            voiceClonePath = cacheFile.absolutePath
            voiceCloneTranscript = "" // Transcript not used - cloning is audio-only
            showVoiceNameDialog = true // Go directly to name dialog (skip transcript)
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("Voices", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "${uiState.filteredVoices.size} voices available",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            )
        },

        floatingActionButton = {
            if (prefs.currentModel == "pocket_v1") {
                ExtendedFloatingActionButton(
                    onClick = { showCloneOptionsSheet = true },
                    icon = { Icon(Icons.Default.Add, "Clone Voice") },
                    text = { Text("Clone Voice") },
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            }
        },
        bottomBar = {
            Surface(tonalElevation = 8.dp, shadowElevation = 8.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = testText,
                            onValueChange = { testText = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Type your voiceover...") },
                            maxLines = 3,
                            shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        FloatingActionButton(
                            onClick = {
                                if (testText.isNotBlank()) {
                                    val voiceId = uiState.selectedVoiceId ?: prefs.currentVoice
                                    val params = android.os.Bundle().apply {
                                        putString("voiceName", voiceId)
                                    }
                                    // Stop previous playback, then capture this unique preview.
                                    val wasSpeaking = tts?.isSpeaking == true
                                    tts?.stop()
                                    if (wasSpeaking) {
                                        tts?.shutdown()
                                        tts = TextToSpeech(context, { _ -> }, context.packageName).also {
                                            it.setOnUtteranceProgressListener(audioListener)
                                        }
                                    }
                                    val id = "preview_${UUID.randomUUID()}"
                                    audioCapture.arm(id)
                                    activePreviewId = id
                                    tts?.setSpeechRate(prefs.speechSpeed)
                                    val queued = tts?.speak(
                                        testText, TextToSpeech.QUEUE_FLUSH, params, id
                                    )
                                    if (queued != TextToSpeech.SUCCESS) {
                                        audioCapture.abort(id)
                                        activePreviewId = null
                                        scope.launch {
                                            snackbarHostState.showSnackbar("Could not start speech. Try Play again.")
                                        }
                                    }
                                }
                            },
                            containerColor = MaterialTheme.colorScheme.primary
                        ) {
                            Icon(Icons.Default.PlayArrow, "Generate and play voiceover")
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            enabled = lastAudioFile?.isFile == true && activePreviewId == null,
                            onClick = {
                                pendingSaveFile = lastAudioFile
                                val name = SimpleDateFormat(
                                    "yyyyMMdd_HHmmss", Locale.US
                                ).format(Date())
                                saveWavLauncher.launch("NekoSpeak_voiceover_${name}.wav")
                            }
                        ) {
                            Text("Save WAV")
                        }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            enabled = lastAudioFile?.isFile == true && activePreviewId == null,
                            onClick = {
                                lastAudioFile?.let { file ->
                                    runCatching {
                                        val uri = FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            file
                                        )
                                        val share = Intent(Intent.ACTION_SEND).apply {
                                            type = "audio/wav"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(
                                            Intent.createChooser(share, "Share voiceover")
                                        )
                                    }.onFailure {
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                "Could not share: ${it.message ?: "unknown error"}"
                                            )
                                        }
                                    }
                                }
                            }
                        ) {
                            Text("Share")
                        }
                    }
                    if (activePreviewId != null) {
                        Text("Generating voiceover…", style = MaterialTheme.typography.labelSmall)
                    } else if (lastAudioFile?.isFile == true) {
                        Text(
                            "Last completed preview ready to save or share",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (uiState.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // Search Bar
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = viewModel::updateSearchQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search voices...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (uiState.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                            Icon(Icons.Default.Clear, null)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            
            // Filters Row
            Row(
                modifier = Modifier
                    .horizontalScroll(androidx.compose.foundation.rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Region Filter
                FilterChip(
                    selected = uiState.selectedRegion != null,
                    onClick = { showRegionModal = true },
                    label = { Text(uiState.selectedRegion ?: "Region") },
                    trailingIcon = { Icon(Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(16.dp)) }
                )

                // Language Filter
                FilterChip(
                    selected = uiState.selectedLanguage != null,
                    onClick = { showLanguageModal = true },
                    label = { Text(uiState.selectedLanguage ?: "Language") },
                    trailingIcon = { Icon(Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(16.dp)) }
                )
                
                // Gender Filter
                FilterChip(
                    selected = uiState.selectedGender != null,
                    onClick = { showGenderModal = true },
                    label = { Text(uiState.selectedGender ?: "Gender") },
                    trailingIcon = { Icon(Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(16.dp)) }
                )
                
                // Quality Filter (Piper only)
                if (uiState.availableQualities.isNotEmpty()) {
                    FilterChip(
                        selected = uiState.selectedQuality != null,
                        onClick = { showQualityModal = true },
                        label = { Text(uiState.selectedQuality ?: "Quality") },
                        trailingIcon = { Icon(Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(16.dp)) }
                    )
                }
                
                // Clear
                if (uiState.selectedRegion != null || uiState.selectedLanguage != null || uiState.selectedGender != null || uiState.selectedQuality != null) {
                    IconButton(onClick = { viewModel.clearFilters() }) {
                        Icon(Icons.Default.Clear, "Clear filters")
                    }
                }
            }
            
            // Processing Status Banner - shows when encoding voices
            uiState.processingStatus?.let { status ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
            
            if (uiState.filteredVoices.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No voices found", style = MaterialTheme.typography.titleMedium)
                        Text("Try adjusting your filters", style = MaterialTheme.typography.bodyMedium)
                        Button(
                            onClick = { viewModel.clearFilters() },
                            modifier = Modifier.padding(top = 16.dp)
                        ) {
                            Text("Clear Filters")
                        }
                    }
                }
            } else {
                // Voice List
                LazyColumn(
                    contentPadding = PaddingValues(start=16.dp, end=16.dp, bottom=100.dp), // Extra bottom padding for TestBar
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(uiState.filteredVoices) { voice ->
                        com.nekospeak.tts.ui.components.VoiceCard(
                            voice = voice,
                            isSelected = voice.id == uiState.selectedVoiceId,
                            onVoiceSelected = { viewModel.selectVoice(voice.id) },
                            onDownload = { viewModel.downloadVoice(voice) },
                            onDelete = if (voice.isCloned) {{ viewModel.deleteClonedVoice(voice.id) }} else null
                        )
                    }
                }
            }
        }
        
        if (showLanguageModal) {
            ModalBottomSheet(
                onDismissRequest = { showLanguageModal = false }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 32.dp)
                ) {
                    Text(
                        "Select Language",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(16.dp)
                    )
                    HorizontalDivider()
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { 
                                        viewModel.selectLanguage(null)
                                        showLanguageModal = false
                                    }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = uiState.selectedLanguage == null,
                                    onClick = { 
                                        viewModel.selectLanguage(null)
                                        showLanguageModal = false
                                    }
                                )
                                Text("All Languages", style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        
                        items(uiState.availableLanguages) { lang ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { 
                                        viewModel.selectLanguage(lang)
                                        showLanguageModal = false
                                    }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = uiState.selectedLanguage == lang,
                                    onClick = { 
                                        viewModel.selectLanguage(lang)
                                        showLanguageModal = false
                                    }
                                )
                                Text(lang, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
        }
        
        if (showRegionModal) {
            ModalBottomSheet(onDismissRequest = { showRegionModal = false }) {
                Column(modifier = Modifier.padding(bottom=32.dp)) {
                    Text("Select Region", style=MaterialTheme.typography.titleLarge, modifier=Modifier.padding(16.dp))
                    HorizontalDivider()
                    uiState.availableRegions.forEach { region ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { viewModel.selectRegion(region); showRegionModal = false }.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(region)
                        }
                    }
                    Button(onClick={viewModel.selectRegion(null); showRegionModal=false}, modifier=Modifier.padding(16.dp).fillMaxWidth()) { Text("Clear Region") }
                }
            }
        }
        
        if (showGenderModal) {
             ModalBottomSheet(onDismissRequest = { showGenderModal = false }) {
                Column(modifier = Modifier.padding(bottom=32.dp)) {
                    Text("Select Gender", style=MaterialTheme.typography.titleLarge, modifier=Modifier.padding(16.dp))
                    HorizontalDivider()
                    listOf("Male", "Female").forEach { gender ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { viewModel.selectGender(gender); showGenderModal = false }.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(gender)
                        }
                    }
                    Button(onClick={viewModel.selectGender(null); showGenderModal=false}, modifier=Modifier.padding(16.dp).fillMaxWidth()) { Text("Clear Gender") }
                }
            }
        }
        
        if (showQualityModal) {
             ModalBottomSheet(onDismissRequest = { showQualityModal = false }) {
                Column(modifier = Modifier.padding(bottom=32.dp)) {
                    Text("Select Quality", style=MaterialTheme.typography.titleLarge, modifier=Modifier.padding(16.dp))
                    HorizontalDivider()
                    uiState.availableQualities.forEach { quality ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { viewModel.selectQuality(quality); showQualityModal = false }.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(quality.replaceFirstChar { it.uppercaseChar() })
                        }
                    }
                    Button(onClick={viewModel.selectQuality(null); showQualityModal=false}, modifier=Modifier.padding(16.dp).fillMaxWidth()) { Text("Clear Quality") }
                }
            }
        }
        
        // Voice Cloning Options Sheet
        if (showCloneOptionsSheet) {
            ModalBottomSheet(onDismissRequest = { showCloneOptionsSheet = false }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 32.dp)
                ) {
                    Text(
                        "Clone a Voice",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(16.dp)
                    )
                    HorizontalDivider()
                    
                    // Record option
                    ListItem(
                        headlineContent = { Text("Record Voice") },
                        supportingContent = { Text("Record 5-10 seconds of speech") },
                        leadingContent = { Icon(Icons.Default.PlayArrow, null) },
                        modifier = Modifier.clickable {
                            showCloneOptionsSheet = false
                            navController.navigate(com.nekospeak.tts.ui.navigation.Screen.VoiceRecorder.route)
                        }
                    )
                    
                    // Upload option
                    ListItem(
                        headlineContent = { Text("Upload Audio File") },
                        supportingContent = { Text("Select a WAV or MP3 file") },
                        leadingContent = { Icon(Icons.Default.Create, null) },
                        modifier = Modifier.clickable {
                            showCloneOptionsSheet = false
                            audioPickerLauncher.launch("audio/*")
                        }
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
        
        // NOTE: Transcript dialog removed - transcripts don't improve voice cloning
        // Voice embeddings come purely from mimi_encoder(audio), no text involved
        
        // Voice Name Dialog (step 2 for file upload, or direct from recording)
        if (showVoiceNameDialog && voiceClonePath != null) {
            AlertDialog(
                onDismissRequest = { 
                    showVoiceNameDialog = false
                    voiceClonePath = null
                    voiceCloneName = ""
                    voiceCloneTranscript = ""
                },
                title = { Text("Name Your Voice") },
                text = {
                    Column {
                        Text(
                            "Give your cloned voice a name so you can find it later.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedTextField(
                            value = voiceCloneName,
                            onValueChange = { voiceCloneName = it },
                            label = { Text("Voice Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.cloneVoice(voiceClonePath!!, voiceCloneName, voiceCloneTranscript)
                            showVoiceNameDialog = false
                            voiceClonePath = null
                            voiceCloneName = ""
                            voiceCloneTranscript = ""
                        },
                        enabled = voiceCloneName.isNotBlank()
                    ) {
                        Text("Clone Voice")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { 
                            showVoiceNameDialog = false 
                            voiceClonePath = null
                            voiceCloneName = ""
                            voiceCloneTranscript = ""
                        }
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}
