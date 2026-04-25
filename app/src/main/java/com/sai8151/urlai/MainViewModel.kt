package com.sai8151.urlai

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.sai8151.urlai.ai.AiClient
import com.sai8151.urlai.ai.AutoTuner
import com.sai8151.urlai.ai.ClaudeClient
import com.sai8151.urlai.ai.GeminiClient
import com.sai8151.urlai.ai.LiteRtClient
import com.sai8151.urlai.ai.LocalModelRegistry
import com.sai8151.urlai.ai.OpenAiClient
import com.sai8151.urlai.ai.TunedConfig
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ChatMessage(
    val role: String,
    val content: String,
    val stats: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private var aiClient: AiClient? = null
    private var activeSessionId: String = System.currentTimeMillis().toString()
    private fun newSession() {
        activeSessionId = System.currentTimeMillis().toString()
    }

    private var currentConversationId: String? = null
    private val prefs = PreferencesManager(application)
    private val poller = UrlPoller()
    private val storage = ConversationStorage(getApplication())
    private val _chatMessages = MutableLiveData<List<ChatMessage>>(emptyList())
    val chatMessages: LiveData<List<ChatMessage>> = _chatMessages

    private val _status = MutableLiveData("Idle")
    val status: LiveData<String> = _status

    private val _isPolling = MutableLiveData(false)
    val isPolling: LiveData<Boolean> = _isPolling

    private var lastContent: String = ""
    private var pollingJob: Job? = null
    private val conversationHistory = mutableListOf<ChatMessage>()
    fun clearChat() {
        newSession()

        // 🔥 generate ID ONCE for new chat
        currentConversationId = System.currentTimeMillis().toString()

        _chatMessages.value = listOf()
        conversationHistory.clear()
        lastContent = ""
    }

    fun loadConversation(conv: Conversation) {
        activeSessionId = conv.id
        currentConversationId = conv.id

        _chatMessages.value = conv.messages.toList()

        conversationHistory.clear()
        conversationHistory.addAll(conv.messages)

        lastContent = ""
    }

    private fun autoSaveConversation() {

        val messages = _chatMessages.value ?: return
        if (messages.isEmpty()) return

        if (currentConversationId == null) {
            currentConversationId = System.currentTimeMillis().toString()
        }

        val id = currentConversationId!!

        val conversation = Conversation(
            id = id,
            title = messages.firstOrNull()?.content?.take(30) ?: "New Chat",
            messages = messages,
            timestamp = System.currentTimeMillis()
        )
        Log.d("CHAT_DEBUG", "Saving ID: $id")
        storage.upsertConversation(conversation)
    }

    fun saveCurrentConversation() {

        val messages = _chatMessages.value ?: return
        if (messages.isEmpty()) return

        // 🔥 use EXISTING ID (NOT NEW)
        val id = currentConversationId ?: System.currentTimeMillis().toString()

        val conversation = Conversation(
            id = id,
            title = messages.firstOrNull()?.content?.take(30) ?: "New Chat",
            messages = messages,
            timestamp = System.currentTimeMillis()
        )

        currentConversationId = id

        storage.upsertConversation(conversation)
    }

    suspend fun sendMessage(userInput: String) {

        try {
            if (currentConversationId == null) {
                currentConversationId = System.currentTimeMillis().toString()
            }
            val sessionAtStart = activeSessionId
            val model = prefs.selectedModel.first()

            if (aiClient == null) {
                aiClient = buildClient(model)
            }

            if (aiClient == null) {
                _status.postValue("Invalid API key / model.")
                return
            }

            val systemPrompt = prefs.systemPrompt.first()

            withContext(Dispatchers.Main) {
                _status.value = "Thinking..."
                addMessage(ChatMessage("user", userInput))
            }

            val messages = _chatMessages.value?.toMutableList() ?: mutableListOf()
            val aiIndex = messages.size
            messages.add(ChatMessage("assistant", ""))
            _chatMessages.postValue(messages)

            val buffer = StringBuilder()

            val (finalText, stats, metrics) = aiClient!!.chat(
                systemPrompt = systemPrompt,
                history = conversationHistory.map { it.role to it.content },
                userMessage = userInput
            ) { token ->
                if (sessionAtStart != activeSessionId) return@chat
                buffer.append(token)

                if (buffer.length > 30) {
                    val updated = _chatMessages.value?.toMutableList() ?: return@chat

                    if (aiIndex >= updated.size) return@chat

                    val current = updated[aiIndex]

                    updated[aiIndex] = current.copy(
                        content = current.content + buffer.toString()
                    )

                    buffer.clear()
                    _chatMessages.postValue(updated)
                }
            }

            // Flush remaining
            if (buffer.isNotEmpty()) {
                val updated = _chatMessages.value?.toMutableList() ?: mutableListOf()
                val current = updated[aiIndex]

                updated[aiIndex] = current.copy(
                    content = current.content + buffer.toString()
                )
                withContext(Dispatchers.Main) {
                    _chatMessages.value = updated
                }
            }

            // Final update
            val updated = _chatMessages.value?.toMutableList() ?: mutableListOf()

            val current = updated[aiIndex]

            updated[aiIndex] = current.copy(
                content = finalText,
                stats = stats
            )

            withContext(Dispatchers.Main) {
                _chatMessages.value = updated
            }

            // Save history
            conversationHistory.add(ChatMessage("user", userInput))
            conversationHistory.add(ChatMessage("assistant", finalText))

            _status.postValue("Done")
            //autoSaveConversation()

        } catch (e: Exception) {
            _status.postValue("Error: ${e.message}")
        }
    }

    suspend fun handlePdfImport(uri: Uri) {
        try {
            if (currentConversationId == null) {
                currentConversationId = System.currentTimeMillis().toString()
            }

            withContext(Dispatchers.Main) {
                _status.value = "Reading PDF..."
            }

            val extractedText = extractTextFromPdf(uri)

            if (extractedText.isBlank()) {
                _status.postValue("PDF is empty")
                return
            }

            val finalPrompt = """
            Analyze this PDF content and provide a useful response.

            PDF Content:
            
            $extractedText
        """.trimIndent()

            sendMessage(finalPrompt)

        } catch (e: Exception) {
            _status.postValue("PDF Error: ${e.message}")
        }
    }
    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun handlePdfToWordImport(uri: Uri) {
        try {
            if (currentConversationId == null) {
                currentConversationId = System.currentTimeMillis().toString()
            }

            withContext(Dispatchers.Main) {
                _status.value = "Processing PDF..."
                // Post initial status bubble into chat
                addMessage(ChatMessage("assistant", "📄 Starting PDF conversion..."))
            }

            val context = getApplication<Application>()
            val model = prefs.selectedModel.first()
            val isLocal = LocalModelRegistry.isLocalModel(model)

            if (aiClient == null) {
                aiClient = buildClient(model)
            }

            if (aiClient == null) {
                updateLastMessage("No valid AI client. Check settings.")
                return
            }

            val tempFile = File(context.cacheDir, "pdf_temp_${System.currentTimeMillis()}.pdf")
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                }
            }

            val pageCount = withContext(Dispatchers.IO) {
                val pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(pfd)
                val count = renderer.pageCount
                renderer.close()
                pfd.close()
                count
            }

            updateLastMessage("Found **$pageCount pages**. Converting page by page...")

            val allPageHtmls = mutableListOf<String>()

            for (pageIndex in 0 until pageCount) {
                val pageNum = pageIndex + 1

                // Update the same bubble with current progress
                val progress = buildProgressMessage(pageNum, pageCount, allPageHtmls.size)
                updateLastMessage(progress)

                val htmlChunk = withContext(Dispatchers.IO) {
                    if (isLocal) {
                        extractPageTextAndConvert(uri, pageIndex, pageNum)
                    } else {
                        renderPageAndConvertViaVision(tempFile, pageIndex, pageNum)
                    }
                }

                allPageHtmls.add(htmlChunk)
            }

            tempFile.delete()

            updateLastMessage("\nAll $pageCount pages converted. Building files...")

            val fullHtml = buildFullHtml(allPageHtmls)
            val htmlSaved = saveHtmlToDownloads(context, fullHtml)
            val docxSaved = saveDocxToDownloads(context, allPageHtmls)

            val resultMsg = buildString {
                append("**PDF conversion complete!**\n\n")
                append("$pageCount pages processed\n")
                if (htmlSaved) append("HTML saved to Downloads\n")
                if (docxSaved != null) append("DOC saved to Downloads\n")
                if (!htmlSaved && docxSaved == null) append("Save failed — check storage permissions")
                if (docxSaved != null) append("\n[DOCX_PATH:$docxSaved]")
            }

            withContext(Dispatchers.Main) {
                updateLastMessage(resultMsg)
                _status.value = "Done!"
            }

        } catch (e: Exception) {
            updateLastMessage("PDF Conversion Error: ${e.message}")
            _status.postValue("Error")
            Log.e("PdfToWord", "Error", e)
        }
    }
    // Builds the animated progress text shown in the chat bubble
    private fun buildProgressMessage(current: Int, total: Int, done: Int): String {
        val filled = (done.toFloat() / total * 20).toInt().coerceIn(0, 20)
        val bar = "█".repeat(filled) + "░".repeat(20 - filled)
        return "Converting PDF...\n\n" +
                "[$bar] $done/$total\n\n" +
                "Processing page $current of $total..."
    }

    // Updates the last message in the chat list in-place (no new bubble)
    private fun updateLastMessage(text: String) {
        val updated = _chatMessages.value?.toMutableList() ?: return
        if (updated.isEmpty()) return
        val lastIndex = updated.size - 1
        updated[lastIndex] = updated[lastIndex].copy(content = text)
        _chatMessages.postValue(updated)
    }
    // ─────────────────────────────────────────────────────────────
// LOCAL PATH: extract text for one page → ask LLM → get HTML
// ─────────────────────────────────────────────────────────────
    private suspend fun extractPageTextAndConvert(
        uri: Uri,
        pageIndex: Int,
        pageNum: Int
    ): String {
        return try {
            val context = getApplication<Application>()

            // Copy URI to temp file once
            val tempFile = withContext(Dispatchers.IO) {
                val file = File(context.cacheDir, "pdf_temp_${System.currentTimeMillis()}.pdf")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                file
            }

            val imageBytes = withContext(Dispatchers.IO) {
                val pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(pfd)
                val page = renderer.openPage(pageIndex)

                val scale = minOf(1200f / page.width, 1200f / page.height, 2.0f)
                val bitmap = Bitmap.createBitmap(
                    (page.width * scale).toInt(),
                    (page.height * scale).toInt(),
                    Bitmap.Config.ARGB_8888  // ← ARGB_8888 not RGB_565, required by vision encoder
                )
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                renderer.close()
                pfd.close()
                tempFile.delete()

                val baos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                bitmap.recycle()
                baos.toByteArray()
            }

            val prompt = """
This is page $pageNum of a PDF document.
Recreate this document EXACTLY as visually shown.

Preserve:
- exact table structure
- borders
- merged cells
- text alignment
- indentation
- bold/italic
- spacing
- paragraph breaks
- section hierarchy
- line spacing
- margins
- page layout
- headers
- footers
- signatures
- underlines
- stamps if visible

Output complete production-grade HTML with inline CSS (Full HTML + inline CSS).

Do not simplify.
Do not summarize.
Do not infer missing text.
Reconstruct exact visual layout.

Return only HTML body content.
        """.trimIndent()

            val systemPrompt = "You are a precise document formatter. Output only valid inner HTML."

            val liteRtClient = aiClient as? LiteRtClient
                ?: return "<p><em>Page $pageNum: local model required for vision</em></p>"

            val (html, _, _) = liteRtClient.chatWithImage(
                systemPrompt = systemPrompt,
                imageBytes = imageBytes,
                userMessage = prompt
            )

            stripToBodyContent(html)
        } catch (e: Exception) {
            "<p><em>Page $pageNum error: ${e.message}</em></p>"
        }
    }
    // ─────────────────────────────────────────────────────────────
    // CLOUD PATH: render page to PNG → base64 → vision prompt
    // ─────────────────────────────────────────────────────────────
    private suspend fun renderPageAndConvertViaVision(
        pdfFile: File,
        pageIndex: Int,
        pageNum: Int,
    ): String {
        return try {
            val base64Image = withContext(Dispatchers.IO) {
                val pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(pfd)
                val page = renderer.openPage(pageIndex)

                // Scale to max 1200px wide to balance quality vs token size
                val scale = minOf(1200f / page.width, 1200f / page.height, 2.0f)
                val bitmapWidth = (page.width * scale).toInt()
                val bitmapHeight = (page.height * scale).toInt()

                val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.RGB_565)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                renderer.close()
                pfd.close()

                // Compress to JPEG at 75% quality to reduce token usage
                val baos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 75, baos)
                bitmap.recycle()

                Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            }

            val prompt = """
            This is page $pageNum of a PDF document rendered as an image.
            Recreate this document EXACTLY as visually shown.

Preserve:
- exact table structure
- borders
- merged cells
- text alignment
- indentation
- bold/italic
- spacing
- paragraph breaks
- section hierarchy
- line spacing
- margins
- page layout
- headers
- footers
- signatures
- underlines
- stamps if visible
- always give paragraphs as justified

Output complete production-grade HTML with inline CSS (Full HTML + inline CSS).

Do not simplify.
Do not summarize.
Do not infer missing text.
Reconstruct exact visual layout.

Return only HTML body content.
IMAGE (base64 JPEG): data:image/jpeg;base64,$base64Image
        """.trimIndent()

            val systemPrompt = "You are a precise document formatter. Output only valid inner HTML."

            val (html, _, _) = aiClient!!.chat(
                systemPrompt = systemPrompt,
                history = emptyList(),
                userMessage = prompt,
                onToken = null
            )

            stripToBodyContent(html)
        } catch (e: Exception) {
            "<p><em>Page $pageNum render error: ${e.message}</em></p>"
        }
    }

    // ─────────────────────────────────────────────────────────────
// Strip any accidental <html>/<body> wrapper from LLM output
// ─────────────────────────────────────────────────────────────
    private fun stripToBodyContent(raw: String): String {
        val bodyRegex = Regex(
            """<body[^>]*>(.*?)</body>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val extracted = bodyRegex.find(raw)?.groupValues?.get(1)?.trim()
            ?: raw
                .replace(Regex("""</?html[^>]*>""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""<head[^>]*>.*?</head>""",
                    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
                .replace(Regex("""</?body[^>]*>""", RegexOption.IGNORE_CASE), "")
                .trim()

        // Collapse 3+ consecutive <br> or empty <p> tags into a single one
        return extracted
            .replace(Regex("""(<br\s*/?>[\s\n]*){3,}""", RegexOption.IGNORE_CASE), "<br>")
            .replace(Regex("""(<p[^>]*>\s*</p>[\s\n]*){3,}""", RegexOption.IGNORE_CASE), "<p></p>")
            .trim()
    }

    // ─────────────────────────────────────────────────────────────
// Build complete HTML document
// ─────────────────────────────────────────────────────────────
    private fun buildFullHtml(pages: List<String>): String {
        val sb = StringBuilder()
        sb.append("""
        <!DOCTYPE html><html><head><meta charset="UTF-8">
        <style>
  body { font-family: Calibri, sans-serif; margin: 2cm; line-height: 1.15; }
  .page { page-break-after: always; }
  p { margin: 0; padding: 0; margin-bottom: 4pt; }
  br { line-height: 1.15; }
  table { border-collapse: collapse; width: 100%; margin-bottom: 8pt; }
  td, th { border: 1px solid #ccc; padding: 4px 8px; }
  th { background: #f0f0f0; font-weight: bold; }
  h1 { font-size: 20pt; margin: 8pt 0 4pt 0; }
  h2 { font-size: 16pt; margin: 6pt 0 4pt 0; }
  h3 { font-size: 13pt; margin: 4pt 0 2pt 0; }
  ul, ol { margin: 0; padding-left: 20pt; }
  li { margin-bottom: 2pt; }
</style></head><body>
    """.trimIndent())
        pages.forEachIndexed { i, html ->
            sb.append("""<div class="page"><div class="page-num">Page ${i + 1}</div>$html</div>""")
        }
        sb.append("</body></html>")
        return sb.toString()
    }

    // ─────────────────────────────────────────────────────────────
// Save HTML to Downloads
// ─────────────────────────────────────────────────────────────
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveHtmlToDownloads(context: Application, html: String): Boolean {
        return try {
            val filename = "converted_${System.currentTimeMillis()}.html"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, "text/html")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(html.toByteArray(Charsets.UTF_8))
            }
            true
        } catch (e: Exception) {
            Log.e("PdfToWord", "HTML save error", e)
            false
        }
    }

    // ─────────────────────────────────────────────────────────────
// Convert HTML pages → DOCX and save to Downloads
// ─────────────────────────────────────────────────────────────
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveDocxToDownloads(context: Application, pages: List<String>): String? {
        return try {
            val wordHtml = """
            <html xmlns:o='urn:schemas-microsoft-com:office:office'
                  xmlns:w='urn:schemas-microsoft-com:office:word'
                  xmlns='http://www.w3.org/TR/REC-html40'>
            <head>
                <meta charset='UTF-8'>
                <meta name=ProgId content=Word.Document>
                <meta name=Generator content='Microsoft Word 14'>
                <meta name=Originator content='Microsoft Word 14'>
                <!--[if gte mso 9]>
                <xml><w:WordDocument><w:View>Print</w:View></w:WordDocument></xml>
                <![endif]-->
                <style>
                  body { font-family: Calibri, serif; margin: 2cm; }
                  .page { page-break-after: always; margin-bottom: 32px; padding: 24px; }
                  table { border-collapse: collapse; width: 100%; }
                  td, th { border: 1px solid #ccc; padding: 6px 10px; }
                  th { background: #f0f0f0; font-weight: bold; }
                  h1 { font-size: 24pt; }
                  h2 { font-size: 18pt; }
                  h3 { font-size: 14pt; }
                  p  { font-size: 11pt; line-height: 1.5; }
                </style>
            </head>
            <body>
                ${pages.mapIndexed { i, page ->
                "<div class='page'>$page</div>"
            }.joinToString("\n")}
            </body>
            </html>
        """.trimIndent()

            val filename = "converted_${System.currentTimeMillis()}.doc"
            val file = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                filename
            )
            file.writeText(wordHtml, Charsets.UTF_8)
            file.absolutePath  // null means failed, path means success
        } catch (e: Exception) {
            Log.e("PdfToWord", "DOC save error", e)
            null
        }
    }


    private suspend fun extractTextFromPdf(uri: Uri): String =
        withContext(Dispatchers.IO) {

            try {
                val context = getApplication<Application>()

                PDFBoxResourceLoader.init(context)

                context.contentResolver.openInputStream(uri).use { inputStream ->

                    if (inputStream == null) return@withContext ""

                    val document = PDDocument.load(inputStream)
                    val stripper = PDFTextStripper()

                    val text = stripper.getText(document)

                    document.close()

                    text.take(15000) // prevent huge prompts
                }

            } catch (e: Exception) {
                ""
            }
        }

    fun startPolling() {
        if (pollingJob?.isActive == true) return

        _isPolling.value = true
        _status.value = "Initializing..."

        pollingJob = viewModelScope.launch(Dispatchers.IO) {

            try {
                val url = prefs.targetUrl.first()
                if (url.isBlank()) {
                    _status.postValue("No URL set. Go to Settings.")
                    return@launch
                }

                val model = prefs.selectedModel.first()

                // ✅ Build client ONCE
                if (aiClient == null) {
                    aiClient = buildClient(model)
                }

                if (aiClient == null) {
                    _status.postValue("Invalid API key / model.")
                    return@launch
                }

                _status.postValue("Ready. Polling...")

            } catch (e: Exception) {
                _status.postValue("Init Error: ${e.message}")
                return@launch
            }

            while (isActive) {
                val sessionAtStart = activeSessionId
                try {
                    val url = prefs.targetUrl.first()
                    val content = poller.fetchContent(url)

                    if (content != lastContent) {
                        if (sessionAtStart != activeSessionId) return@launch
                        val isFirstFetch = lastContent.isEmpty()
                        lastContent = content

                        val systemPrompt = prefs.systemPrompt.first()

                        val userMessage = if (isFirstFetch)
                            "URL content loaded:\n\n$content"
                        else
                            "URL content updated:\n\n$content"

                        withContext(Dispatchers.Main) {
                            _status.value = "Processing..."
                            addMessage(ChatMessage("user", userMessage))
                        }

                        val messages = _chatMessages.value?.toMutableList() ?: mutableListOf()
                        val aiIndex = messages.size
                        messages.add(ChatMessage("assistant", ""))
                        _chatMessages.postValue(messages)

                        val buffer = StringBuilder()

                        val (finalText, stats, metrics) = aiClient!!.chat(
                            systemPrompt = systemPrompt,
                            history = conversationHistory.map { it.role to it.content },
                            userMessage = userMessage
                        ) { token ->
                            if (sessionAtStart != activeSessionId) return@chat
                            buffer.append(token)

                            if (buffer.length > 30) {
                                val updated = _chatMessages.value?.toMutableList() ?: return@chat

                                if (aiIndex >= updated.size) return@chat

                                val current = updated[aiIndex]

                                updated[aiIndex] = current.copy(
                                    content = current.content + buffer.toString()
                                )

                                buffer.clear()
                                _chatMessages.postValue(updated)
                            }
                        }

                        // Flush remaining buffer
                        // Flush remaining
                        if (buffer.isNotEmpty()) {

                            if (sessionAtStart != activeSessionId) return@launch

                            val updated = _chatMessages.value?.toMutableList() ?: return@launch

                            if (aiIndex >= updated.size) return@launch

                            val current = updated[aiIndex]

                            updated[aiIndex] = current.copy(
                                content = current.content + buffer.toString()
                            )

                            withContext(Dispatchers.Main) {
                                _chatMessages.value = updated
                            }
                        }

// Final update
                        if (sessionAtStart != activeSessionId) return@launch

                        val updated = _chatMessages.value?.toMutableList() ?: return@launch

                        if (aiIndex >= updated.size) return@launch

                        val current = updated[aiIndex]

                        updated[aiIndex] = current.copy(
                            content = finalText,
                            stats = stats
                        )

                        _chatMessages.postValue(updated)
                        // Save history
                        conversationHistory.add(ChatMessage("user", userMessage))
                        conversationHistory.add(ChatMessage("assistant", finalText))

                        // ✅ SAFE AUTO-TUNE (NO OVERWRITE BUG)
                        if (aiClient?.isLocal() == true) {

                            val currentConfig = TunedConfig(
                                useGpu = prefs.useGpu.first(),
                                topK = prefs.topK.first(),
                                topP = prefs.topP.first(),
                                temperature = prefs.temperature.first(),
                                contextSize = prefs.contextSize.first()
                            )

                            val newConfig = AutoTuner.tune(currentConfig, metrics)

                            val currentUrl = prefs.targetUrl.first()
                            val currentGemini = prefs.geminiKey.first()
                            val currentClaude = prefs.claudeKey.first()
                            val currentOpenAi = prefs.openAiKey.first()
                            val currentPrompt = prefs.systemPrompt.first()
                            val currentModel = prefs.selectedModel.first()

                            prefs.save(
                                url = currentUrl,
                                gemini = currentGemini,
                                claude = currentClaude,
                                openAi = currentOpenAi,
                                prompt = currentPrompt,
                                model = currentModel,
                                useGpu = newConfig.useGpu,
                                temperature = newConfig.temperature,
                                topK = newConfig.topK,
                                topP = newConfig.topP,
                                contextSize = newConfig.contextSize
                            )

                            prefs.saveMetrics(
                                metrics.tps,
                                metrics.firstLatency,
                                metrics.totalTime
                            )
                        }

                        _status.postValue("Updated at ${currentTime()}")
                    }

                } catch (e: Exception) {
                    _status.postValue("Error: ${e.message}")
                }

                delay(3000)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        _isPolling.value = false
        _status.value = "Stopped"
    }

    fun refreshChat() {
        viewModelScope.launch {

            pollingJob?.cancelAndJoin()
            pollingJob = null

            conversationHistory.clear()
            lastContent = ""

            withContext(Dispatchers.Main) {
                _chatMessages.value = emptyList()
                _status.value = "Chat refreshed. Press Start."
                _isPolling.value = false
            }

            try {
                (aiClient as? LiteRtClient)?.resetConversation()
            } catch (_: Exception) {
            }

            aiClient = null
        }
    }

    private fun addMessage(msg: ChatMessage) {
        val current = _chatMessages.value?.toMutableList() ?: mutableListOf()
        current.add(msg)
        _chatMessages.value = current
    }

    private suspend fun buildClient(model: String): AiClient? {

        if (LocalModelRegistry.isLocalModel(model)) {
            return LiteRtClient(
                getApplication(),
                model,
                prefs.useGpu.first(),
                prefs.temperature.first(),
                prefs.topK.first(),
                prefs.topP.first(),
                prefs.contextSize.first()
            )
        }

        return when {
            model.startsWith("gemini") -> {
                val key = prefs.geminiKey.first()
                if (key.isBlank()) null else GeminiClient(key, model)
            }

            model.startsWith("claude") -> {
                val key = prefs.claudeKey.first()
                if (key.isBlank()) null else ClaudeClient(key, model)
            }

            model.startsWith("gpt") || model.startsWith("o1") || model.startsWith("o3") -> {
                val key = prefs.openAiKey.first()
                if (key.isBlank()) null else OpenAiClient(key, model)
            }

            else -> null
        }
    }

    private fun currentTime(): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    }
}