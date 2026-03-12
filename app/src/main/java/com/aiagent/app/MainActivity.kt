package com.aiagent.app

import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.aiagent.app.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: AgentViewModel
    private lateinit var chatAdapter: ChatAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[AgentViewModel::class.java]

        setupRecyclerView()
        setupInput()
        observeViewModel()

        // Show welcome message
        viewModel.addWelcomeMessage()
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter()
        binding.recyclerView.apply {
            adapter = chatAdapter
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                stackFromEnd = true
            }
        }
    }

    private fun setupInput() {
        binding.sendButton.setOnClickListener { sendMessage() }

        binding.inputField.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                sendMessage()
                true
            } else false
        }

        // Suggestion chips
        binding.chip1.setOnClickListener { setInput("What is 1234 × 5678 + 999?") }
        binding.chip2.setOnClickListener { setInput("Convert 100°C to Fahrenheit") }
        binding.chip3.setOnClickListener { setInput("Count words in: The quick brown fox jumps over the lazy dog") }
        binding.chip4.setOnClickListener { setInput("Convert 'hello world' to uppercase") }
    }

    private fun setInput(text: String) {
        binding.inputField.setText(text)
        binding.inputField.setSelection(text.length)
    }

    private fun sendMessage() {
        val text = binding.inputField.text?.toString()?.trim() ?: return
        if (text.isEmpty() || viewModel.isLoading.value == true) return

        binding.inputField.text?.clear()
        binding.suggestionGroup.visibility = android.view.View.GONE

        lifecycleScope.launch {
            viewModel.sendMessage(text)
        }
    }

    private fun observeViewModel() {
        viewModel.messages.observe(this) { messages ->
            chatAdapter.submitList(messages.toList()) {
                binding.recyclerView.scrollToPosition(messages.size - 1)
            }
        }

        viewModel.isLoading.observe(this) { loading ->
            binding.sendButton.isEnabled = !loading
            binding.loadingIndicator.visibility = if (loading) android.view.View.VISIBLE else android.view.View.GONE
            binding.inputField.hint = if (loading) "Agent thinking..." else "Ask me anything..."
        }

        viewModel.toolCallsText.observe(this) { toolText ->
            if (toolText.isNotEmpty()) {
                binding.toolStatusCard.visibility = android.view.View.VISIBLE
                binding.toolStatusText.text = toolText
            } else {
                binding.toolStatusCard.visibility = android.view.View.GONE
            }
        }

        viewModel.errorText.observe(this) { error ->
            if (error.isNotEmpty()) {
                binding.errorCard.visibility = android.view.View.VISIBLE
                binding.errorText.text = error
            } else {
                binding.errorCard.visibility = android.view.View.GONE
            }
        }
    }
}
