package com.vic.inkflow.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.awt.SwingPanel
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.image.BufferedImage
import java.io.File
import com.vic.inkflow.data.DatabaseManager
import com.vic.inkflow.data.PointEntity
import com.vic.inkflow.data.StrokeEntity
import com.vic.inkflow.data.StrokeWithPoints
import mu.KotlinLogging
import java.awt.RenderingHints

private val logger = KotlinLogging.logger {}

/**
 * Simple canvas for displaying PDF with annotation strokes.
 * This is a read-only viewer - no editing capabilities.
 */
@Composable
fun PdfViewerCanvas(
    documentUri: String,
    pageIndex: Int,
    databaseManager: DatabaseManager,
    modifier: Modifier = Modifier
) {
    var strokes by remember(documentUri, pageIndex) { 
        mutableStateOf<List<StrokeWithPoints>>(emptyList()) 
    }
    
    // Load strokes from database when page changes
    LaunchedEffect(documentUri, pageIndex) {
        try {
            strokes = databaseManager.getStrokesForPage(documentUri, pageIndex)
            logger.info { "Loaded ${strokes.size} strokes for page $pageIndex" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to load strokes" }
            strokes = emptyList()
        }
    }
    
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Draw all strokes
            strokes.forEach { strokeWithPoints ->
                val stroke = strokeWithPoints.stroke
                val points = strokeWithPoints.points
                
                if (points.isNotEmpty()) {
                    val path = Path()
                    val firstPoint = points.first()
                    path.moveTo(firstPoint.x, firstPoint.y)
                    
                    for (i in 1 until points.size) {
                        val point = points[i]
                        path.lineTo(point.x, point.y)
                    }
                    
                    val color = Color(stroke.color)
                    val strokeWidth = stroke.strokeWidth
                    
                    drawPath(
                        path = path,
                        color = if (stroke.isHighlighter) color.copy(alpha = 0.3f) else color,
                        style = Stroke(
                            width = strokeWidth,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }
            }
        }
    }
}

/**
 * PDF Viewer using PDFBox to render PDF pages with annotations overlay.
 */
@Composable
fun PdfViewerWithPdfBox(
    documentUri: String,
    pageIndex: Int,
    databaseManager: DatabaseManager,
    modifier: Modifier = Modifier
) {
    var pdfImage by remember { mutableStateOf<BufferedImage?>(null) }
    var strokes by remember(documentUri, pageIndex) { 
        mutableStateOf<List<StrokeWithPoints>>(emptyList()) 
    }
    var scale by remember { mutableStateOf(1.0f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    
    // Load PDF page image
    LaunchedEffect(documentUri, pageIndex) {
        try {
            // Extract file path from URI (remove "file://" prefix if present)
            val filePath = documentUri.removePrefix("file://")
            val file = File(filePath)
            
            if (file.exists()) {
                val document = PDDocument.load(file)
                try {
                    val pdfRenderer = PDFRenderer(document)
                    // Render at 2x DPI for better quality
                    pdfImage = pdfRenderer.renderImageWithDPI(pageIndex, 150f)
                    logger.info { "Loaded PDF page $pageIndex: ${pdfImage?.width}x${pdfImage?.height}" }
                } finally {
                    document.close()
                }
            } else {
                logger.error { "PDF file not found: $filePath" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to load PDF" }
            pdfImage = null
        }
    }
    
    // Load strokes from database
    LaunchedEffect(documentUri, pageIndex) {
        try {
            strokes = databaseManager.getStrokesForPage(documentUri, pageIndex)
            logger.info { "Loaded ${strokes.size} strokes for page $pageIndex" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to load strokes" }
            strokes = emptyList()
        }
    }
    
    Box(modifier = modifier.fillMaxSize()) {
        SwingPanel(
            factory = {
                javax.swing.JPanel().apply {
                    layout = java.awt.BorderLayout()
                }
            },
            update = { panel ->
                panel.removeAll()
                
                if (pdfImage != null) {
                    val imageLabel = javax.swing.JLabel(javax.swing.ImageIcon(pdfImage))
                    panel.add(imageLabel, java.awt.BorderLayout.CENTER)
                }
                
                panel.repaint()
            },
            modifier = Modifier.fillMaxSize()
        )
        
        // Overlay strokes on top
        Canvas(modifier = Modifier
            .fillMaxSize()
            .transformable(
                state = rememberTransformableState { zoomChange, panChange, _ ->
                    scale = (scale * zoomChange).coerceIn(0.5f, 3.0f)
                    offset += panChange
                }
            )
        ) {
            // Draw all strokes
            strokes.forEach { strokeWithPoints ->
                val stroke = strokeWithPoints.stroke
                val points = strokeWithPoints.points
                
                if (points.isNotEmpty()) {
                    val path = Path()
                    val firstPoint = points.first()
                    path.moveTo(firstPoint.x, firstPoint.y)
                    
                    for (i in 1 until points.size) {
                        val point = points[i]
                        path.lineTo(point.x, point.y)
                    }
                    
                    val color = Color(stroke.color)
                    val strokeWidth = stroke.strokeWidth
                    
                    drawPath(
                        path = path,
                        color = if (stroke.isHighlighter) color.copy(alpha = 0.3f) else color,
                        style = Stroke(
                            width = strokeWidth,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }
            }
        }
    }
}

/**
 * Document library view showing all available documents.
 */
@Composable
fun DocumentLibraryView(
    databaseManager: DatabaseManager,
    onDocumentSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val documents by remember { 
        mutableStateOf(databaseManager.getAllDocuments()) 
    }
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Document Library",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        if (documents.isEmpty()) {
            Text(
                text = "No documents found. Open a PDF file to get started.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            documents.forEach { document ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    onClick = { onDocumentSelected(document.uri) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = document.displayName,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Last opened: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(java.util.Date(document.lastOpenedAt))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (document.isFavorite) {
                            Icon(
                                imageVector = androidx.compose.material.icons.Icons.Default.Star,
                                contentDescription = "Favorite",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}
