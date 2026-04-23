package com.sai8151.urlai

data class Conversation(
    val id: String,
    val title: String,
    val messages: List<ChatMessage>,
    val timestamp: Long
)