package com.sai8151.urlai

import android.annotation.SuppressLint
import android.util.Log
import android.view.*
import android.widget.*
import androidx.recyclerview.widget.RecyclerView

class ConversationAdapter(
    private var list: List<Conversation>,
    private val onClick: (Conversation) -> Unit,
    private val onRename: (Conversation) -> Unit,
    private val onDelete: (Conversation) -> Unit
) : RecyclerView.Adapter<ConversationAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tvTitle)
        val btnMenu: ImageButton = view.findViewById(R.id.btnMenu)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_conversation, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = list.getOrNull(position) ?: return

        holder.tvTitle.text = item.title

        holder.itemView.setOnClickListener {
            onClick(item)
        }
        Log.d("CHAT_DEBUG", "Sidebar ID: ${item.id}")
        holder.btnMenu.setOnClickListener { v ->
            val popup = PopupMenu(v.context, v)
            popup.menu.add("Rename")
            popup.menu.add("Delete")

            popup.setOnMenuItemClickListener {
                when (it.title) {
                    "Rename" -> onRename(item)
                    "Delete" -> onDelete(item)
                }
                true
            }
            popup.show()
        }
    }

    override fun getItemCount() = list.size

    @SuppressLint("NotifyDataSetChanged")
    fun update(newList: List<Conversation>) {
        list = newList
        notifyDataSetChanged()
    }
}