package com.agent42.voice

import android.content.Context
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

class VoiceIO(private val context: Context) {
    private val _transcribedText = MutableStateFlow("")
    val transcribedText: StateFlow<String> = _transcribedText
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening
    private var speechRecognizer: SpeechRecognizer? = null

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    fun startListening() {
        if (speechRecognizer == null)
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() { _isListening.value = true }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { _isListening.value = false }
            override fun onError(error: Int) { _isListening.value = false }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                _transcribedText.value = matches?.firstOrNull() ?: ""
                _isListening.value = false
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                _transcribedText.value = partial?.firstOrNull() ?: ""
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speechRecognizer?.startListening(intent)
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        _isListening.value = false
    }

    fun initTTS(onReady: () -> Unit) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { _isSpeaking.value = true }
                    override fun onDone(utteranceId: String?) { _isSpeaking.value = false }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { _isSpeaking.value = false }
                })
                ttsReady = true
                onReady()
            }
        }
    }

    fun speak(text: String) {
        if (!ttsReady) return
        val cleanText = text
            .replace(Regex("[*_#`>]"), "")
            .replace(Regex("\\[.*?\\]\\(.*?\\)"), "")
            .trim()
        tts?.speak(cleanText, TextToSpeech.QUEUE_ADD, null, "agent_${System.currentTimeMillis()}")
    }

    fun stopSpeaking() { tts?.stop(); _isSpeaking.value = false }
    fun destroy() {
        speechRecognizer?.destroy()
        tts?.stop(); tts?.shutdown()
    }
}
