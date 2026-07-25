package com.example.aquiethours.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object OcrHelper {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognizeTextFromBitmap(bitmap: Bitmap): String {
        return suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    cont.resume(visionText.text)
                }
                .addOnFailureListener { e ->
                    cont.resumeWithException(e)
                }
        }
    }

    suspend fun recognizeTextFromUri(context: Context, uri: Uri): String {
        return suspendCancellableCoroutine { cont ->
            try {
                val image = InputImage.fromFilePath(context, uri)
                recognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        cont.resume(visionText.text)
                    }
                    .addOnFailureListener { e ->
                        cont.resumeWithException(e)
                    }
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        }
    }

    suspend fun recognizeTextFromPdf(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val parcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw Exception("Cannot open PDF file")

        val renderer = PdfRenderer(parcelFileDescriptor)
        val allText = StringBuilder()

        try {
            for (i in 0 until renderer.pageCount) {
                val page = renderer.openPage(i)
                // Render at 2x for better OCR quality
                val width = page.width * 2
                val height = page.height * 2
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()

                val pageText = recognizeTextFromBitmap(bitmap)
                if (pageText.isNotBlank()) {
                    allText.append("--- Page ${i + 1} ---\n")
                    allText.append(pageText)
                    allText.append("\n\n")
                }
                bitmap.recycle()
            }
        } finally {
            renderer.close()
            parcelFileDescriptor.close()
        }

        allText.toString().trim()
    }

    suspend fun getResizedBase64FromUri(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val originalBitmap = BitmapFactory.decodeStream(inputStream) ?: return@withContext null
            inputStream?.close()
            
            var width = originalBitmap.width
            var height = originalBitmap.height
            val maxDimension = 1920
            
            if (width > maxDimension || height > maxDimension) {
                val ratio = width.toFloat() / height.toFloat()
                if (width > height) {
                    width = maxDimension
                    height = (maxDimension / ratio).toInt()
                } else {
                    height = maxDimension
                    width = (maxDimension * ratio).toInt()
                }
            }
            
            val resizedBitmap = Bitmap.createScaledBitmap(originalBitmap, width, height, true)
            val outputStream = ByteArrayOutputStream()
            resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
            
            if (originalBitmap != resizedBitmap) {
                originalBitmap.recycle()
            }
            resizedBitmap.recycle()
            
            Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
