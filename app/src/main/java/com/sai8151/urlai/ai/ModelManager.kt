package com.sai8151.urlai.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

object ModelManager {

    private const val MODEL_URL =
        "https://huggingface.co/sai8151/gemma-4-e2b-it-litertlm/resolve/main/gemma-4-e2b-it.litertlm"

    private const val FILE_NAME = "gemma-4-e2b-it.litertlm"

    fun getModelFile(context: Context): File {
        return File(context.filesDir, FILE_NAME)
    }

    fun isModelDownloaded(context: Context): Boolean {
        val file = getModelFile(context)

        if (!file.exists()) return false

        // 🔥 Validate size (~2.5GB expected)
        if (file.length() < 1_000_000_000) { // <1GB = invalid
            file.delete()
            return false
        }

        return true
    }
    suspend fun downloadModel(
        context: Context,
        onProgress: (Int) -> Unit
    ) = withContext(Dispatchers.IO) {

        val client = OkHttpClient()
        val request = Request.Builder().url(MODEL_URL).build()

        val response = client.newCall(request).execute()
        val body = response.body ?: throw Exception("Download failed")

        val total = body.contentLength()
        val input = body.byteStream()
        val file = getModelFile(context)

        file.outputStream().use { output ->
            val buffer = ByteArray(8192)
            var downloaded = 0L
            var read: Int

            while (input.read(buffer).also { read = it } != -1) {
                output.write(buffer, 0, read)
                downloaded += read

                val progress = ((downloaded * 100) / total).toInt()
                onProgress(progress)
            }
        }
    }
}