package com.example.motionspeak

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import java.util.Locale

/**
 * Two-Way FSL Communication Mode:
 * Translates hearing speech/text into FSL demonstration video playback,
 * and enables quick spoken responses from the Deaf user via offline TTS.
 */
class TwoWayConversationActivity : BaseActivity() {

    private lateinit var btnBack: MaterialButton
    private lateinit var etHearingInput: EditText
    private lateinit var btnMicInput: MaterialButton
    private lateinit var btnTranslateToFsl: MaterialButton
    private lateinit var videoFslDemo: VideoView
    private lateinit var tvDemoPlaceholder: TextView
    private lateinit var tvCurrentSignLabel: TextView
    private lateinit var btnLaunchCameraTranslator: MaterialButton
    private lateinit var btnQuickYes: MaterialButton
    private lateinit var btnQuickNo: MaterialButton

    private lateinit var speechPlayer: SpeechPlayer
    private var wordBank: List<WordBankWord> = emptyList()

    private val speechRecognizerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val first = spoken?.firstOrNull()
            if (!first.isNullOrBlank()) {
                etHearingInput.setText(first)
                translateTextToFsl(first)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_two_way_conversation)

        speechPlayer = SpeechPlayer(this, AppSettings.getInstance(this), lifecycleScope)
        wordBank = defaultWordBank()

        btnBack = findViewById(R.id.btnBack)
        etHearingInput = findViewById(R.id.etHearingInput)
        btnMicInput = findViewById(R.id.btnMicInput)
        btnTranslateToFsl = findViewById(R.id.btnTranslateToFsl)
        videoFslDemo = findViewById(R.id.videoFslDemo)
        tvDemoPlaceholder = findViewById(R.id.tvDemoPlaceholder)
        tvCurrentSignLabel = findViewById(R.id.tvCurrentSignLabel)
        btnLaunchCameraTranslator = findViewById(R.id.btnLaunchCameraTranslator)
        btnQuickYes = findViewById(R.id.btnQuickYes)
        btnQuickNo = findViewById(R.id.btnQuickNo)

        btnBack.setOnClickListener { finish() }

        btnMicInput.setOnClickListener {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak message for Deaf person...")
            }
            try {
                speechRecognizerLauncher.launch(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Voice recognition unavailable", Toast.LENGTH_SHORT).show()
            }
        }

        btnTranslateToFsl.setOnClickListener {
            val text = etHearingInput.text.toString().trim()
            if (text.isNotEmpty()) {
                translateTextToFsl(text)
            }
        }

        btnLaunchCameraTranslator.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        btnQuickYes.setOnClickListener {
            speechPlayer.speak("Opo, naiintindihan ko.")
            Toast.makeText(this, "Spoke: 'Opo, naiintindihan ko.'", Toast.LENGTH_SHORT).show()
        }

        btnQuickNo.setOnClickListener {
            speechPlayer.speak("Hindi po.")
            Toast.makeText(this, "Spoke: 'Hindi po.'", Toast.LENGTH_SHORT).show()
        }
    }

    private fun translateTextToFsl(input: String) {
        val query = input.lowercase().trim()
        val match = wordBank.firstOrNull { word ->
            word.label.lowercase() in query ||
            (word.filipino_translation != null && word.filipino_translation.lowercase() in query)
        }

        if (match != null && !match.video_url.isNullOrBlank()) {
            tvDemoPlaceholder.visibility = View.GONE
            tvCurrentSignLabel.visibility = View.VISIBLE
            tvCurrentSignLabel.text = "FSL Sign: ${match.label} (${match.filipino_translation ?: ""})"

            videoFslDemo.visibility = View.VISIBLE
            videoFslDemo.setVideoURI(Uri.parse(match.video_url))
            videoFslDemo.setOnPreparedListener { mp ->
                mp.isLooping = true
                videoFslDemo.start()
            }
        } else {
            tvDemoPlaceholder.visibility = View.VISIBLE
            tvDemoPlaceholder.text = "Sign for \"$input\" not found in offline dictionary. Try words like 'Water', 'Help', 'Fever'."
            tvCurrentSignLabel.visibility = View.GONE
            videoFslDemo.visibility = View.GONE
        }
    }

    override fun onDestroy() {
        speechPlayer.release()
        super.onDestroy()
    }
}
