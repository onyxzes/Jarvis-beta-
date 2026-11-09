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
    private lateinit var pantallaActivar: View
    private lateinit var pantallaBoveda: View
    private lateinit var pantallaControl: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("jarvis", MODE_PRIVATE)
        tts = TextToSpeech(this, this)

        estado = findViewById(R.id.estado)
        btnToggle = findViewById(R.id.btnToggle)
        pantallaActivar = findViewById(R.id.pantallaActivar)
        pantallaBoveda = findViewById(R.id.pantallaBoveda)
        pantallaControl = findViewById(R.id.pantallaControl)

        // Navegación entre pantallas
        findViewById<Button>(R.id.navActivar).setOnClickListener { mostrar(0) }
        findViewById<Button>(R.id.navBoveda).setOnClickListener { mostrar(1) }
        findViewById<Button>(R.id.navControl).setOnClickListener { mostrar(2) }

        // Botón grande Activar / Desactivar
        btnToggle.setOnClickListener {
            val activo = !prefs.getBoolean("activo", false)
            prefs.edit().putBoolean("activo", activo).apply()
            pintarEstado(activo)
            if (activo) {
                hablar("Jarvis activado. Estoy a tus órdenes.")
            } else {
                hablar("Apagándome. Nos vemos.")
            }
        }

        // Probar voz de hombre
        findViewById<Button>(R.id.btnProbarVoz).setOnClickListener {
            hablar("Hola, soy Jarvis. Esta es mi voz.")
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
        if (ttsListo) {
            tts.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("es", "ES")
            elegirVozDeHombre()
            ttsListo = true
        }
    }

    // Busca una voz masculina en español entre las instaladas
    private fun elegirVozDeHombre() {
        try {
            val voces = tts.voices ?: return
            var elegida: Voice? = null
            for (v in voces) {
                val lang = v.locale.language
                if (lang != "es") continue
                val nombre = v.name.lowercase()
                if (nombre.contains("male") || nombre.contains("-m-") || nombre.endsWith("m")) {
                    elegida = v
                    break
                }
                if (elegida == null) elegida = v
            }
            if (elegida != null) tts.voice = elegida
        } catch (e: Exception) {
            // si falla, queda la voz por defecto
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
