package com.example.printcontrol

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        PrintWatchdogWorker.scheduleBackgroundWatchdog(applicationContext)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF00E676),
                    secondary = Color(0xFF00B0FF),
                    background = Color(0xFF0F1216),
                    surface = Color(0xFF1B2028)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PrintControlDashboard()
                }
            }
        }
    }
}

@Composable
fun PrintControlDashboard(viewModel: DashboardViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // HLAVIČKA
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "3D Print & Tapo Hub",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "Stav: ${if (state.printer.isOnline) "Online" else "Offline"} • ${state.watchdog.lastCheckTime}",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
                Icon(
                    imageVector = if (state.printer.isOnline) Icons.Default.CloudDone else Icons.Default.CloudOff,
                    contentDescription = null,
                    tint = if (state.printer.isOnline) MaterialTheme.colorScheme.primary else Color.Gray
                )
            }
        }

        // DIAGNOSTICKÁ ALEBO BEZPEČNOSTNÁ SPRÁVA
        state.errorMessage?.let { error ->
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF4A151B)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFF5252))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = error, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { viewModel.dismissError() }) {
                            Text("OK", color = Color(0xFFFF8A80))
                        }
                    }
                }
            }
        }

        // 1. KARTA: BAMBU LAB + VOLITEĽNÁ KAMERA
        item {
            BambuPrinterCard(
                printer = state.printer,
                isCameraVisible = state.isCameraVisible,
                isCameraLoading = state.isCameraLoading,
                cameraBitmap = state.cameraBitmap,
                onPauseToggle = { viewModel.onTogglePausePrint() },
                onLightToggle = { viewModel.onToggleChamberLight() },
                onCameraToggle = { viewModel.onToggleCameraPreview() }
            )
        }

        // 2. KARTA: HLÍDAČ TISKU
        item {
            WatchdogCard(
                watchdog = state.watchdog,
                onToggleAutoOff = { viewModel.onToggleAutoPowerOff() }
            )
        }

        // 3. SEKCIA: TAPO SMART HOME
        item {
            Text(
                text = "Tapo Smart Home",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        }

        items(state.tapoDevices) { device ->
            TapoDeviceCard(
                device = device,
                onToggle = { viewModel.onToggleTapo(device.id) }
            )
        }
    }
}

@Composable
fun BambuPrinterCard(
    printer: BambuPrinterState,
    isCameraVisible: Boolean,
    isCameraLoading: Boolean,
    cameraBitmap: Bitmap?,
    onPauseToggle: () -> Unit,
    onLightToggle: () -> Unit,
    onCameraToggle: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Print,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(printer.name, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                        Text(printer.currentFile, fontSize = 12.sp, color = Color.Gray)
                    }
                }
                AssistChip(
                    onClick = {},
                    label = { Text(printer.statusText) },
                    colors = AssistChipDefaults.assistChipColors(
                        labelColor = if (printer.isPaused) Color(0xFFFFB74D) else MaterialTheme.colorScheme.primary
                    )
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Priebeh tlače
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("${printer.progressPercent} %", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.White)
                Text("Zostáva: ${printer.remainingMinutes} min", fontSize = 14.sp, color = Color.LightGray)
            }
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { printer.progressPercent / 100f },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color(0xFF2C3440),
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Teploty
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Tryska (Nozzle)", fontSize = 12.sp, color = Color.Gray)
                    Text("${printer.nozzleTemp} / ${printer.nozzleTargetTemp} °C", fontWeight = FontWeight.Bold, color = Color(0xFFFF8A65))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Podložka (Bed)", fontSize = 12.sp, color = Color.Gray)
                    Text("${printer.bedTemp} °C", fontWeight = FontWeight.Bold, color = Color(0xFF4FC3F7))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // VOLITEĽNÝ NÁHĽAD KAMERY (Rozbalí sa iba na vyžiadanie)
            AnimatedVisibility(visible = isCameraVisible) {
                Column {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(210.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF0A0C0E)),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            isCameraLoading && cameraBitmap == null -> {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("Pripájam šifrovanú kameru...", color = Color.Gray, fontSize = 12.sp)
                                }
                            }
                            cameraBitmap != null -> {
                                Image(
                                    bitmap = cameraBitmap.asImageBitmap(),
                                    contentDescription = "Náhľad kamery z 3D tlačiarne",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                            else -> {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.VideocamOff, contentDescription = null, tint = Color.Gray)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("Obraz kamery nie je dostupný", color = Color.Gray, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            // Ovládacie tlačidlá (Pozastaviť, Svetlo komory, Kamera)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onPauseToggle,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (printer.isPaused) MaterialTheme.colorScheme.primary else Color(0xFF37474F)
                    )
                ) {
                    Icon(
                        imageVector = if (printer.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (printer.isPaused) "Tlačiť" else "Pauza", fontSize = 13.sp)
                }

                OutlinedButton(
                    onClick = onLightToggle,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                ) {
                    Icon(
                        // OPRAVA: Použijeme Icons.Default.Lightbulb, farba (tint) nižšie ukáže či svieti
                        imageVector = Icons.Default.Lightbulb,
                        contentDescription = null,
                        tint = if (printer.chamberLightOn) Color(0xFFFFD54F) else Color.Gray
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Svetlo", color = Color.White, fontSize = 13.sp)
                }

                OutlinedButton(
                    onClick = onCameraToggle,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                ) {
                    Icon(
                        imageVector = if (isCameraVisible) Icons.Default.Videocam else Icons.Default.VideocamOff,
                        contentDescription = null,
                        tint = if (isCameraVisible) MaterialTheme.colorScheme.secondary else Color.Gray
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (isCameraVisible) "Skryť" else "Kamera", color = Color.White, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
fun WatchdogCard(
    watchdog: WatchdogState,
    onToggleAutoOff: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Shield, contentDescription = null, tint = Color(0xFF81C784))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Hlídač tisku & Ochrana", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color.White)
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Stav kontroly: ${if (watchdog.aiSpaghettiCheckOk) "Tlač prebieha čisto bez chýb" else "Pozor: Zistená možná chyba tlače!"}",
                fontSize = 13.sp,
                color = if (watchdog.aiSpaghettiCheckOk) Color(0xFFA5D6A7) else Color(0xFFFF8A80)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Automaticky vypnúť Tapo po dotlačení", color = Color.White, fontSize = 14.sp)
                    Text(
                        "Čaká na vychladnutie trysky pod ${watchdog.safeNozzleTempThreshold} °C",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }
                Switch(
                    checked = watchdog.autoPowerOffEnabled,
                    onCheckedChange = { onToggleAutoOff() }
                )
            }
        }
    }
}

@Composable
fun TapoDeviceCard(
    device: TapoDevice,
    onToggle: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (device.type == "PLUG") Icons.Default.Power else Icons.Default.WbIncandescent,
                    contentDescription = null,
                    tint = if (device.isOn) MaterialTheme.colorScheme.secondary else Color.Gray
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(device.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Color.White)
                    Text(
                        text = if (device.isOn) "Odber: ${device.currentPowerWatts} W • Dnes: ${device.todayEnergyWh} Wh" else "Vypnuté (0 W)",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
            }
            Switch(
                checked = device.isOn,
                onCheckedChange = { onToggle() }
            )
        }
    }
}