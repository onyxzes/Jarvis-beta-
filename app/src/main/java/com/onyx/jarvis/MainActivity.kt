package com.onyx.jarvis

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private var ttsListo = false

    private lateinit var prefs: android.content.SharedPreferences

    private lateinit var estado: TextView
    private lateinit var btnToggle: Button
    private lateinit var vozActual: TextView
    private lateinit var pantallaActivar: View
    private lateinit var pantallaBoveda: View
    private lateinit var pantallaControl: View

    // Lista de voces disponibles para elegir
    private var voces: List<Voice> = emptyList()
    private var indiceVoz = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("jarvis", MODE_PRIVATE)
        tts = TextToSpeech(this, this)

        estado = findViewById(R.id.estado)
        btnToggle = findViewById(R.id.btnToggle)
        vozActual = findViewById(R.id.vozActual)
        pantallaActivar = findViewById(R.id.pantallaActivar)
        pantallaBoveda = findViewById(R.id.pantallaBoveda)
        pantallaControl = findViewById(R.id.pantallaControl)

        findViewById<Button>(R.id.navActivar).setOnClickListener { mostrar(0) }
        findViewById<Button>(R.id.navBoveda).setOnClickListener { mostrar(1) }
        findViewById<Button>(R.id.navControl).setOnClickListener { mostrar(2) }

        btnToggle.setOnClickListener {
            val activo = !prefs.getBoolean("activo", false)
            prefs.edit().putBoolean("activo", activo).apply()
            pintarEstado(activo)
            if (activo) hablar("Jarvis activado. Estoy a tus órdenes.")
            else hablar("Apagándome. Nos vemos.")
        }

        findViewById<Button>(R.id.btnProbarVoz).setOnClickListener {
            hablar("Hola, soy Jarvis. Esta es mi voz.")
        }

        findViewById<Button>(R.id.btnCambiarVoz).setOnClickListener {
            cambiarVoz()
        }

        pintarEstado(prefs.getBoolean("activo", false))
        mostrar(0)
    }

    private fun mostrar(i: Int) {
        pantallaActivar.visibility = if (i == 0) View.VISIBLE else View.GONE
        pantallaBoveda.visibility = if (i == 1) View.VISIBLE else View.GONE
        pantallaControl.visibility = if (i == 2) View.VISIBLE else View.GONE
    }

    private fun pintarEstado(activo: Boolean) {
        if (activo) {
            estado.text = "Activado"
            estado.setTextColor(0xFF3FE0FF.toInt())
            btnToggle.text = "APAGAR"
        } else {
            estado.text = "Desactivado"
            estado.setTextColor(0xFF6F93A6.toInt())
            btnToggle.text = "ACTIVAR"
        }
    }

    private fun hablar(texto: String) {
        if (ttsListo) tts.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        tts.language = Locale("es", "ES")
        // Tono grave para que suene más masculino
        tts.setPitch(0.75f)
        tts.setSpeechRate(1.0f)
        armarListaDeVoces()
        cargarVozGuardada()
        ttsListo = true
    }

    // Arma la lista: primero voces en español que funcionan sin internet,
    // después el resto, así podés recorrerlas y quedarte con la que te guste.
    private fun armarListaDeVoces() {
        try {
            val todas = tts.voices?.toList() ?: emptyList()
            val es = todas.filter { it.locale.language == "es" }
            val esLocales = es.filter { !it.isNetworkConnectionRequired }
            val esRed = es.filter { it.isNetworkConnectionRequired }
            val ordenadas = (esLocales + esRed).sortedBy { it.name }
            voces = if (ordenadas.isNotEmpty()) ordenadas else todas.sortedBy { it.name }
        } catch (e: Exception) {
            voces = emptyList()
        }
    }

    private fun cargarVozGuardada() {
        val guardada = prefs.getString("voz", null)
        if (guardada != null) {
            val i = voces.indexOfFirst { it.name == guardada }
            if (i >= 0) {
                indiceVoz = i
                aplicarVoz(indiceVoz, hablar = false)
                return
            }
        }
        if (voces.isNotEmpty()) aplicarVoz(0, hablar = false)
    }

    private fun cambiarVoz() {
        if (voces.isEmpty()) {
            hablar("No encontré otras voces instaladas.")
            return
        }
        indiceVoz = (indiceVoz + 1) % voces.size
        aplicarVoz(indiceVoz, hablar = true)
    }

    private fun aplicarVoz(i: Int, hablar: Boolean) {
        if (i < 0 || i >= voces.size) return
        val v = voces[i]
        try {
            tts.voice = v
            prefs.edit().putString("voz", v.name).apply()
            vozActual.text = "Voz ${i + 1} de ${voces.size}: ${v.name}"
            if (hablar) hablar("Hola, soy Jarvis. ¿Te gusta esta voz?")
        } catch (e: Exception) {
            vozActual.text = "No pude usar esa voz, probá otra."
        }
    }

    override fun onDestroy() {
        try {
            tts.stop()
            tts.shutdown()
        } catch (e: Exception) {
        }
        super.onDestroy()
    }
}
