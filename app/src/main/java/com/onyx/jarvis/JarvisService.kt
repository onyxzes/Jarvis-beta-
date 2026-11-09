package com.onyx.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

class JarvisService : Service(), RecognitionListener, TextToSpeech.OnInitListener {

    companion object {
        const val CANAL = "jarvis_canal"
        const val MODELO_URL = "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip"
    }

    private var tts: TextToSpeech? = null
    private var speechService: SpeechService? = null
    private var esperandoComando = false
    private val fondo = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        crearCanal()
        startForeground(1, notificacion("Jarvis se está preparando..."))
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("es", "ES")
            tts?.setPitch(0.75f)
            aplicarVozGuardada()
        }
        // Preparar el modelo de voz en segundo plano y después escuchar
        fondo.execute {
            try {
                val dir = asegurarModelo()
                val model = Model(dir.absolutePath)
                val rec = Recognizer(model, 16000.0f)
                speechService = SpeechService(rec, 16000.0f)
                speechService?.startListening(this)
                actualizarNotif("Jarvis activo. Decí \"Jarvis\".")
                hablar("Jarvis activado. Decí Jarvis cuando me necesites.")
            } catch (e: Exception) {
                actualizarNotif("Error preparando la voz: " + (e.message ?: ""))
            }
        }
    }

    // Descarga y descomprime el modelo una sola vez
    private fun asegurarModelo(): File {
        val base = File(filesDir, "voskmodel")
        val marca = File(base, "conf")
        if (marca.exists()) return base
        if (base.exists()) base.deleteRecursively()
        base.mkdirs()
        actualizarNotif("Descargando voz en español (una sola vez)...")

        val tmp = File(filesDir, "modelo.zip")
        val con = URL(MODELO_URL).openConnection() as HttpURLConnection
        con.connectTimeout = 30000
        con.readTimeout = 60000
        con.inputStream.use { input ->
            FileOutputStream(tmp).use { out -> input.copyTo(out, 8192) }
        }
        con.disconnect()

        // Descomprimir, quitando la carpeta raíz del zip
        ZipInputStream(tmp.inputStream()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val sinRaiz = e.name.substringAfter('/')
                if (sinRaiz.isNotEmpty()) {
                    val destino = File(base, sinRaiz)
                    if (e.isDirectory) {
                        destino.mkdirs()
                    } else {
                        destino.parentFile?.mkdirs()
                        FileOutputStream(destino).use { out -> zis.copyTo(out, 8192) }
                    }
                }
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        tmp.delete()
        return base
    }

    override fun onResult(hypothesis: String?) {
        val texto = JSONObject(hypothesis ?: "{}").optString("text").trim().lowercase()
        if (texto.isEmpty()) return

        if (texto.contains("apagate") || texto.contains("apágate") || texto.contains("apagado")) {
            hablar("Apagándome. Nos vemos.")
            detener()
            return
        }

        if (esperandoComando) {
            esperandoComando = false
            responderConIA(texto)
            return
        }

        if (texto.contains("jarvis")) {
            val comando = texto.substringAfter("jarvis").trim()
            if (comando.isEmpty()) {
                esperandoComando = true
                hablar("¿Sí? Decime.")
            } else {
                responderConIA(comando)
            }
        }
    }

    override fun onPartialResult(hypothesis: String?) {}
    override fun onFinalResult(hypothesis: String?) {}
    override fun onError(e: Exception?) {
        actualizarNotif("Error de audio: " + (e?.message ?: ""))
    }
    override fun onTimeout() {}

    private fun responderConIA(pregunta: String) {
        actualizarNotif("Pensando: " + pregunta)
        hablar("Dame un segundo.")
        fondo.execute {
            val (ok, texto) = llamarGemini(pregunta)
            if (ok) {
                hablar(texto)
                actualizarNotif("Jarvis activo. Decí \"Jarvis\".")
            } else {
                hablar("Hubo un error con la IA. Te lo dejé en la notificación.")
                actualizarNotif("Error IA: " + texto.take(140))
            }
        }
    }

    // Prueba varios modelos; devuelve (ok, respuesta o error detallado)
    private fun llamarGemini(pregunta: String): Pair<Boolean, String> {
        val key = getSharedPreferences("jarvis", MODE_PRIVATE).getString("gemini_key", "")?.trim() ?: ""
        if (key.isBlank()) return false to "Falta la clave de Gemini (pantalla Control)."

        val prompt = "Sos Jarvis, un asistente personal. Respondé en español, " +
            "en pocas frases, claro y directo. Pregunta: " + pregunta
        val body = JSONObject().apply {
            put("contents", org.json.JSONArray().put(
                JSONObject().put("parts", org.json.JSONArray().put(
                    JSONObject().put("text", prompt)
                ))
            ))
        }.toString()

        val modelos = listOf("gemini-2.0-flash", "gemini-2.5-flash", "gemini-flash-latest", "gemini-1.5-flash")
        var ultimoError = "No pude conectarme."

        for (modelo in modelos) {
            try {
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$modelo:generateContent")
                val con = url.openConnection() as HttpURLConnection
                con.requestMethod = "POST"
                con.setRequestProperty("Content-Type", "application/json")
                con.setRequestProperty("x-goog-api-key", key)
                con.doOutput = true
                con.connectTimeout = 20000
                con.readTimeout = 30000
                con.outputStream.use { it.write(body.toByteArray()) }

                val codigo = con.responseCode
                val stream = if (codigo in 200..299) con.inputStream else con.errorStream
                val txt = stream?.bufferedReader()?.use { it.readText() } ?: ""
                con.disconnect()

                if (codigo in 200..299) {
                    val respuesta = JSONObject(txt)
                        .getJSONArray("candidates").getJSONObject(0)
                        .getJSONObject("content").getJSONArray("parts")
                        .getJSONObject(0).getString("text").trim()
                    return true to respuesta
                }

                ultimoError = "HTTP $codigo: " + txt.take(180)
                // Si es problema de clave o permiso, no sirve probar otros modelos
                if (codigo == 400 || codigo == 401 || codigo == 403) return false to ultimoError
            } catch (e: Exception) {
                ultimoError = e.message ?: "error de red"
            }
        }
        return false to ultimoError
    }

    private fun aplicarVozGuardada() {
        try {
            val nombre = getSharedPreferences("jarvis", MODE_PRIVATE).getString("voz", null) ?: return
            val v = tts?.voices?.firstOrNull { it.name == nombre } ?: return
            tts?.voice = v
        } catch (e: Exception) {
        }
    }

    private fun hablar(texto: String) {
        tts?.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
    }

    private fun detener() {
        try { speechService?.stop() } catch (e: Exception) {}
        try { speechService?.shutdown() } catch (e: Exception) {}
        getSharedPreferences("jarvis", MODE_PRIVATE).edit().putBoolean("activo", false).apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun crearCanal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(CANAL, "Jarvis", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(canal)
        }
    }

    private fun notificacion(texto: String): Notification {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CANAL) else Notification.Builder(this)
        return b.setContentTitle("Jarvis")
            .setContentText(texto)
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
    }

    private fun actualizarNotif(texto: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(1, notificacion(texto))
        } catch (e: Exception) {
        }
    }

    override fun onDestroy() {
        try { speechService?.stop() } catch (e: Exception) {}
        try { tts?.shutdown() } catch (e: Exception) {}
        fondo.shutdownNow()
        super.onDestroy()
    }
}
