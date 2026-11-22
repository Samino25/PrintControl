package com.example.printcontrol

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class SmartPrintRepository {

    // HTTP klient s nastaveným časovým limitom pre šetrenie batérie pri zlom signáli
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private var mqttClient: MqttClient? = null

    // Aktuálny stav v pamäti, ktorý priebežne dopĺňajú MQTT správy z tlačiarne
    @Volatile
    private var currentPrinter = BambuPrinterState()
    private var currentWatchdog = WatchdogState()
    private var lastError: String? = null

    private var currentTapoList = listOf(
        TapoDevice(
            id = DeviceConfig.TAPO_PLUG_DEVICE_ID,
            name = "Tapo P110 - 3D Tlačiareň",
            type = "PLUG",
            isOn = true,
            currentPowerWatts = 115.0,
            todayEnergyWh = 410
        ),
        TapoDevice(
            id = "tapo_light_desk",
            name = "Tapo L530 - Osvetlenie stola",
            type = "LIGHT",
            isOn = true,
            currentPowerWatts = 8.5,
            todayEnergyWh = 45
        )
    )

    /**
     * Hlavná funkcia na získanie čerstvých dát.
     * Ak ešte nie sme pripojení na MQTT Bambu Lab, nadviaže šifrované spojenie.
     */
    suspend fun fetchLatestData(): DashboardUiState = withContext(Dispatchers.IO) {
        if (DeviceConfig.USE_REAL_DEVICES) {
            ensureMqttConnected()
        }

        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        currentWatchdog = currentWatchdog.copy(lastCheckTime = timeFormat.format(Date()))

        // Automatické vypnutie Tapo zásuvky po dotlačení a vychladnutí trysky
        if (currentPrinter.progressPercent == 100 &&
            currentWatchdog.autoPowerOffEnabled &&
            currentPrinter.nozzleTemp <= currentWatchdog.safeNozzleTempThreshold
        ) {
            sendTapoCloudCommand(DeviceConfig.TAPO_PLUG_DEVICE_ID, turnOn = false)
            setLocalTapoState(DeviceConfig.TAPO_PLUG_DEVICE_ID, false)
        }

        DashboardUiState(
            printer = currentPrinter,
            tapoDevices = currentTapoList,
            watchdog = currentWatchdog,
            errorMessage = lastError
        )
    }

    /**
     * Pripojenie k Bambu Lab tlačiarni cez šifrovaný MQTT protokol (Port 8883).
     */
    private fun ensureMqttConnected() {
        try {
            if (mqttClient?.isConnected == true) return

            val brokerUrl = "ssl://${DeviceConfig.BAMBU_HOST}:${DeviceConfig.BAMBU_PORT}"
            val clientId = "AndroidPrintControl_${System.currentTimeMillis()}"

            mqttClient = MqttClient(brokerUrl, clientId, MemoryPersistence())

            val options = MqttConnectOptions().apply {
                userName = DeviceConfig.BAMBU_USERNAME
                password = DeviceConfig.BAMBU_ACCESS_CODE.toCharArray()
                connectionTimeout = 5
                keepAliveInterval = 30
                isAutomaticReconnect = true
                isCleanSession = true
                // Použijeme SSL továreň, ktorá akceptuje lokálny certifikát Bambu tlačiarne
                socketFactory = createTrustAllSslSocketFactory()
            }

            mqttClient?.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    currentPrinter = currentPrinter.copy(isOnline = false, statusText = "Spojenie prerušené")
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    message?.let { parseBambuTelemetryJson(String(it.payload)) }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            mqttClient?.connect(options)

            // Prihlásime sa na odber živých správ z tlačiarne
            val reportTopic = "device/${DeviceConfig.BAMBU_SERIAL}/report"
            mqttClient?.subscribe(reportTopic, 0)

            // Požiadame tlačiareň, aby nám hneď poslala kompletný stav (pushall)
            sendBambuMqttCommand("""{"pushing": {"sequence_id": "0", "command": "pushall"}}""")

            currentPrinter = currentPrinter.copy(isOnline = true, statusText = "Pripojené")
            lastError = null

        } catch (e: Exception) {
            currentPrinter = currentPrinter.copy(isOnline = false, statusText = "Offline")
            lastError = "Bambu MQTT (${DeviceConfig.BAMBU_HOST}): Skontroluj IP adresu a Access Code v DeviceConfig.kt."
        }
    }

    /**
     * Spracovanie JSON správy, ktorú posiela Bambu Lab tlačiareň.
     */
    private fun parseBambuTelemetryJson(jsonString: String) {
        try {
            val root = JSONObject(jsonString)
            val printObj = root.optJSONObject("print") ?: return

            val nozzle = printObj.optDouble("nozzle_temper", currentPrinter.nozzleTemp.toDouble()).toInt()
            val nozzleTarget = printObj.optDouble("nozzle_target_temper", currentPrinter.nozzleTargetTemp.toDouble()).toInt()
            val bed = printObj.optDouble("bed_temper", currentPrinter.bedTemp.toDouble()).toInt()
            val progress = printObj.optInt("mc_percent", currentPrinter.progressPercent)
            val remaining = printObj.optInt("mc_remaining_time", currentPrinter.remainingMinutes)
            val subtask = printObj.optString("subtask_name", currentPrinter.currentFile)
            val gcodeState = printObj.optString("gcode_state", "")

            // Zistenie stavu LED svetla v komore
            var lightOn = currentPrinter.chamberLightOn
            val lightsReport = printObj.optJSONArray("lights_report")
            if (lightsReport != null && lightsReport.length() > 0) {
                val mode = lightsReport.getJSONObject(0).optString("mode", "")
                lightOn = (mode == "on")
            }

            val isPaused = (gcodeState == "PAUSE")
            val statusSk = when (gcodeState) {
                "RUNNING" -> "Tlačí sa"
                "PAUSE" -> "Pozastavené"
                "FINISH" -> "Dokončené"
                "FAILED" -> "Chyba tlače!"
                "IDLE" -> "Pripravená"
                else -> currentPrinter.statusText
            }

            currentPrinter = currentPrinter.copy(
                isOnline = true,
                nozzleTemp = nozzle,
                nozzleTargetTemp = nozzleTarget,
                bedTemp = bed,
                progressPercent = progress,
                remainingMinutes = remaining,
                currentFile = if (subtask.isNotEmpty()) subtask else currentPrinter.currentFile,
                chamberLightOn = lightOn,
                isPaused = isPaused,
                statusText = statusSk
            )
        } catch (_: Exception) {
            // Ignorujeme čiastočné servisné správy, ktoré neobsahujú telemetriu
        }
    }

    /**
     * Odoslanie riadiaceho príkazu do Bambu Lab tlačiarne cez MQTT.
     */
    private fun sendBambuMqttCommand(jsonPayload: String) {
        try {
            if (mqttClient?.isConnected == true) {
                val requestTopic = "device/${DeviceConfig.BAMBU_SERIAL}/request"
                val message = MqttMessage(jsonPayload.toByteArray()).apply { qos = 0 }
                mqttClient?.publish(requestTopic, message)
            }
        } catch (e: Exception) {
            lastError = "Nepodarilo sa odoslať príkaz do tlačiarne: ${e.message}"
        }
    }

    // Ovládanie Bambu Lab: Pozastavenie alebo pokračovanie tlače
    suspend fun togglePausePrint(): DashboardUiState = withContext(Dispatchers.IO) {
        val nextPause = !currentPrinter.isPaused
        val command = if (nextPause) "pause" else "resume"

        if (DeviceConfig.USE_REAL_DEVICES) {
            val payload = """{"print": {"sequence_id": "0", "command": "$command"}}"""
            sendBambuMqttCommand(payload)
        }

        currentPrinter = currentPrinter.copy(
            isPaused = nextPause,
            statusText = if (nextPause) "Pozastavené" else "Tlačí sa"
        )
        fetchLatestData()
    }

    // Ovládanie Bambu Lab: Zapnutie alebo vypnutie svetla v komore
    suspend fun toggleChamberLight(): DashboardUiState = withContext(Dispatchers.IO) {
        val nextLight = !currentPrinter.chamberLightOn
        val mode = if (nextLight) "on" else "off"

        if (DeviceConfig.USE_REAL_DEVICES) {
            val payload = """{"system": {"sequence_id": "0", "command": "ledctrl", "led_node": "chamber_light", "led_mode": "$mode", "led_on_time": 500, "led_off_time": 500, "loop_times": 0, "interval_time": 0}}"""
            sendBambuMqttCommand(payload)
        }

        currentPrinter = currentPrinter.copy(chamberLightOn = nextLight)
        fetchLatestData()
    }

    /**
     * VOLITEĽNÁ KAMERA: Stiahne 1 aktuálnu snímku z kamery Bambu Lab (Port 6000 TLS)
     * alebo z externej HTTP URL adresy. Šetrí dáta aj batériu.
     */
    suspend fun fetchCameraFrame(): Bitmap? = withContext(Dispatchers.IO) {
        try {
            // Možnosť 1: Ak má používateľ nastavenú vlastnú HTTP URL kamery
            if (DeviceConfig.CUSTOM_CAMERA_SNAPSHOT_URL.isNotEmpty()) {
                val request = Request.Builder().url(DeviceConfig.CUSTOM_CAMERA_SNAPSHOT_URL).build()
                httpClient.newCall(request).execute().use { response ->
                    val bytes = response.body?.bytes() ?: return@withContext null
                    return@withContext BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            }

            // Možnosť 2: Priamy TLS protokol kamery Bambu Lab (P1S / P1P / A1 na porte 6000)
            val factory = createTrustAllSslSocketFactory()
            val socket = factory.createSocket(DeviceConfig.BAMBU_HOST, DeviceConfig.BAMBU_CAMERA_PORT) as SSLSocket
            socket.soTimeout = 4000
            socket.startHandshake()

            // Zostavíme 80-bajtový autorizačný paket pre Bambu kameru
            val authPacket = ByteBuffer.allocate(80).order(ByteOrder.LITTLE_ENDIAN)
            authPacket.putInt(0x40)     // Dĺžka hlavičky (64 bajtov)
            authPacket.putInt(0x3000)   // Typ správy (Auth)
            authPacket.putLong(0L)      // Rezervované nuly

            val userBytes = DeviceConfig.BAMBU_USERNAME.toByteArray(Charsets.US_ASCII)
            val passBytes = DeviceConfig.BAMBU_ACCESS_CODE.toByteArray(Charsets.US_ASCII)

            val userField = ByteArray(32)
            val passField = ByteArray(32)
            System.arraycopy(userBytes, 0, userField, 0, minOf(userBytes.size, 32))
            System.arraycopy(passBytes, 0, passField, 0, minOf(passBytes.size, 32))

            authPacket.put(userField)
            authPacket.put(passField)

            socket.outputStream.write(authPacket.array())
            socket.outputStream.flush()

            // Prečítame prvú kompletnú JPEG snímku (hľadáme štart FF D8 a koniec FF D9)
            val jpegBytes = readSingleJpegFromStream(socket.inputStream)
            socket.close()

            if (jpegBytes != null) {
                return@withContext BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            }
            null
        } catch (e: Exception) {
            lastError = "Kamera nie je dostupná: Skontroluj pripojenie k tlačiarni."
            null
        }
    }

    /**
     * Pomocná funkcia, ktorá zo surového prúdu dát kamery vyreže presne jeden JPEG obrázok.
     */
    private fun readSingleJpegFromStream(input: InputStream): ByteArray? {
        val buffer = ByteArrayOutputStream()
        var prevByte = -1
        var recording = false
        var bytesRead = 0
        val maxBytes = 1_500_000 // Ochranný limit 1.5 MB na jednu snímku

        while (bytesRead < maxBytes) {
            val current = input.read()
            if (current == -1) break
            bytesRead++

            // Začiatok JPEG obrázka (0xFF 0xD8)
            if (!recording && prevByte == 0xFF && current == 0xD8) {
                recording = true
                buffer.write(0xFF)
                buffer.write(0xD8)
            } else if (recording) {
                buffer.write(current)
                // Koniec JPEG obrázka (0xFF 0xD9)
                if (prevByte == 0xFF && current == 0xD9) {
                    return buffer.toByteArray()
                }
            }
            prevByte = current
        }
        return null
    }

    /**
     * Ovládanie Tapo Smart Home zariadenia na diaľku.
     */
    suspend fun toggleTapoDevice(deviceId: String): DashboardUiState = withContext(Dispatchers.IO) {
        val device = currentTapoList.find { it.id == deviceId }

        // Bezpečnostná poistka: Ochrana horúcej trysky pred vypnutím ventilátora
        if (device?.id == DeviceConfig.TAPO_PLUG_DEVICE_ID && device.isOn && currentPrinter.nozzleTemp > 60) {
            return@withContext DashboardUiState(
                printer = currentPrinter,
                tapoDevices = currentTapoList,
                watchdog = currentWatchdog,
                errorMessage = "Ochrana trysky: Zásuvku nemožno vypnúť, kým má tryska nad 60 °C!"
            )
        }

        val targetState = !(device?.isOn ?: false)

        if (DeviceConfig.USE_REAL_DEVICES && DeviceConfig.TAPO_CLOUD_TOKEN != "TVOJ_TPLINK_CLOUD_TOKEN") {
            sendTapoCloudCommand(deviceId, targetState)
        }

        setLocalTapoState(deviceId, targetState)
        fetchLatestData()
    }

    /**
     * Skutočné HTTP POST volanie na TP-Link Cloud API (wap.tplinkcloud.com).
     */
    private fun sendTapoCloudCommand(deviceId: String, turnOn: Boolean) {
        try {
            val stateInt = if (turnOn) 1 else 0
            val url = "https://wap.tplinkcloud.com?token=${DeviceConfig.TAPO_CLOUD_TOKEN}"
            val jsonPayload = """
                {
                    "method": "passthrough",
                    "params": {
                        "deviceId": "$deviceId",
                        "requestData": "{\"system\":{\"set_relay_state\":{\"state\":$stateInt}}}"
                    }
                }
            """.trimIndent()

            val body = jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder().url(url).post(body).build()
            httpClient.newCall(request).execute().close()
        } catch (e: Exception) {
            lastError = "Chyba komunikácie s Tapo Cloud: ${e.message}"
        }
    }

    private fun setLocalTapoState(deviceId: String, turnOn: Boolean) {
        currentTapoList = currentTapoList.map { device ->
            if (device.id == deviceId) {
                device.copy(
                    isOn = turnOn,
                    currentPowerWatts = if (turnOn) (if (device.type == "PLUG") 115.0 else 8.5) else 0.0
                )
            } else device
        }
    }

    suspend fun toggleAutoPowerOff(): DashboardUiState = withContext(Dispatchers.IO) {
        currentWatchdog = currentWatchdog.copy(autoPowerOffEnabled = !currentWatchdog.autoPowerOffEnabled)
        fetchLatestData()
    }

    fun clearError() {
        lastError = null
    }

    /**
     * Vytvorí SSLSocketFactory, ktorý akceptuje lokálny certifikát Bambu Lab tlačiarne.
     */
    private fun createTrustAllSslSocketFactory(): SSLSocketFactory {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, SecureRandom())
        return sslContext.socketFactory
    }
}