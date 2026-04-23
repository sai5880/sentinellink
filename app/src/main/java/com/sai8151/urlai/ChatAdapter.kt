package com.sai8151.urlai

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import io.noties.markwon.Markwon
import io.noties.markwon.syntax.Prism4jThemeDefault
import io.noties.markwon.syntax.SyntaxHighlightPlugin
import io.noties.prism4j.GrammarLocator
import io.noties.prism4j.Prism4j
class ChatAdapter(private var messages: List<ChatMessage>) :
    RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    inner class ChatViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val llContainer: LinearLayout = view.findViewById(R.id.llContainer)
        val tvRole: TextView = view.findViewById(R.id.tvRole)
        val tvContent: TextView = view.findViewById(R.id.tvContent)
        val cardMessage: MaterialCardView = view.findViewById(R.id.cardMessage)
    }
    private lateinit var markwon: Markwon
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)

        if (!::markwon.isInitialized) {

            val prism4j = Prism4j(EmptyGrammarLocator())
            val theme = Prism4jThemeDefault.create()

            markwon = Markwon.builder(parent.context)
                .usePlugin(SyntaxHighlightPlugin.create(prism4j, theme))
                .build()
        }
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val msg = messages.getOrNull(position) ?: return

        val content = msg.content ?: ""

        markwon.setMarkdown(holder.tvContent, content)

        if (msg.role == "user") {

            holder.tvRole.text = "User"

            // LEFT SIDE
            holder.llContainer.gravity = Gravity.END
            holder.cardMessage.layoutParams = (holder.cardMessage.layoutParams as LinearLayout.LayoutParams).apply {
                gravity = Gravity.START
            }

            holder.cardMessage.setCardBackgroundColor(0xFF2B2B2B.toInt())

        } else {

            holder.tvRole.text = "AI"

            // RIGHT SIDE
            holder.llContainer.gravity = Gravity.START
            holder.cardMessage.layoutParams = (holder.cardMessage.layoutParams as LinearLayout.LayoutParams).apply {
                gravity = Gravity.END
            }

            holder.cardMessage.setCardBackgroundColor(0xFF1E1E1E.toInt())
        }
    }

    override fun getItemCount(): Int = messages.size

    @SuppressLint("NotifyDataSetChanged")
    fun updateData(newMessages: List<ChatMessage>) {
        messages = newMessages.toList()
        notifyDataSetChanged()
    }
}
class EmptyGrammarLocator : io.noties.prism4j.GrammarLocator {
    override fun grammar(prism4j: Prism4j, language: String): Prism4j.Grammar? {
        return null // no syntax rules
    }

    override fun languages(): MutableSet<String> {
        return mutableSetOf()
    }
}