package com.sai8151.urlai

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class ConversationStorage(context: Context) {

    private val prefs = context.getSharedPreferences("chat_storage", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val KEY = "conversations"
    fun upsertConversation(conversation: Conversation) {

        val list = getConversations().toMutableList()

        val index = list.indexOfFirst { it.id == conversation.id }

        if (index != -1) {
            list[index] = conversation
        } else {
            list.add(conversation)
        }

        prefs.edit().putString(KEY, gson.toJson(list)).apply()
    }

fun getConversations(): List<Conversation> {
    val json = prefs.getString(KEY, null) ?: return emptyList()
    val type = object : TypeToken<List<Conversation>>() {}.type

    val list: List<Conversation> = gson.fromJson(json, type)
    return list.sortedByDescending { it.timestamp }
}
    fun deleteConversation(id: String) {

        val list = getConversations().toMutableList()

        val newList = list.filter { it.id != id }

        prefs.edit().putString(KEY, gson.toJson(newList)).apply()
    }

    fun renameConversation(id: String, newTitle: String) {
        val list = getConversations().map {
            if (it.id == id) it.copy(title = newTitle) else it
        }
        prefs.edit().putString(KEY, gson.toJson(list)).apply()
    }
}