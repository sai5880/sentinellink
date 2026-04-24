package com.sai8151.urlai

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.linkify.LinkifyPlugin

// ── Segment model ─────────────────────────────────────────────────────────────

sealed class MessageSegment {
    data class Text(val content: String) : MessageSegment()
    data class Code(val language: String, val content: String) : MessageSegment()
}

// ── Adapter ───────────────────────────────────────────────────────────────────

class ChatAdapter(
    private var messages: List<ChatMessage>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private lateinit var markwon: Markwon

    /** When true, a typing-indicator row is appended after all messages. */
    var isTyping: Boolean = false
        private set

    companion object {
        private const val VIEW_TYPE_MESSAGE = 0
        private const val VIEW_TYPE_TYPING  = 1
    }

    private val langColors = mapOf(
        "python"     to 0xFF3572A5.toInt(),
        "kotlin"     to 0xFF7F52FF.toInt(),
        "java"       to 0xFFB07219.toInt(),
        "javascript" to 0xFFF1E05A.toInt(),
        "typescript" to 0xFF2B7489.toInt(),
        "html"       to 0xFFE34C26.toInt(),
        "css"        to 0xFF563D7C.toInt(),
        "xml"        to 0xFF0060AC.toInt(),
        "json"       to 0xFF40B971.toInt(),
        "sql"        to 0xFFE38C00.toInt(),
        "bash"       to 0xFF89E051.toInt(),
        "sh"         to 0xFF89E051.toInt(),
        "c"          to 0xFF555555.toInt(),
        "cpp"        to 0xFFF34B7D.toInt(),
        "rust"       to 0xFFDEA584.toInt(),
        "go"         to 0xFF00ADD8.toInt(),
        "swift"      to 0xFFF05138.toInt(),
        "dart"       to 0xFF00B4AB.toInt(),
        "ruby"       to 0xFF701516.toInt(),
        "php"        to 0xFF4F5D95.toInt()
    )

    // ── ViewHolders ───────────────────────────────────────────────────────────

    inner class ChatViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val llContainer: LinearLayout     = view.findViewById(R.id.llContainer)
        val tvRole: TextView              = view.findViewById(R.id.tvRole)
        val cardMessage: MaterialCardView = view.findViewById(R.id.cardMessage)
        val llContent: LinearLayout       = view.findViewById(R.id.llContent)
        val llToolbar: LinearLayout       = view.findViewById(R.id.llToolbar)
        val btnCopyAll: MaterialButton    = view.findViewById(R.id.btnCopyAll)
    }

    inner class TypingViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val typingIndicator: TypingIndicatorView = view.findViewById(R.id.typingIndicator)
    }

    // ── RecyclerView overrides ────────────────────────────────────────────────

    override fun getItemCount(): Int = messages.size + if (isTyping) 1 else 0

    override fun getItemViewType(position: Int): Int =
        if (isTyping && position == messages.size) VIEW_TYPE_TYPING else VIEW_TYPE_MESSAGE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_TYPING -> {
                val view = inflater.inflate(R.layout.item_typing_indicator, parent, false)
                TypingViewHolder(view)
            }
            else -> {
                val view = inflater.inflate(R.layout.item_chat_message, parent, false)
                if (!::markwon.isInitialized) {
                    markwon = Markwon.builder(parent.context)
                        .usePlugin(StrikethroughPlugin.create())
                        .usePlugin(TablePlugin.create(parent.context))
                        .usePlugin(TaskListPlugin.create(parent.context))
                        .usePlugin(LinkifyPlugin.create())
                        .build()
                }
                ChatViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is TypingViewHolder -> holder.typingIndicator.startAnimation()
            is ChatViewHolder   -> bindMessage(holder, position)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is TypingViewHolder) holder.typingIndicator.stopAnimation()
        super.onViewRecycled(holder)
    }

    private fun bindMessage(holder: ChatViewHolder, position: Int) {
        val msg = messages.getOrNull(position) ?: return
        val isUser = msg.role.equals("user", ignoreCase = true)
        val rawContent = msg.content.orEmpty()

        holder.tvRole.text = if (isUser) "You" else "AI"

        val params = holder.cardMessage.layoutParams as LinearLayout.LayoutParams
        if (isUser) {
            holder.llContainer.gravity = Gravity.END
            params.gravity = Gravity.END
            holder.cardMessage.setCardBackgroundColor(0xFF2B2B2B.toInt())
        } else {
            holder.llContainer.gravity = Gravity.START
            params.gravity = Gravity.START
            holder.cardMessage.setCardBackgroundColor(0xFF1A1A1A.toInt())
        }
        holder.cardMessage.layoutParams = params

        holder.llContent.removeAllViews()

        if (isUser) {
            holder.llContent.addView(makeTextView(holder.llContent.context, rawContent))
            holder.llToolbar.visibility = View.GONE
        } else {
            val segments = parseSegments(rawContent)
            for (segment in segments) {
                when (segment) {
                    is MessageSegment.Text -> {
                        if (segment.content.isNotBlank())
                            holder.llContent.addView(makeMarkdownView(holder.llContent.context, segment.content))
                    }
                    is MessageSegment.Code ->
                        holder.llContent.addView(makeCodeBlockView(holder.llContent.context, segment))
                }
            }
            holder.llToolbar.visibility = View.VISIBLE
            holder.btnCopyAll.setOnClickListener {
                copyToClipboard(it.context, rawContent, "Response copied")
                animateCopied(holder.btnCopyAll, "Copied!")
            }
        }
    }

    // ── Typing indicator control ──────────────────────────────────────────────

    /**
     * Show the animated typing row.
     * Call this immediately after the user sends a message (before the API responds).
     */
    fun showTyping() {
        if (isTyping) return
        isTyping = true
        notifyItemInserted(messages.size)
    }

    /**
     * Remove the typing row and reveal the finished response.
     * Call this after updateData() so the final message takes its place.
     */
    fun hideTyping() {
        if (!isTyping) return
        val indicatorPos = messages.size
        isTyping = false
        notifyItemRemoved(indicatorPos)
    }

    // ── Segment parser ────────────────────────────────────────────────────────

    private fun parseSegments(raw: String): List<MessageSegment> {
        val segments = mutableListOf<MessageSegment>()
        val lines = raw.lines()
        val textBuffer = StringBuilder()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]
            val openFence = Regex("^```(\\w*)\\s*$").find(line.trim())

            if (openFence != null) {
                val preceding = textBuffer.toString().trimEnd()
                if (preceding.isNotEmpty()) {
                    segments.add(MessageSegment.Text(preceding))
                    textBuffer.clear()
                }
                val lang = openFence.groupValues[1].lowercase().ifEmpty { "plaintext" }
                val codeBuffer = StringBuilder()
                i++
                while (i < lines.size) {
                    val codeLine = lines[i]
                    if (codeLine.trim() == "```") { i++; break }
                    codeBuffer.appendLine(codeLine)
                    i++
                }
                segments.add(MessageSegment.Code(lang, codeBuffer.toString().trimEnd()))
            } else {
                textBuffer.appendLine(line)
                i++
            }
        }

        val remaining = textBuffer.toString().trim()
        if (remaining.isNotEmpty()) segments.add(MessageSegment.Text(remaining))
        return segments
    }

    // ── View factories ────────────────────────────────────────────────────────

    private fun makeTextView(context: Context, text: String): TextView =
        TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setTextColor(0xFFEEEEEE.toInt())
            textSize = 15f
            setLineSpacing(4f, 1f)
            setTextIsSelectable(true)
            this.text = text
        }

    private fun makeMarkdownView(context: Context, markdown: String): TextView =
        TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setTextColor(0xFFEEEEEE.toInt())
            textSize = 15f
            setLineSpacing(4f, 1f)
            movementMethod = LinkMovementMethod.getInstance()
            setTextIsSelectable(true)
            markwon.setMarkdown(this, markdown)
        }

    private fun makeCodeBlockView(context: Context, segment: MessageSegment.Code): View {
        val view = LayoutInflater.from(context)
            .inflate(R.layout.item_code_block, null, false)

        val tvLanguage: TextView        = view.findViewById(R.id.tvLanguage)
        val tvCode: TextView            = view.findViewById(R.id.tvCode)
        val btnCopyCode: MaterialButton = view.findViewById(R.id.btnCopyCode)
        val vLangDot: View              = view.findViewById(R.id.vLangDot)

        tvLanguage.text = segment.language
        tvCode.text     = segment.content

        val dotColor = langColors[segment.language] ?: 0xFF888888.toInt()
        vLangDot.background?.mutate()?.setTint(dotColor)

        btnCopyCode.setOnClickListener {
            copyToClipboard(context, segment.content, "Code copied")
            animateCopied(btnCopyCode, "Copied!")
        }

        view.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        return view
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private fun copyToClipboard(context: Context, text: String, toastMsg: String) {
        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("chat", text))
        Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
    }

    private fun animateCopied(button: MaterialButton, label: String) {
        val original = button.text.toString()
        button.text = label
        button.postDelayed({ button.text = original }, 1500)
    }

    // ── Data update ───────────────────────────────────────────────────────────

    @SuppressLint("NotifyDataSetChanged")
    fun updateData(newMessages: List<ChatMessage>) {
        messages = newMessages.toList()
        notifyDataSetChanged()
    }
}