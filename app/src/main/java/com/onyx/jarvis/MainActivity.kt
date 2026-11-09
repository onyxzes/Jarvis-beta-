package com.onyx.jarvis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
    private lateinit var campoClave: EditText
    private lateinit var pantallaActivar: View
    private lateinit var pantallaBoveda: View
    private lateinit var pantallaControl: View

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
        campoClave = findViewById(R.id.campoClave)
        pantallaActivar = findViewById(R.id.pantallaActivar)
        pantallaBoveda = findViewById(R.id.pantallaBoveda)
        pantallaControl = findViewById(R.id.pantallaControl)

        findViewById<Button>(R.id.navActivar).setOnClickListener { mostrar(0) }
        findViewById<Button>(R.id.navBoveda).setOnClickListener { mostrar(1) }
        findViewById<Button>(R.id.navControl).setOnClickListener { mostrar(2) }

        btnToggle.setOnClickListener {
            val activar = !prefs.getBoolean("activo", false)
            if (activar) {
                if (pedirPermisos()) encender()
            } else {
                apagar()
            }
        }

        findViewById<Button>(R.id.btnProbarVoz).setOnClickListener {
            hablar("Hola, soy Jarvis. Esta es mi voz.")
        }
        findViewById<Button>(R.id.btnCambiarVoz).setOnClickListener { cambiarVoz() }

        findViewById<Button>(R.id.btnGuardarClave).setOnClickListener {
            val k = campoClave.text.toString().trim()
            prefs.edit().putString("gemini_key", k).apply()
            Toast.makeText(this, "Clave guardada", Toast.LENGTH_SHORT).show()
        }
        campoClave.setText(prefs.getString("gemini_key", ""))

        pintarEstado(prefs.getBoolean("activo", false))
        mostrar(0)
    }

    // --- Encendido / apagado del servicio que escucha ---

    private fun encender() {
        prefs.edit().putBoolean("activo", true).apply()
        pintarEstado(true)
        val i = Intent(this, JarvisService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
        else startService(i)
    }

    private fun apagar() {
        prefs.edit().putBoolean("activo", false).apply()
        pintarEstado(false)
        stopService(Intent(this, JarvisService::class.java))
        hablar("Apagándome. Nos vemos.")
    }

    private fun pedirPermisos(): Boolean {
        val faltan = ArrayList<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) faltan.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) faltan.add(Manifest.permission.POST_NOTIFICATIONS)
        if (faltan.isEmpty()) return true
        ActivityCompat.requestPermissions(this, faltan.toTypedArray(), 1)
        return false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val micOk = permissions.indexOf(Manifest.permission.RECORD_AUDIO).let {
            it >= 0 && grantResults.getOrNull(it) == PackageManager.PERMISSION_GRANTED
        }
        if (micOk) {
            encender()
        } else {
            Toast.makeText(this, "Necesito el micrófono para escucharte", Toast.LENGTH_LONG).show()
        }
    }

    // --- Pantallas ---

    private fun mostrar(i: Int) {
        pantallaActivar.visibility = if (i == 0) View.VISIBLE else View.GONE
        pantallaBoveda.visibility = if (i == 1) View.VISIBLE else View.GONE
        pantallaControl.visibility = if (i == 2) View.VISIBLE else View.GONE
    }

    private fun pintarEstado(activo: Boolean) {
        if (activo) {
            estado.text = "Activado — escuchando"
            estado.setTextColor(0xFF3FE0FF.toInt())
            btnToggle.text = "APAGAR"
        } else {
            estado.text = "Desactivado"
            estado.setTextColor(0xFF6F93A6.toInt())
            btnToggle.text = "ACTIVAR"
        }
    }

    // --- Voz (prueba local en la app) ---

    private fun hablar(texto: String) {
        if (ttsListo) tts.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        tts.language = Locale("es", "ES")
        tts.setPitch(0.75f)
        armarListaDeVoces()
        cargarVozGuardada()
        ttsListo = true
    }

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
            if (i >= 0) { indiceVoz = i; aplicarVoz(indiceVoz, false); return }
        }
        if (voces.isNotEmpty()) aplicarVoz(0, false)
    }

    private fun cambiarVoz() {
        if (voces.isEmpty()) { hablar("No encontré otras voces instaladas."); return }
        indiceVoz = (indiceVoz + 1) % voces.size
        aplicarVoz(indiceVoz, true)
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
        try { tts.stop(); tts.shutdown() } catch (e: Exception) {}
        super.onDestroy()
    }
}
