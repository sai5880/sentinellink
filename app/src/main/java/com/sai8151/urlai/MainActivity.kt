package com.sai8151.urlai

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.sai8151.urlai.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var adapter: ChatAdapter
    private lateinit var convAdapter: ConversationAdapter
    private lateinit var storage: ConversationStorage
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        storage = ConversationStorage(this)

        convAdapter = ConversationAdapter(
            storage.getConversations(),
            onClick = { conversation ->

                // 🔥 SAVE CURRENT CHAT BEFORE SWITCH
                viewModel.saveCurrentConversation()

                // 🔥 LOAD NEW CHAT
                viewModel.loadConversation(conversation)

                binding.drawerLayout.closeDrawers()
                refreshSidebar()
            },
            onRename = { conversation ->
                showRenameDialog(conversation)
                refreshSidebar()
            },
            onDelete = { conversation ->
                storage.deleteConversation(conversation.id)
                refreshSidebar()
            }
        )
        binding.btnMic.setOnClickListener {
            Toast.makeText(
                this,
                "Voice feature is under development",
                Toast.LENGTH_SHORT
            ).show()
        }
        viewModel.isPolling.observe(this) { isPolling ->
            if (isPolling) {
                binding.btnPlus.setImageResource(R.drawable.ic_stop)
                binding.btnPlus.imageTintList =
                    android.content.res.ColorStateList.valueOf(0xFFF44336.toInt())
            } else {
                binding.btnPlus.setImageResource(R.drawable.ic_add)
                binding.btnPlus.imageTintList =
                    android.content.res.ColorStateList.valueOf(0xFF4CAF50.toInt())
            }
            binding.etMessage.isEnabled = !isPolling
            binding.btnSend.isEnabled = !isPolling
            binding.etMessage.alpha = if (isPolling) 0.5f else 1f
            binding.btnSend.alpha = if (isPolling) 0.5f else 1f
            if (isPolling) {
                binding.etMessage.clearFocus()
            }
        }
        binding.rvConversations.layoutManager = LinearLayoutManager(this)
        binding.rvConversations.adapter = convAdapter
        setSupportActionBar(binding.topBar)
        binding.topBar.setNavigationOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
            refreshSidebar()
        }
        val prefs = PreferencesManager(this)

        // Recycler
        adapter = ChatAdapter(emptyList())
        binding.rvChat.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.rvChat.adapter = adapter

        // Observe messages
        viewModel.chatMessages.observe(this) { msgs ->

            adapter.updateData(msgs)

            if (msgs.isNotEmpty()) {
                binding.rvChat.scrollToPosition(msgs.size - 1)
            } else {
                binding.rvChat.scrollToPosition(0)
            }
        }

        // ➕ Polling button (unchanged)
        binding.btnPlus.setOnClickListener {
            lifecycleScope.launch {
                val savedUrl = prefs.targetUrl.first()

                if (savedUrl.isNullOrBlank()) {
                    Toast.makeText(
                        this@MainActivity,
                        "Configure URL in Settings first",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }

                if (viewModel.isPolling.value == true) {
                    viewModel.stopPolling()
                    Toast.makeText(this@MainActivity, "Polling stopped", Toast.LENGTH_SHORT).show()
                } else {
                    viewModel.startPolling()
                    Toast.makeText(this@MainActivity, "Polling started", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnSend.setOnClickListener {

            val text = binding.etMessage.text
                ?.toString()
                ?.trim() ?: ""

            if (text.isEmpty()) {
                return@setOnClickListener
            }

            lifecycleScope.launch {
                val selectedModel = prefs.selectedModel.first()

                // No model selected
                if (selectedModel.isBlank()) {
                    Toast.makeText(
                        this@MainActivity,
                        "Please select a model in Settings",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }

                val isLocal =
                    com.sai8151.urlai.ai.LocalModelRegistry
                        .isLocalModel(selectedModel)

                // Local model but not downloaded
                if (isLocal) {
                    val downloaded =
                        com.sai8151.urlai.ai.ModelManager
                            .isModelDownloaded(this@MainActivity)

                    if (!downloaded) {
                        Toast.makeText(
                            this@MainActivity,
                            "Please download the model first",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@launch
                    }
                }

                binding.etMessage.setText("")
                viewModel.sendMessage(text)
            }
        }

        binding.etMessage.setOnEditorActionListener { _, _, _ ->
            binding.btnSend.performClick()
            true
        }
    }
    override fun onResume() {
        super.onResume()

        updateSendButtonState(
            PreferencesManager(this)
        )
    }
    private fun updateSendButtonState(
        prefs: PreferencesManager
    ) {
        lifecycleScope.launch {
            try {
                val selectedModel = prefs.selectedModel.first()

                var shouldLookDisabled = false

                // No model selected
                if (selectedModel.isBlank()) {
                    shouldLookDisabled = true
                } else {
                    val isLocal =
                        com.sai8151.urlai.ai.LocalModelRegistry
                            .isLocalModel(selectedModel)

                    if (isLocal) {
                        val downloaded =
                            com.sai8151.urlai.ai.ModelManager
                                .isModelDownloaded(this@MainActivity)

                        if (!downloaded) {
                            shouldLookDisabled = true
                        }
                    }
                }

                // Keep clickable for toast handling
                binding.btnSend.isEnabled = true

                binding.btnSend.alpha =
                    if (shouldLookDisabled) 0.5f else 1f

            } catch (e: Exception) {
                e.printStackTrace()

                binding.btnSend.isEnabled = true
                binding.btnSend.alpha = 0.5f
            }
        }
    }
    // MENU
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {

            R.id.menu_new_chat -> {

                lifecycleScope.launch {
                    viewModel.saveCurrentConversation()
                    refreshSidebar()
                    viewModel.clearChat()
                    Toast.makeText(this@MainActivity, "New chat started", Toast.LENGTH_SHORT).show()
                }
                true
            }

            R.id.menu_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }
    private fun refreshSidebar() {
        convAdapter.update(storage.getConversations())
    }

    private fun showRenameDialog(conv: Conversation) {
        val input = android.widget.EditText(this)
        input.setText(conv.title)

        android.app.AlertDialog.Builder(this)
            .setTitle("Rename")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                storage.renameConversation(conv.id, input.text.toString())
                refreshSidebar()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}