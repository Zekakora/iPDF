package com.doge.ipdf

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.io.InputStream

/**
 * Reads both this app's saved /Ink annotations and adoptable foreign /Ink or /Highlight annotations
 * in a single PDFBox pass. The previous open path loaded the same PDF twice, which made opening a
 * clean document needlessly expensive.
 */
object PdfAnnotationReader {
    data class Result(
        val ours: List<Stroke>,
        val foreign: List<Stroke>,
        val ok: Boolean,
    )

    fun read(file: File): Result = runCatching {
        file.inputStream().use { read(it) }
    }.getOrElse { Result(emptyList(), emptyList(), false) }

    fun read(stream: InputStream): Result = runCatching {
        stream.use { input ->
            PDDocument.load(input).use { document ->
                val ours = ArrayList<Stroke>()
                val foreign = ArrayList<Stroke>()
                for (pageIndex in 0 until document.numberOfPages) {
                    val page = document.getPage(pageIndex)
                    val annots = page.cosObject
                        .getDictionaryObject(COSName.ANNOTS) as? COSArray ?: continue
                    val ourPageHeight = page.mediaBox.height
                    val foreignEnabled = page.rotation % 360 == 0
                    val crop = page.cropBox
                    val cropLeft = crop.lowerLeftX
                    val cropBottom = crop.lowerLeftY
                    val cropHeight = crop.height
                    for (annotationIndex in 0 until annots.size()) {
                        val dict = annots.getObject(annotationIndex) as? COSDictionary ?: continue
                        if (PdfInkExporter.isOurInk(dict)) {
                            PdfInkImporter.toStroke(dict, pageIndex, ourPageHeight)?.let(ours::add)
                            continue
                        }
                        if (!foreignEnabled) continue
                        when (dict.getNameAsString(COSName.SUBTYPE)) {
                            "Ink" -> ForeignInkReader.addInk(
                                dict,
                                pageIndex,
                                annotationIndex,
                                cropLeft,
                                cropBottom,
                                cropHeight,
                                foreign,
                            )

                            "Highlight" -> ForeignInkReader.addHighlight(
                                dict,
                                pageIndex,
                                annotationIndex,
                                cropLeft,
                                cropBottom,
                                cropHeight,
                                foreign,
                            )
                        }
                    }
                }
                Result(ours = ours, foreign = foreign, ok = true)
            }
        }
    }.getOrElse { Result(emptyList(), emptyList(), false) }
}
