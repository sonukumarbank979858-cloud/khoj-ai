package com.sonu.khoj

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private lateinit var input: EditText
    private lateinit var askBtn: Button
    private lateinit var progress: ProgressBar
    private lateinit var answerView: TextView
    private lateinit var sourcesView: TextView

    private val prefs by lazy { getSharedPreferences("khoj", Context.MODE_PRIVATE) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(16))
            setBackgroundColor(Color.parseColor("#F7F7F5"))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "Khoj AI"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#13343B"))
        }
        val settingsBtn = Button(this).apply {
            text = "सेटिंग्स"
            setOnClickListener { showSettings() }
        }
        header.addView(title, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        header.addView(settingsBtn)
        root.addView(header)

        input = EditText(this).apply {
            hint = "कुछ भी पूछिए…"
            textSize = 17f
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setSingleLine(false)
            maxLines = 4
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) { ask(); true } else false
            }
        }
        root.addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })

        askBtn = Button(this).apply {
            text = "पूछें"
            setOnClickListener { ask() }
        }
        root.addView(askBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        progress = ProgressBar(this).apply { visibility = ProgressBar.GONE }
        root.addView(progress, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        answerView = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.parseColor("#222222"))
            setLineSpacing(0f, 1.25f)
            setTextIsSelectable(true)
        }
        sourcesView = TextView(this).apply {
            textSize = 14f
            movementMethod = LinkMovementMethod.getInstance()
            setPadding(0, dp(16), 0, 0)
        }
        content.addView(answerView)
        content.addView(sourcesView)
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(12) })

        setContentView(root)

        if (prefs.getString("gemini", "").isNullOrBlank() || prefs.getString("tavily", "").isNullOrBlank()) {
            answerView.text = "शुरू करने के लिए 'सेटिंग्स' में अपनी Gemini और Tavily API key डालिए।\n\nGemini key: aistudio.google.com\nTavily key: app.tavily.com"
        }
    }

    private fun showSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val gem = EditText(this).apply { hint = "Gemini API key"; setText(prefs.getString("gemini", "")) }
        val tav = EditText(this).apply { hint = "Tavily API key"; setText(prefs.getString("tavily", "")) }
        val model = EditText(this).apply { hint = "Gemini model"; setText(prefs.getString("model", DEFAULT_MODEL)) }
        box.addView(gem); box.addView(tav); box.addView(model)
        AlertDialog.Builder(this)
            .setTitle("API keys")
            .setView(box)
            .setPositiveButton("सेव करें") { _, _ ->
                prefs.edit()
                    .putString("gemini", gem.text.toString().trim())
                    .putString("tavily", tav.text.toString().trim())
                    .putString("model", model.text.toString().trim().ifBlank { DEFAULT_MODEL })
                    .apply()
                answerView.text = "Keys सेव हो गईं। अब सवाल पूछिए।"
            }
            .setNegativeButton("रद्द करें", null)
            .show()
    }

    private fun ask() {
        val q = input.text.toString().trim()
        if (q.isEmpty()) return
        val gemKey = prefs.getString("gemini", "") ?: ""
        val tavKey = prefs.getString("tavily", "") ?: ""
        val model = prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        if (gemKey.isBlank() || tavKey.isBlank()) { showSettings(); return }

        askBtn.isEnabled = false
        progress.visibility = ProgressBar.VISIBLE
        answerView.text = "खोज रहा हूँ…"
        sourcesView.text = ""

        Thread {
            try {
                val results = tavilySearch(tavKey, q)
                runOnUiThread { answerView.text = "जवाब लिख रहा हूँ…" }
                val answer = geminiAnswer(gemKey, model, q, results)
                runOnUiThread { showResult(answer, results) }
            } catch (e: Exception) {
                runOnUiThread {
                    answerView.text = "गड़बड़ हो गई: ${e.message}"
                    done()
                }
            }
        }.start()
    }

    private fun done() {
        askBtn.isEnabled = true
        progress.visibility = ProgressBar.GONE
    }

    private fun showResult(answer: String, results: List<Source>) {
        answerView.text = answer
        val sb = StringBuilder("<b>सोर्स</b><br>")
        results.forEachIndexed { i, s ->
            sb.append("[${i + 1}] <a href=\"").append(Html.escapeHtml(s.url)).append("\">")
                .append(Html.escapeHtml(s.title)).append("</a><br>")
        }
        @Suppress("DEPRECATION")
        sourcesView.text = Html.fromHtml(sb.toString())
        done()
    }

    data class Source(val title: String, val url: String, val content: String)

    private fun post(url: String, body: String, headers: Map<String, String>): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 20000
        conn.readTimeout = 60000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        if (code !in 200..299) throw RuntimeException("HTTP $code: ${text.take(300)}")
        return text
    }

    private fun tavilySearch(key: String, q: String): List<Source> {
        val body = JSONObject()
            .put("query", q)
            .put("max_results", 6)
            .put("search_depth", "basic")
            .toString()
        val json = JSONObject(post("https://api.tavily.com/search", body, mapOf("Authorization" to "Bearer $key")))
        val arr = json.optJSONArray("results") ?: JSONArray()
        val out = mutableListOf<Source>()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            out.add(Source(r.optString("title"), r.optString("url"), r.optString("content")))
        }
        return out
    }

    private fun geminiAnswer(key: String, model: String, q: String, results: List<Source>): String {
        val ctx = StringBuilder()
        results.forEachIndexed { i, s ->
            ctx.append("[${i + 1}] ${s.title}\n${s.url}\n${s.content}\n\n")
        }
        val prompt = """
            You are a helpful search assistant. Answer the user's question using ONLY the web results below.
            Cite sources inline like [1], [2]. Be clear and concise.
            Reply in the same language the user asked in (Hindi or Hinglish if they used it).

            Web results:
            $ctx
            Question: $q
        """.trimIndent()
        val body = JSONObject().put(
            "contents", JSONArray().put(
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            )
        ).toString()
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        val json = JSONObject(post(url, body, mapOf("x-goog-api-key" to key)))
        val parts = json.getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
        return sb.toString().replace("**", "")
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
    }
}
