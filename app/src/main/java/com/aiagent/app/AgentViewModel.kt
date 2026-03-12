package com.aiagent.app

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ChatMessage(
    val id: Long = System.currentTimeMillis(),
    val role: String, // "user", "assistant", "system"
    val content: String,
    val isToolCall: Boolean = false
)

class AgentViewModel : ViewModel() {

    private val _messages = MutableLiveData<MutableList<ChatMessage>>(mutableListOf())
    val messages: LiveData<MutableList<ChatMessage>> = _messages

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _toolCallsText = MutableLiveData("")
    val toolCallsText: LiveData<String> = _toolCallsText

    private val _errorText = MutableLiveData("")
    val errorText: LiveData<String> = _errorText

    private val apiKey = BuildConfig.ANTHROPIC_API_KEY

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // Conversation history for multi-turn
    private val conversationHistory = mutableListOf<JSONObject>()

    fun addWelcomeMessage() {
        val welcome = ChatMessage(
            role = "assistant",
            content = "👋 Hi! I'm your AI Agent powered by Claude.\n\nI can:\n⚡ Calculate math expressions\n🌡️ Convert units (temp, length, weight)\n📊 Count words & analyze text\n✨ Transform text (uppercase, reverse...)\n{} Format & validate JSON\n\nTry one of the suggestions below or ask me anything!"
        )
        addMessage(welcome)
    }

    fun sendMessage(userText: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _toolCallsText.value = ""
            _errorText.value = ""

            // Add user message to UI
            addMessage(ChatMessage(role = "user", content = userText))

            // Add to conversation history
            conversationHistory.add(JSONObject().apply {
                put("role", "user")
                put("content", userText)
            })

            try {
                runAgentLoop()
            } catch (e: Exception) {
                _errorText.value = "Error: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun runAgentLoop() {
        val toolCallsSummary = StringBuilder()
        var currentMessages = conversationHistory.toMutableList()

        // Agentic loop — up to 5 steps
        repeat(5) { step ->
            val response = callClaude(currentMessages) ?: return

            val content = response.getJSONArray("content")
            val stopReason = response.getString("stop_reason")

            // Collect tool uses
            val toolUses = mutableListOf<JSONObject>()
            var hasText = false

            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.getString("type") == "tool_use") {
                    toolUses.add(block)
                } else if (block.getString("type") == "text") {
                    hasText = true
                }
            }

            if (toolUses.isNotEmpty()) {
                // Execute tools
                val toolResults = JSONArray()
                for (toolUse in toolUses) {
                    val toolName = toolUse.getString("name")
                    val toolInput = toolUse.getJSONObject("input")
                    val toolId = toolUse.getString("id")

                    val result = executeTool(toolName, toolInput)

                    toolCallsSummary.append("🔧 $toolName → ${result.toString(2)}\n\n")
                    withContext(Dispatchers.Main) {
                        _toolCallsText.value = toolCallsSummary.toString().trim()
                    }

                    toolResults.put(JSONObject().apply {
                        put("type", "tool_result")
                        put("tool_use_id", toolId)
                        put("content", result.toString())
                    })
                }

                // Add assistant message + tool results to history
                currentMessages.add(JSONObject().apply {
                    put("role", "assistant")
                    put("content", content)
                })
                currentMessages.add(JSONObject().apply {
                    put("role", "user")
                    put("content", toolResults)
                })

            } else {
                // Final text response
                val finalText = StringBuilder()
                for (i in 0 until content.length()) {
                    val block = content.getJSONObject(i)
                    if (block.getString("type") == "text") {
                        finalText.append(block.getString("text"))
                    }
                }

                // Update conversation history
                conversationHistory.clear()
                conversationHistory.addAll(currentMessages)
                conversationHistory.add(JSONObject().apply {
                    put("role", "assistant")
                    put("content", finalText.toString())
                })

                withContext(Dispatchers.Main) {
                    addMessage(ChatMessage(role = "assistant", content = finalText.toString()))
                }
                return
            }
        }
    }

    private suspend fun callClaude(messages: List<JSONObject>): JSONObject? {
        return withContext(Dispatchers.IO) {
            val tools = buildToolsJson()
            val messagesArray = JSONArray().apply { messages.forEach { put(it) } }

            val body = JSONObject().apply {
                put("model", "claude-sonnet-4-20250514")
                put("max_tokens", 1000)
                put("system", "You are a helpful AI agent with tools. Use tools whenever they help answer accurately. Be concise and clear.")
                put("tools", tools)
                put("messages", messagesArray)
            }.toString()

            val request = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("content-type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: return@withContext null

            if (!response.isSuccessful) {
                val errObj = JSONObject(responseBody)
                throw Exception(errObj.optJSONObject("error")?.optString("message") ?: "API Error ${response.code}")
            }

            JSONObject(responseBody)
        }
    }

    private fun executeTool(name: String, input: JSONObject): JSONObject {
        return try {
            when (name) {
                "calculator" -> {
                    val expr = input.getString("expression")
                    val sanitized = expr.replace(Regex("[^0-9+\\-*/().\\s%]"), "")
                    // Simple evaluation using ScriptEngine alternative
                    val result = evalMath(sanitized)
                    JSONObject().apply {
                        put("expression", expr)
                        put("result", result)
                    }
                }
                "word_counter" -> {
                    val text = input.getString("text")
                    val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size
                    val sentences = text.split(Regex("[.!?]+")).filter { it.isNotEmpty() }.size
                    JSONObject().apply {
                        put("words", words)
                        put("characters", text.length)
                        put("characters_no_spaces", text.replace(" ", "").length)
                        put("sentences", sentences)
                    }
                }
                "unit_converter" -> {
                    val value = input.getDouble("value")
                    val from = input.getString("from_unit").lowercase()
                    val to = input.getString("to_unit").lowercase()
                    val result = convertUnit(value, from, to)
                    JSONObject().apply {
                        put("original", "$value $from")
                        put("converted", result)
                    }
                }
                "text_transformer" -> {
                    val text = input.getString("text")
                    val op = input.getString("operation")
                    val result = when (op) {
                        "uppercase" -> text.uppercase()
                        "lowercase" -> text.lowercase()
                        "reverse" -> text.reversed()
                        "titlecase" -> text.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                        "count_vowels" -> "Found ${text.count { it in "aeiouAEIOU" }} vowels"
                        else -> "Unknown operation: $op"
                    }
                    JSONObject().apply {
                        put("original", text)
                        put("result", result)
                        put("operation", op)
                    }
                }
                "json_formatter" -> {
                    val jsonStr = input.getString("json_string")
                    val parsed = JSONObject(jsonStr)
                    JSONObject().apply {
                        put("valid", true)
                        put("formatted", parsed.toString(2))
                        put("keys", parsed.keys().asSequence().toList().joinToString(", "))
                    }
                }
                else -> JSONObject().put("error", "Unknown tool: $name")
            }
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: "Tool execution failed")
        }
    }

    private fun evalMath(expr: String): Double {
        // Simple recursive math parser
        return MathParser.evaluate(expr)
    }

    private fun convertUnit(value: Double, from: String, to: String): String {
        val result = when {
            from == "c" && to == "f" -> value * 9/5 + 32
            from == "f" && to == "c" -> (value - 32) * 5/9
            from == "c" && to == "k" -> value + 273.15
            from == "k" && to == "c" -> value - 273.15
            from == "f" && to == "k" -> (value - 32) * 5/9 + 273.15
            from == "k" && to == "f" -> (value - 273.15) * 9/5 + 32
            from == "km" && to == "miles" -> value * 0.621371
            from == "miles" && to == "km" -> value / 0.621371
            from == "m" && to == "feet" -> value * 3.28084
            from == "feet" && to == "m" -> value / 3.28084
            from == "kg" && to == "lbs" -> value * 2.20462
            from == "lbs" && to == "kg" -> value / 2.20462
            from == "m" && to == "cm" -> value * 100
            from == "cm" && to == "m" -> value / 100
            else -> return "Conversion from $from to $to not supported"
        }
        return "${Math.round(result * 10000.0) / 10000.0} $to"
    }

    private fun buildToolsJson(): JSONArray {
        return JSONArray().apply {
            put(JSONObject("""{"name":"calculator","description":"Perform mathematical calculations. Use for any math.","input_schema":{"type":"object","properties":{"expression":{"type":"string","description":"Math expression to evaluate"}},"required":["expression"]}}"""))
            put(JSONObject("""{"name":"word_counter","description":"Count words, characters, and sentences in text.","input_schema":{"type":"object","properties":{"text":{"type":"string","description":"Text to analyze"}},"required":["text"]}}"""))
            put(JSONObject("""{"name":"unit_converter","description":"Convert between units: temperature (C/F/K), length (km/miles/m/feet/cm), weight (kg/lbs).","input_schema":{"type":"object","properties":{"value":{"type":"number"},"from_unit":{"type":"string"},"to_unit":{"type":"string"}},"required":["value","from_unit","to_unit"]}}"""))
            put(JSONObject("""{"name":"text_transformer","description":"Transform text: uppercase, lowercase, reverse, titlecase, count_vowels.","input_schema":{"type":"object","properties":{"text":{"type":"string"},"operation":{"type":"string","description":"uppercase, lowercase, reverse, titlecase, or count_vowels"}},"required":["text","operation"]}}"""))
            put(JSONObject("""{"name":"json_formatter","description":"Parse and format JSON strings.","input_schema":{"type":"object","properties":{"json_string":{"type":"string"}},"required":["json_string"]}}"""))
        }
    }

    private fun addMessage(message: ChatMessage) {
        val current = _messages.value ?: mutableListOf()
        current.add(message)
        _messages.value = current
    }
}

// Simple math expression evaluator (no external deps needed)
object MathParser {
    private var pos = -1
    private var ch = ' '
    private var str = ""

    @Synchronized
    fun evaluate(expression: String): Double {
        str = expression.replace(" ", "")
        pos = -1
        nextChar()
        val result = parseExpression()
        return result
    }

    private fun nextChar() {
        ch = if (++pos < str.length) str[pos] else ' '
    }

    private fun eat(charToEat: Char): Boolean {
        while (ch == ' ') nextChar()
        if (ch == charToEat) { nextChar(); return true }
        return false
    }

    private fun parseExpression(): Double {
        var x = parseTerm()
        while (true) {
            x = when {
                eat('+') -> x + parseTerm()
                eat('-') -> x - parseTerm()
                else -> return x
            }
        }
    }

    private fun parseTerm(): Double {
        var x = parseFactor()
        while (true) {
            x = when {
                eat('*') -> x * parseFactor()
                eat('/') -> x / parseFactor()
                eat('%') -> x % parseFactor()
                else -> return x
            }
        }
    }

    private fun parseFactor(): Double {
        if (eat('+')) return parseFactor()
        if (eat('-')) return -parseFactor()
        var x: Double
        val startPos = pos
        if (eat('(')) {
            x = parseExpression()
            eat(')')
        } else if (ch in '0'..'9' || ch == '.') {
            while (ch in '0'..'9' || ch == '.') nextChar()
            x = str.substring(startPos, pos).toDouble()
        } else {
            throw RuntimeException("Unexpected char: $ch")
        }
        if (eat('^')) x = Math.pow(x, parseFactor())
        return x
    }
}
