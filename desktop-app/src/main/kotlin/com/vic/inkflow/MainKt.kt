package com.vic.inkflow

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncDisabled
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.vic.inkflow.data.DatabaseManager
import com.vic.inkflow.sync.LocalSyncManager
import com.vic.inkflow.ui.DocumentLibraryView
import com.vic.inkflow.ui.PdfViewerWithPdfBox
import com.vic.inkflow.data.StrokeWithPoints
import mu.KotlinLogging
import java.awt.Dimension
import java.io.File

private val logger = KotlinLogging.logger {}

@Composable
@Preview
fun App() {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF6750A4),
            secondary = Color(0xFF625B71),
            tertiary = Color(0xFF7D5260)
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            InkFlowApp()
        }
    }
}

@Composable
fun InkFlowApp() {
    // Initialize database manager
    val appDataDir = System.getProperty("user.home") + File.separator + ".inkflow"
    val dbPath = appDataDir + File.separator + "inkflow.db"
    
    // Create app data directory if it doesn't exist
    LaunchedEffect(Unit) {
        File(appDataDir).mkdirs()
        logger.info { "App data directory: $appDataDir" }
    }
    
    val databaseManager = remember { 
        DatabaseManager(dbPath).also { it.connect() }
    }
    
    // Initialize local sync manager
    val syncManager = remember { LocalSyncManager(databaseManager, appDataDir) }
    
    // Start sync service
    LaunchedEffect(Unit) {
        syncManager.startListening()
        logger.info { "Local sync service started" }
    }
    
    // Cleanup on exit
    DisposableEffect(Unit) {
        onDispose {
            syncManager.stopListening()
            databaseManager.disconnect()
        }
    }
    
    var selectedDocument by remember { mutableStateOf<String?>(null) }
    var currentPageIndex by remember { mutableStateOf(0) }
    var isSyncing by remember { mutableStateOf(false) }
    
    Column(modifier = Modifier.fillMaxSize()) {
        // Top App Bar
        TopAppBar(
            title = { Text("InkFlow - PDF Reader") },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ),
            actions = {
                // Sync status indicator
                IconButton(
                    onClick = { 
                        isSyncing = true
                        syncManager.broadcastSync()
                        isSyncing = false
                    },
                    enabled = !isSyncing
                ) {
                    Icon(
                        if (isSyncing) Icons.Default.Refresh else Icons.Default.Sync,
                        contentDescription = "Sync",
                        tint = if (syncManager.isConnected) MaterialTheme.colorScheme.primary 
                              else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                if (selectedDocument != null) {
                    IconButton(onClick = { selectedDocument = null }) {
                        Icon(Icons.Default.Home, contentDescription = "Back to Library")
                    }
                }
            }
        )
        
        // Main content area
        Row(modifier = Modifier.fillMaxSize()) {
            // Navigation Rail (sidebar)
            NavigationRail(
                modifier = Modifier.width(80.dp),
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                header = {
                    Spacer(modifier = Modifier.height(12.dp))
                }
            ) {
                NavigationRailItem(
                    selected = selectedDocument == null,
                    onClick = { selectedDocument = null },
                    icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                    label = { Text("Home") }
                )
                NavigationRailItem(
                    selected = selectedDocument != null,
                    onClick = { /* Open document */ },
                    icon = { Icon(Icons.Default.FolderOpen, contentDescription = "Documents") },
                    label = { Text("Docs") }
                )
                NavigationRailItem(
                    selected = false,
                    onClick = { /* Settings */ },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") }
                )
                
                Spacer(modifier = Modifier.weight(1f))
                
                // Sync status indicator at bottom
                NavigationRailItem(
                    selected = false,
                    onClick = { /* Show sync status */ },
                    icon = { 
                        Icon(
                            if (syncManager.isConnected) Icons.Default.Sync else Icons.Default.SyncDisabled,
                            contentDescription = "Sync Status"
                        ) 
                    },
                    label = { 
                        Text(
                            if (syncManager.isConnected) "Connected" else "Offline",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                )
            }
            
            // Content area
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .background(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.shapes.medium
                    )
            ) {
                if (selectedDocument == null) {
                    DocumentLibraryView(
                        databaseManager = databaseManager,
                        onDocumentSelected = { uri ->
                            selectedDocument = uri
                            currentPageIndex = 0
                        }
                    )
                } else {
                    PdfViewerWithPdfBox(
                        documentUri = selectedDocument!!,
                        pageIndex = currentPageIndex,
                        databaseManager = databaseManager,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
fun DocumentReaderView(
    documentUri: String,
    databaseManager: DatabaseManager,
    onPageChanged: (Int) -> Unit = {}
) {
    var currentPageIndex by remember { mutableStateOf(0) }
    val pageCount by remember { mutableStateOf(10) } // Placeholder, will be updated when PDF is loaded
    
    // Notify parent of page changes
    LaunchedEffect(currentPageIndex) {
        onPageChanged(currentPageIndex)
    }
    
    Column(modifier = Modifier.fillMaxSize()) {
        // Page navigation bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { if (currentPageIndex > 0) currentPageIndex-- },
                enabled = currentPageIndex > 0
            ) {
                Text("Previous")
            }
            
            Text("Page ${currentPageIndex + 1} / $pageCount")
            
            Button(
                onClick = { currentPageIndex++ },
                enabled = currentPageIndex < pageCount - 1
            ) {
                Text("Next")
            }
        }
        
        // PDF viewing area with annotations
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .background(Color.LightGray)
        ) {
            // Placeholder for PDF rendering
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "PDF Viewer",
                    style = MaterialTheme.typography.headlineMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Document: $documentUri",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Page: ${currentPageIndex + 1}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "PDF rendering and annotation display will be implemented here",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                // Show strokes from database
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Annotations from database:",
                    style = MaterialTheme.typography.titleSmall
                )
                StrokesDisplay(
                    documentUri = documentUri,
                    pageIndex = currentPageIndex,
                    databaseManager = databaseManager
                )
            }
        }
    }
}

@Composable
fun StrokesDisplay(
    documentUri: String,
    pageIndex: Int,
    databaseManager: DatabaseManager
) {
    var strokes by remember(documentUri, pageIndex) { 
        mutableStateOf<List<StrokeWithPoints>>(emptyList()) 
    }
    
    LaunchedEffect(documentUri, pageIndex) {
        try {
            strokes = databaseManager.getStrokesForPage(documentUri, pageIndex)
            logger.info { "Loaded ${strokes.size} strokes for page $pageIndex" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to load strokes" }
            strokes = emptyList()
        }
    }
    
    if (strokes.isEmpty()) {
        Text(
            text = "No annotations on this page",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        strokes.forEach { strokeWithPoints: StrokeWithPoints ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Stroke ID: ${strokeWithPoints.stroke.id.take(8)}...",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "Points: ${strokeWithPoints.points.size}, Color: ${strokeWithPoints.stroke.color}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "InkFlow",
        state = rememberWindowState(width = 1280.dp, height = 800.dp)
    ) {
        App()
    }
}
