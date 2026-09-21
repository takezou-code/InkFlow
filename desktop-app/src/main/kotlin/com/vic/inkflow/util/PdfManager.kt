package com.vic.inkflow.util

import mu.KotlinLogging
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.pdmodel.PDPage
import java.awt.image.BufferedImage
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * PDF Manager for Windows desktop app.
 * Handles PDF loading, rendering to images, and page operations.
 */
class PdfManager {
    
    /**
     * Load a PDF document from file path.
     */
    fun loadPdf(filePath: String): PDDocument? {
        return try {
            val file = File(filePath)
            if (!file.exists()) {
                logger.error { "PDF file not found: $filePath" }
                return null
            }
            PDDocument.load(file)
        } catch (e: Exception) {
            logger.error(e) { "Failed to load PDF: $filePath" }
            null
        }
    }
    
    /**
     * Get the number of pages in a PDF.
     */
    fun getPageCount(document: PDDocument): Int {
        return document.numberOfPages
    }
    
    /**
     * Render a PDF page to a BufferedImage.
     * @param document The PDF document
     * @param pageIndex The page index (0-based)
     * @param zoom The zoom level (1.0 = 100%)
     */
    fun renderPage(document: PDDocument, pageIndex: Int, zoom: Float = 1.0f): BufferedImage? {
        return try {
            if (pageIndex < 0 || pageIndex >= document.numberOfPages) {
                logger.error { "Invalid page index: $pageIndex" }
                return null
            }
            
            val renderer = PDFRenderer(document)
            // PDFBox uses DPI for rendering, convert zoom to DPI
            // Default is 72 DPI, so zoom 1.0 = 72 DPI
            val dpi = (72 * zoom).toInt()
            renderer.renderImageWithDPI(pageIndex, dpi.toFloat(), org.apache.pdfbox.rendering.ImageType.RGB)
        } catch (e: Exception) {
            logger.error(e) { "Failed to render page $pageIndex" }
            null
        }
    }
    
    /**
     * Get page dimensions in points (1/72 inch).
     */
    fun getPageSize(document: PDDocument, pageIndex: Int): Pair<Float, Float>? {
        return try {
            if (pageIndex < 0 || pageIndex >= document.numberOfPages) {
                return null
            }
            val page = document.getPage(pageIndex)
            Pair(page.mediaBox.width, page.mediaBox.height)
        } catch (e: Exception) {
            logger.error(e) { "Failed to get page size" }
            null
        }
    }
    
    /**
     * Add a blank page to the PDF.
     */
    fun addBlankPage(document: PDDocument, pageIndex: Int = -1): Boolean {
        return try {
            val newPage = PDPage()
            if (pageIndex < 0 || pageIndex >= document.numberOfPages) {
                // Add to end
                document.addPage(newPage)
            } else {
                // Insert at specific position
                document.pages.insertBefore(newPage, document.getPage(pageIndex))
            }
            true
        } catch (e: Exception) {
            logger.error(e) { "Failed to add blank page" }
            false
        }
    }
    
    /**
     * Delete a page from the PDF.
     */
    fun deletePage(document: PDDocument, pageIndex: Int): Boolean {
        return try {
            if (document.numberOfPages <= 1) {
                logger.warn { "Cannot delete the only page" }
                return false
            }
            document.removePage(pageIndex)
            true
        } catch (e: Exception) {
            logger.error(e) { "Failed to delete page $pageIndex" }
            false
        }
    }
    
    /**
     * Save the PDF document to a file.
     */
    fun savePdf(document: PDDocument, filePath: String): Boolean {
        return try {
            // Atomic write: save to temp file first, then rename
            val tempFile = File("$filePath.tmp_${System.currentTimeMillis()}")
            document.save(tempFile)
            
            // Rename temp file to original
            val destFile = File(filePath)
            if (tempFile.renameTo(destFile)) {
                logger.info { "PDF saved successfully: $filePath" }
                true
            } else {
                // Fallback: copy and delete
                tempFile.copyTo(destFile, overwrite = true)
                tempFile.delete()
                logger.info { "PDF saved successfully (copy method): $filePath" }
                true
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to save PDF: $filePath" }
            false
        }
    }
    
    /**
     * Close the PDF document.
     */
    fun closePdf(document: PDDocument) {
        try {
            document.close()
            logger.info { "PDF document closed" }
        } catch (e: Exception) {
            logger.error(e) { "Error closing PDF document" }
        }
    }
}
