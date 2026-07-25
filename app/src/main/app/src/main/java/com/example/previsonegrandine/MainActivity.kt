package com.example.previsonegrandine

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        101
                    )
                }
            }
            createNotificationChannel(this)
        } catch (e: Exception) {
            Log.e("MainActivity", "Errore notifiche: ${e.localizedMessage}")
        }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AllertaGrandineScreen(this)
                }
            }
        }
    }
}

data class Comune(val nome: String, val lat: Double, val lon: Double)

val comuniAltoAdige = listOf(
    Comune("Bolzano", 46.4983, 11.3548),
    Comune("Merano", 46.6681, 11.1595),
    Comune("Bressanone", 46.7162, 11.6568),
    Comune("Brunico", 46.7961, 11.9358),
    Comune("Silandro", 46.6281, 10.7714)
)

@Composable
fun AllertaGrandineScreen(context: Context) {
    var comuneSelezionato by remember { mutableStateOf(comuniAltoAdige[0]) }
    var statoMeteo by remember { mutableStateOf("Tocca 'Verifica Ora' per controllare") }
    var isRischioGrandine by remember { mutableStateOf(false) }
    var isChecking by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "⛈️ Allerta Grandine AA",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "Meteo ufficiale Provincia di Bolzano",
            fontSize = 14.sp,
            color = Color.Gray
        )

        Text(
            text = "Zona monitorata: ${comuneSelezionato.nome}",
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            comuniAltoAdige.take(3).forEach { comune ->
                Button(
                    onClick = { comuneSelezionato = comune },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (comuneSelezionato == comune) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text(comune.nome, fontSize = 11.sp)
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isRischioGrandine) Color(0xFFFFEBEE) else Color(0xFFE8F5E9)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Stato Allerta:",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = statoMeteo,
                    fontSize = 14.sp,
                    color = if (isRischioGrandine) Color.Red else Color(0xFF2E7D32)
                )
            }
        }

        Button(
            onClick = {
                scope.launch {
                    isChecking = true
                    statoMeteo = "Connessione al server per ${comuneSelezionato.nome}..."
                    val esito = controllaMeteoComune(comuneSelezionato)

                    if (esito == "RISCHIO") {
                        isRischioGrandine = true
                        statoMeteo = "⚠️ ATTENZIONE: Rischio grandine o temporale forte a ${comuneSelezionato.nome}!"
                    } else if (esito == "OK") {
                        isRischioGrandine = false
                        statoMeteo = "✅ Nessun rischio grandine attualmente a ${comuneSelezionato.nome}."
                    } else {
                        isRischioGrandine = false
                        statoMeteo = "⚠️ Nota: $esito"
                    }
                    isChecking = false
                }
            },
            enabled = !isChecking,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isChecking) "Verifica in corso..." else "Verifica Ora per ${comuneSelezionato.nome}")
        }

        OutlinedButton(
            onClick = {
                try {
                    avviaMonitoraggioBackground(context)
                    inviaNotifica(
                        context,
                        "Monitoraggio Background Attivo",
                        "L'app verificherà periodicamente il meteo."
                    )
                } catch (e: Exception) {
                    statoMeteo = "Errore background: ${e.localizedMessage}"
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Attiva Allerta in Background")
        }

        TextButton(
            onClick = {
                inviaNotifica(
                    context,
                    "⚠️ Test Allerta Grandine",
                    "Notifica di prova inviata con successo!"
                )
            }
        ) {
            Text("Invia Notifica di Prova")
        }
    }
}

suspend fun controllaMeteoComune(comune: Comune): String = withContext(Dispatchers.IO) {
    try {
        val url = URL("https://api.open-meteo.com/v1/forecast?latitude=${comune.lat}&longitude=${comune.lon}&current=weather_code,precipitation&forecast_days=1")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10000
        connection.readTimeout = 10000

        val stream = connection.inputStream
        val testo = stream.bufferedReader().use { it.readText() }

        val haRischio = testo.contains("\"weather_code\":96") || 
                        testo.contains("\"weather_code\":99") ||
                        testo.contains("\"weather_code\":95") ||
                        testo.contains("\"weather_code\":82")

        if (haRischio) "RISCHIO" else "OK"
    } catch (e: Exception) {
        "Impossibile scaricare i dati: ${e.localizedMessage}"
    }
}

class MeteoWorker(val context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result {
        val esito = controllaMeteoComune(comuniAltoAdige[0])
        if (esito == "RISCHIO") {
            inviaNotifica(
                context,
                "⚠️ Allerta Grandine Alto Adige!",
                "Rischio grandine o temporali forti rilevato."
            )
        }
        return Result.success()
    }
}

fun avviaMonitoraggioBackground(context: Context) {
    val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    val workRequest = PeriodicWorkRequestBuilder<MeteoWorker>(1, TimeUnit.HOURS)
        .setConstraints(constraints)
        .build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        "MeteoGrandineWork",
        ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
        workRequest
    )
}

fun createNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val name = "Allerta Grandine"
        val descriptionText = "Notifiche per rischio grandine"
        val importance = NotificationManager.IMPORTANCE_HIGH
        val channel = NotificationChannel("GRANDINE_CHANNEL", name, importance).apply {
            description = descriptionText
        }
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
}

fun inviaNotifica(context: Context, titolo: String, messaggio: String) {
    try {
        val iconResId = context.applicationInfo.icon
        val builder = NotificationCompat.Builder(context, "GRANDINE_CHANNEL")
            .setSmallIcon(if (iconResId != 0) iconResId else android.R.drawable.ic_dialog_info)
            .setContentTitle(titolo)
            .setContentText(messaggio)
            .setStyle(NotificationCompat.BigTextStyle().bigText(messaggio))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        val notificationManager = ContextCompat.getSystemService(context, NotificationManager::class.java)
        notificationManager?.notify(System.currentTimeMillis().toInt(), builder.build())
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
