package com.sai8151.urlai

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.sai8151.urlai.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private lateinit var adapter: ChatAdapter
    private lateinit var convAdapter: ConversationAdapter
    private lateinit var storage: ConversationStorage
    private lateinit var pdfToWordLauncher: ActivityResultLauncher<Array<String>>

    private lateinit var pdfPickerLauncher: ActivityResultLauncher<Array<String>>
    private var isSending = false
    private var isActionPopupVisible = false

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        storage = ConversationStorage(this)
        val prefs = PreferencesManager(this)

        // ------------------------------------------------
        // Sidebar conversations
        // ------------------------------------------------

        convAdapter = ConversationAdapter(
            storage.getConversations(),

            onClick = { conversation ->
                viewModel.saveCurrentConversation()
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
        pdfPickerLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->

            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )

                Toast.makeText(
                    this,
                    "PDF Selected: $uri",
                    Toast.LENGTH_SHORT
                ).show()

                lifecycleScope.launch {
                    viewModel.handlePdfImport(uri)
                }
            }
        }
        binding.btnImportPdf.setOnClickListener {
            pdfPickerLauncher.launch(arrayOf("application/pdf"))
            closeActionPopup()
        }
        binding.btnconvertpdftoword.setOnClickListener {
            pdfToWordLauncher.launch(arrayOf("application/pdf"))
            closeActionPopup()
        }
        pdfToWordLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->

            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )

                Toast.makeText(
                    this,
                    "PDF Selected: $uri",
                    Toast.LENGTH_SHORT
                ).show()

                lifecycleScope.launch {
                    viewModel.handlePdfToWordImport(uri)
                }
            }
        }
        binding.rvConversations.layoutManager =
            LinearLayoutManager(this)

        binding.rvConversations.adapter =
            convAdapter

        // ------------------------------------------------
        // Toolbar
        // ------------------------------------------------

        setSupportActionBar(binding.topBar)

        binding.topBar.setNavigationOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
            refreshSidebar()
        }

        // ------------------------------------------------
        // Voice placeholder
        // ------------------------------------------------

        binding.btnMic.setOnClickListener {
            Toast.makeText(
                this,
                "Voice feature is under development",
                Toast.LENGTH_SHORT
            ).show()
        }

        // ------------------------------------------------
        // Polling UI state
        // ------------------------------------------------

        viewModel.isPolling.observe(this) { isPolling ->

            binding.etMessage.isEnabled = !isPolling
            binding.btnSend.isEnabled = !isPolling

            binding.etMessage.alpha =
                if (isPolling) 0.5f else 1f

            binding.btnSend.alpha =
                if (isPolling) 0.5f else 1f

            if (isPolling) {
                binding.etMessage.clearFocus()

                binding.tvPollingStatus.text =
                    "Clipboard Sync (Active)"

                binding.btnPolling.setBackgroundResource(
                    R.drawable.bg_polling_active
                )

            } else {

                binding.tvPollingStatus.text =
                    "Clipboard Sync (Inactive)"

                binding.btnPolling.setBackgroundResource(
                    R.drawable.bg_polling_inactive
                )
            }
        }
        // ------------------------------------------------
        // Chat recycler
        // ------------------------------------------------

        adapter = ChatAdapter(emptyList())

        binding.rvChat.layoutManager =
            LinearLayoutManager(this).apply {
                stackFromEnd = true
            }

        binding.rvChat.adapter = adapter

        viewModel.chatMessages.observe(this) { msgs ->

            adapter.updateData(msgs)

            if (msgs.isNotEmpty()) {
                binding.rvChat.scrollToPosition(
                    msgs.size - 1
                )
            } else {
                binding.rvChat.scrollToPosition(0)
            }
        }

        // ------------------------------------------------
        // + button popup toggle
        // ------------------------------------------------

        binding.btnPlus.setOnClickListener {

            isActionPopupVisible = !isActionPopupVisible

            if (isActionPopupVisible) {
                binding.actionPopup.visibility =
                    View.VISIBLE

                binding.btnPlus.animate()
                    .rotation(120f)
                    .setDuration(200)
                    .start()

            } else {
                closeActionPopup()
            }
        }

        // ------------------------------------------------
        // Computer action (Polling)
        // ------------------------------------------------

        binding.btnPolling.setOnClickListener {
            lifecycleScope.launch {

                val savedUrl =
                    prefs.targetUrl.first()

                if (savedUrl.isNullOrBlank()) {
                    Toast.makeText(
                        this@MainActivity,
                        "Configure URL in Settings first",
                        Toast.LENGTH_SHORT
                    ).show()

                    closeActionPopup()
                    return@launch
                }

                if (viewModel.isPolling.value == true) {

                    viewModel.stopPolling()

                    Toast.makeText(
                        this@MainActivity,
                        "Polling stopped",
                        Toast.LENGTH_SHORT
                    ).show()

                } else {

                    viewModel.startPolling()

                    Toast.makeText(
                        this@MainActivity,
                        "Polling started",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                closeActionPopup()
            }
        }

        // ------------------------------------------------
        // Import PDF placeholder
        // ------------------------------------------------

//        binding.btnImportPdf.setOnClickListener {
//            Toast.makeText(
//                this,
//                "PDF import feature coming soon",
//                Toast.LENGTH_SHORT
//            ).show()
//
//            closeActionPopup()
//        }

        // ------------------------------------------------
        // Send button
        // ------------------------------------------------

        binding.btnSend.setOnClickListener {
            handleSendMessage(prefs)
        }
    }

    // ====================================================
    // Popup close helper
    // ====================================================

    private fun closeActionPopup() {
        binding.actionPopup.visibility = View.GONE
        isActionPopupVisible = false

        binding.btnPlus.animate()
            .rotation(0f)
            .setDuration(200)
            .start()
    }

    // ====================================================
    // Resume
    // ====================================================

    override fun onResume() {
        super.onResume()

        updateSendButtonState(
            PreferencesManager(this)
        )
    }

    // ====================================================
    // Send button validation
    // ====================================================

    private fun updateSendButtonState(
        prefs: PreferencesManager
    ) {
        lifecycleScope.launch {
            try {
                val selectedModel =
                    prefs.selectedModel.first()

                var shouldLookDisabled = false

                if (selectedModel.isBlank()) {
                    shouldLookDisabled = true
                } else {
                    val isLocal =
                        com.sai8151.urlai.ai.LocalModelRegistry
                            .isLocalModel(selectedModel)

                    if (isLocal) {
                        val downloaded =
                            com.sai8151.urlai.ai.ModelManager
                                .isModelDownloaded(
                                    this@MainActivity
                                )

                        if (!downloaded) {
                            shouldLookDisabled = true
                        }
                    }
                }

                binding.btnSend.isEnabled = true

                binding.btnSend.alpha =
                    if (shouldLookDisabled) 0.5f
                    else 1f

            } catch (e: Exception) {
                e.printStackTrace()

                binding.btnSend.isEnabled = true
                binding.btnSend.alpha = 0.5f
            }
        }
    }

    // ====================================================
    // Send message logic
    // ====================================================

    private fun handleSendMessage(
        prefs: PreferencesManager
    ) {
        if (isSending) return

        val text = binding.etMessage.text
            ?.toString()
            ?.trim() ?: ""

        if (text.isEmpty()) return

        lifecycleScope.launch {
            try {
                val selectedModel =
                    prefs.selectedModel.first()

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

                if (isLocal) {
                    val downloaded =
                        com.sai8151.urlai.ai.ModelManager
                            .isModelDownloaded(
                                this@MainActivity
                            )

                    if (!downloaded) {
                        Toast.makeText(
                            this@MainActivity,
                            "Please download the model first",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@launch
                    }
                }

                isSending = true

                binding.etMessage.setText("")
                viewModel.sendMessage(text)

            } finally {
                isSending = false
            }
        }
    }

    // ====================================================
    // Menu
    // ====================================================

    override fun onCreateOptionsMenu(
        menu: Menu
    ): Boolean {
        menuInflater.inflate(
            R.menu.main_menu,
            menu
        )
        return true
    }

    override fun onOptionsItemSelected(
        item: MenuItem
    ): Boolean {
        return when (item.itemId) {

            R.id.menu_new_chat -> {
                lifecycleScope.launch {
                    viewModel.saveCurrentConversation()
                    refreshSidebar()
                    viewModel.clearChat()

                    Toast.makeText(
                        this@MainActivity,
                        "New chat started",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                true
            }

            R.id.menu_settings -> {
                startActivity(
                    Intent(
                        this,
                        SettingsActivity::class.java
                    )
                )
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    // ====================================================
    // Sidebar helpers
    // ====================================================

    private fun refreshSidebar() {
        convAdapter.update(
            storage.getConversations()
        )
    }

    private fun showRenameDialog(
        conv: Conversation
    ) {
        val input =
            android.widget.EditText(this)

        input.setText(conv.title)

        android.app.AlertDialog.Builder(this)
            .setTitle("Rename")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                storage.renameConversation(
                    conv.id,
                    input.text.toString()
                )
                refreshSidebar()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}