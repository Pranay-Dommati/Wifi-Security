package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.myapplication.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

// Build an Android app that analyzes the currently connected Wi-Fi network.
// The app should:
// 1. Request location permission at runtime.
// 2. Get the current Wi-Fi SSID and signal strength (RSSI).
// 3. Display Wi-Fi name and signal strength on screen.
// 4. Calculate a risk level based on signal strength.
//    - Weak signal -> High Risk
//    - Medium signal -> Medium Risk
//    - Strong signal -> Low Risk
// 5. Show the risk level clearly in the UI.
// 6. Handle cases where permission is denied or location is off.
// 7. Show proper messages instead of crashing.
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    WifiInfoScreen()
                }
            }
        }
    }
}

private sealed interface WifiUiState {
    data object Idle : WifiUiState
    data class Loaded(
        val ssid: String,
        val rssi: Int,
        val risk: String,
        val riskDetails: String
    ) : WifiUiState

    data class Error(
        val message: String,
        val needsLocation: Boolean = false
    ) : WifiUiState
}

private sealed interface WifiScanState {
    data object Loading : WifiScanState
    data class Loaded(val networks: List<WifiNetworkItem>) : WifiScanState
    data class Error(
        val message: String,
        val needsLocation: Boolean = false
    ) : WifiScanState
}

private data class WifiNetworkItem(
    val ssid: String,
    val rssi: Int,
    val security: String,
    val risk: String,
    val riskDetails: String,
    val isOpen: Boolean
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun WifiInfoScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val requiredPermissions = remember { requiredPermissions() }
    var uiState by remember { mutableStateOf<WifiUiState>(WifiUiState.Idle) }
    var scanState by remember { mutableStateOf<WifiScanState>(WifiScanState.Loading) }
    var hasPermissions by remember { mutableStateOf(false) }
    var permissionMessage by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var passwordBySsid by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var pendingConnect by remember { mutableStateOf<WifiNetworkItem?>(null) }
    val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    var activeNetworkCallback by remember { mutableStateOf<ConnectivityManager.NetworkCallback?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    fun refreshPermissions() {
        val missing = requiredPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        hasPermissions = missing.isEmpty()
        permissionMessage = if (!hasPermissions && missing.isNotEmpty()) {
            "Missing permission: ${missing.joinToString()}."
        } else {
            null
        }
    }

    fun refreshAll(triggerScan: Boolean) {
        refreshPermissions()
        if (hasPermissions) {
            uiState = loadWifiInfo(context)
            if (triggerScan) {
                scanState = WifiScanState.Loading
                scope.launch { scanState = loadWifiScan(context) }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val denied = result.filterValues { granted -> !granted }.keys
        permissionMessage = if (denied.isNotEmpty()) {
            "Permission denied. Allow access in system settings."
        } else {
            null
        }
        refreshAll(triggerScan = true)
    }

    LaunchedEffect(refreshTick) {
        refreshAll(triggerScan = true)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                refreshTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            activeNetworkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Wi-Fi Risk Check") },
                navigationIcon = {
                    Icon(
                        imageVector = Icons.Filled.Wifi,
                        contentDescription = null,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                },
                actions = {
                    IconButton(onClick = { openAppSettings(context) }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Open app settings")
                    }
                    IconButton(
                        onClick = {
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    "Risk is estimated using signal strength + security. Always verify the network name with a trusted source."
                                )
                            }
                        }
                    ) {
                        Icon(Icons.Filled.Info, contentDescription = "About")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (hasPermissions) {
                ExtendedFloatingActionButton(
                    onClick = {
                        refreshAll(triggerScan = true)
                        if (!hasPermissions) {
                            scope.launch {
                                snackbarHostState.showSnackbar("Grant permission to scan nearby Wi‑Fi networks.")
                            }
                            return@ExtendedFloatingActionButton
                        }
                    },
                    icon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                    text = { Text("Scan") },
                    modifier = Modifier
                        .navigationBarsPadding()
                        .imePadding()
                )
            }
        }
    ) { innerPadding ->
        if (!hasPermissions) {
            PermissionGate(
                modifier = Modifier
                    .padding(innerPadding)
                    .navigationBarsPadding()
                    .padding(24.dp),
                permissionMessage = permissionMessage,
                onGrant = { permissionLauncher.launch(requiredPermissions.toTypedArray()) },
                onOpenSettings = { openAppSettings(context) }
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .padding(innerPadding)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(16.dp)
            ) {
                item {
                    CurrentConnectionCard(
                        context = context,
                        state = uiState,
                        onRetry = { uiState = loadWifiInfo(context) }
                    )
                }

                item {
                    SectionHeader(
                        title = "Nearby networks",
                        subtitle = "Review risk before connecting. Risk is higher for weak signals and unsafe security (Open/WEP)."
                    )
                }

                when (val state = scanState) {
                    is WifiScanState.Loading -> {
                        item {
                            ElevatedCard {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                    Text("Scanning…", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }

                    is WifiScanState.Error -> {
                        item {
                            val needsLocation = state.needsLocation
                            EmptyStateCard(
                                icon = Icons.Filled.WifiOff,
                                title = "Can’t scan right now",
                                message = state.message,
                                primaryActionLabel = if (needsLocation) "Location settings" else "Scan again",
                                onPrimaryAction = {
                                    if (needsLocation) {
                                        openLocationSettings(context)
                                    } else {
                                        refreshAll(triggerScan = true)
                                    }
                                },
                                secondaryActionLabel = if (needsLocation) "Scan again" else null,
                                onSecondaryAction = if (needsLocation) {
                                    {
                                        refreshAll(triggerScan = true)
                                    }
                                } else {
                                    null
                                }
                            )
                        }
                    }

                    is WifiScanState.Loaded -> {
                        val connectedSsid = (uiState as? WifiUiState.Loaded)?.ssid
                        items(state.networks, key = { it.ssid }) { network ->
                            WifiNetworkCard(
                                network = network,
                                isConnected = !connectedSsid.isNullOrBlank() && connectedSsid == network.ssid,
                                onReview = { pendingConnect = network }
                            )
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(72.dp)) }
            }
        }
    }

    pendingConnect?.let { network ->
        var password by rememberSaveable(network.ssid) { mutableStateOf(passwordBySsid[network.ssid].orEmpty()) }
        var passwordVisible by rememberSaveable(network.ssid) { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { pendingConnect = null },
            icon = {
                Icon(
                    imageVector = when (network.risk) {
                        "High" -> Icons.Filled.Warning
                        "Medium" -> Icons.Filled.SignalCellularAlt
                        else -> Icons.Filled.Wifi
                    },
                    contentDescription = null
                )
            },
            title = { Text("Review before connecting") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(network.ssid, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    RiskRow(network = network)
                    HorizontalDivider()

                    if (!network.isOpen) {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { value ->
                                password = value
                                passwordBySsid = passwordBySsid.toMutableMap().apply { put(network.ssid, value) }
                            },
                            label = { Text("Wi-Fi password") },
                            leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        imageVector = if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                        contentDescription = if (passwordVisible) "Hide password" else "Show password"
                                    )
                                }
                            },
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done
                            ),
                            singleLine = true
                        )
                    } else {
                        Text(
                            "This looks like an open network (no password). Avoid sensitive activity unless you trust it.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        try {
                            activeNetworkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
                        } catch (_: Exception) {
                            // Ignore
                        }

                        val result = connectToNetwork(
                            context = context,
                            connectivityManager = connectivityManager,
                            network = network,
                            password = password
                        )
                        activeNetworkCallback = result.callback
                        scope.launch { snackbarHostState.showSnackbar(result.message) }

                        // Give Android a moment to apply the change, then refresh current connection info.
                        scope.launch {
                            delay(2000)
                            refreshTick++
                        }
                        pendingConnect = null
                    }
                ) {
                    Text("Connect anyway")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingConnect = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun PermissionGate(
    modifier: Modifier,
    permissionMessage: String?,
    onGrant: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(
            imageVector = Icons.Filled.MyLocation,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp)
        )
        Text(
            text = "Permission required",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                "To scan nearby Wi‑Fi networks, allow Nearby devices + Location permission and keep Location turned ON."
            } else {
                "To scan nearby Wi‑Fi networks, allow Location permission and keep Location turned ON."
            },
            style = MaterialTheme.typography.bodyMedium
        )
        permissionMessage?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onGrant) { Text("Grant") }
            FilledTonalButton(onClick = onOpenSettings) { Text("Open settings") }
        }
    }
}

@Composable
private fun CurrentConnectionCard(
    context: Context,
    state: WifiUiState,
    onRetry: () -> Unit
) {
    SectionHeader(
        title = "Current connection",
        subtitle = "If you’re not connected yet, this may show as unavailable."
    )
    ElevatedCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                is WifiUiState.Idle -> Text("Loading…", style = MaterialTheme.typography.bodyMedium)

                is WifiUiState.Loaded -> {
                    Text(state.ssid, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("RSSI: ${state.rssi} dBm", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Risk: ${state.risk}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        state.riskDetails,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                is WifiUiState.Error -> {
                    Text(state.message, style = MaterialTheme.typography.bodyMedium)
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FilledTonalButton(onClick = onRetry) { Text("Retry") }
                            FilledTonalButton(onClick = { openWifiSettings(context) }) { Text("Wi-Fi settings") }
                        }
                        if (state.needsLocation) {
                            FilledTonalButton(onClick = { openLocationSettings(context) }) {
                                Text("Location settings")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WifiNetworkCard(
    network: WifiNetworkItem,
    isConnected: Boolean,
    onReview: () -> Unit
) {
    val signal = signalLabel(network.rssi)
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(network.ssid, fontWeight = FontWeight.SemiBold) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("$signal • ${network.rssi} dBm")
                    Text("Security: ${network.security}")
                    if (isConnected) {
                        Text(
                            "Connected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            leadingContent = {
                Icon(
                    imageVector = if (network.isOpen) Icons.Filled.Wifi else Icons.Filled.Lock,
                    contentDescription = if (network.isOpen) "Open Wi-Fi network" else "Secured Wi-Fi network"
                )
            },
            trailingContent = {
                RiskChip(risk = network.risk, details = network.riskDetails)
            }
        )
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FilledTonalButton(
                onClick = onReview,
                enabled = !isConnected
            ) {
                Text(if (isConnected) "Connected" else "Review")
            }
            Text(
                text = if (network.isOpen) "Open network" else "Password required",
                style = MaterialTheme.typography.bodySmall,
                color = if (network.isOpen) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterVertically)
            )
        }
    }
}

@Composable
private fun RiskRow(network: WifiNetworkItem) {
    val signal = signalLabel(network.rssi)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.SignalCellularAlt, contentDescription = null)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
            Text("Signal: $signal (${network.rssi} dBm)", style = MaterialTheme.typography.bodyMedium)
            Text("Security: ${network.security}", style = MaterialTheme.typography.bodyMedium)
        }
        RiskChip(risk = network.risk, details = network.riskDetails)
    }
}

@Composable
private fun RiskChip(risk: String, details: String) {
    val colors = riskColors(risk)
    ElevatedCard(colors = androidx.compose.material3.CardDefaults.elevatedCardColors(containerColor = colors.container)) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(
                text = risk.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = colors.onContainer
            )
            Text(
                text = details,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onContainer
            )
        }
    }
}

private data class RiskChipColors(
    val container: androidx.compose.ui.graphics.Color,
    val onContainer: androidx.compose.ui.graphics.Color
)

@Composable
private fun riskColors(risk: String): RiskChipColors {
    return when (risk) {
        "High" -> RiskChipColors(
            container = MaterialTheme.colorScheme.errorContainer,
            onContainer = MaterialTheme.colorScheme.onErrorContainer
        )

        "Medium" -> RiskChipColors(
            container = MaterialTheme.colorScheme.tertiaryContainer,
            onContainer = MaterialTheme.colorScheme.onTertiaryContainer
        )

        else -> RiskChipColors(
            container = MaterialTheme.colorScheme.secondaryContainer,
            onContainer = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

@Composable
private fun EmptyStateCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    message: String,
    primaryActionLabel: String,
    onPrimaryAction: () -> Unit,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null
) {
    ElevatedCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(message, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = onPrimaryAction) { Text(primaryActionLabel) }
                if (!secondaryActionLabel.isNullOrBlank() && onSecondaryAction != null) {
                    FilledTonalButton(onClick = onSecondaryAction) { Text(secondaryActionLabel) }
                }
            }
        }
    }
}

private fun signalLabel(rssi: Int): String {
    return when {
        rssi >= -55 -> "Excellent"
        rssi >= -67 -> "Good"
        rssi >= -80 -> "Fair"
        else -> "Weak"
    }
}

private fun requiredPermissions(): List<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        // Some OEM builds still gate SSID + scans behind Location permission even on Android 13+.
        // Request both for better real‑device compatibility.
        listOf(
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

private fun isLocationEnabledCompat(context: Context): Boolean {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        locationManager.isLocationEnabled
    } else {
        locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }
}

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
private fun readCurrentWifiInfo(context: Context, wifiManager: WifiManager): android.net.wifi.WifiInfo? {
    // Prefer ConnectivityManager transportInfo when available.
    val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    val activeNetwork = connectivityManager?.activeNetwork
    val caps = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            val transport = caps.transportInfo
            if (transport is android.net.wifi.WifiInfo) {
                return transport
            }
        }

        // If Wi‑Fi is connected but NOT the app's active/default network (e.g., cellular is default),
        // try to locate any Wi‑Fi transport among all networks.
        connectivityManager?.allNetworks?.forEach { network ->
            val networkCaps = connectivityManager.getNetworkCapabilities(network) ?: return@forEach
            if (!networkCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@forEach
            val transport = networkCaps.transportInfo
            if (transport is android.net.wifi.WifiInfo) {
                val ssid = normalizeSsid(transport.ssid)
                if (ssid.isNotBlank()) return transport
            }
        }
    }
    return wifiManager.connectionInfo
}

private fun normalizeSsid(rawSsid: String?): String {
    val ssid = rawSsid.orEmpty().trim().trim('"')
    return if (ssid.equals("<unknown ssid>", ignoreCase = true)) "" else ssid
}

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
private fun loadWifiInfo(context: Context): WifiUiState {
    val wifiManager = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as WifiManager

    val wifiEnabled = wifiManager.wifiState == WifiManager.WIFI_STATE_ENABLED ||
        wifiManager.wifiState == WifiManager.WIFI_STATE_ENABLING
    if (!wifiEnabled) {
        return WifiUiState.Error("Wi-Fi is disabled. Turn it on and try again.")
    }

    val isLocationEnabled = isLocationEnabledCompat(context)
    val info = readCurrentWifiInfo(context, wifiManager)
    val ssid = normalizeSsid(info?.ssid)
    if (ssid.isBlank()) {
        return if (!isLocationEnabled) {
            WifiUiState.Error(
                message = "Wi-Fi name unavailable. Turn ON Location and connect to a Wi-Fi network.",
                needsLocation = true
            )
        } else {
            WifiUiState.Error("Wi-Fi name unavailable. Ensure you are connected.")
        }
    }

    val rssi = info?.rssi ?: wifiManager.connectionInfo?.rssi ?: -100
    val risk = riskFromRssi(rssi)
    return WifiUiState.Loaded(ssid, rssi, risk, "Signal strength analysis")
}

private fun riskFromRssi(rssi: Int): String {
    return when {
        rssi < -80 -> "High"
        rssi <= -60 -> "Medium"
        else -> "Low"
    }
}

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
private suspend fun loadWifiScan(context: Context): WifiScanState {
    val wifiManager = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as WifiManager

    val wifiEnabled = wifiManager.wifiState == WifiManager.WIFI_STATE_ENABLED ||
        wifiManager.wifiState == WifiManager.WIFI_STATE_ENABLING
    if (!wifiEnabled) {
        return WifiScanState.Error("Wi-Fi is disabled. Turn it on and try again.")
    }

    val missingPermissions = requiredPermissions().filter { permission ->
        ContextCompat.checkSelfPermission(context, permission) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    if (missingPermissions.isNotEmpty()) {
        return WifiScanState.Error(
            "Permission missing (${missingPermissions.joinToString()}). Grant access to scan Wi‑Fi networks."
        )
    }

    val isLocationEnabled = isLocationEnabledCompat(context)
    if (!isLocationEnabled) {
        // Many devices still require Location services enabled for Wi‑Fi scans, even on Android 13+.
        return WifiScanState.Error(
            message = "Location is off. Turn ON Location to scan nearby Wi‑Fi networks.",
            needsLocation = true
        )
    }

    return try {
        val appContext = context.applicationContext
        // Await scan completion instead of using a fixed delay.
        val updated = startScanAndAwaitResults(appContext, wifiManager, timeoutMillis = 8000)
        val results = wifiManager.scanResults
        val networks = results
            .mapNotNull { result ->
                val ssid = result.SSID?.trim().orEmpty()
                if (ssid.isBlank()) return@mapNotNull null
                val security = securityFromCapabilities(result.capabilities)
                val (adjustedRisk, details) = riskFromRssiAndSecurity(result.level, security)
                WifiNetworkItem(
                    ssid = ssid,
                    rssi = result.level,
                    security = security,
                    risk = adjustedRisk,
                    riskDetails = details,
                    isOpen = security.equals("Open", ignoreCase = true)
                )
            }
            .distinctBy { it.ssid }
            .sortedByDescending { it.rssi }

        if (networks.isEmpty()) {
            val tail = if (!updated) {
                "Android may be throttling scans. Wait ~30 seconds and try again (or toggle Wi‑Fi OFF/ON)."
            } else {
                ""
            }
            WifiScanState.Error(
                message = "No networks found. If Wi‑Fi is ON, try again. Also ensure Location is ON and you’re testing on a physical device (emulators often return empty results). $tail".trim(),
                needsLocation = false
            )
        } else {
            WifiScanState.Loaded(networks)
        }
    } catch (exception: SecurityException) {
        val missing = requiredPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            WifiScanState.Error(
                "Permission missing (${missing.joinToString()}). Grant access to scan Wi‑Fi networks."
            )
        } else {
            WifiScanState.Error(
                "Android blocked Wi‑Fi scanning. Ensure Wi‑Fi is ON and try again. (${exception.localizedMessage ?: "SecurityException"})"
            )
        }
    }
}

@SuppressLint("MissingPermission")
private suspend fun startScanAndAwaitResults(
    context: Context,
    wifiManager: WifiManager,
    timeoutMillis: Long
): Boolean {
    val appContext = context.applicationContext
    return withTimeoutOrNull(timeoutMillis) {
        suspendCancellableCoroutine { cont ->
            val completed = AtomicBoolean(false)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (!completed.compareAndSet(false, true)) return
                    runCatching { appContext.unregisterReceiver(this) }
                    val updated = intent?.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, true) ?: true
                    cont.resume(updated)
                }
            }

            // Register before triggering the scan so we don't miss the broadcast.
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )

            val started = try {
                wifiManager.startScan()
            } catch (_: SecurityException) {
                false
            }

            if (!started) {
                if (completed.compareAndSet(false, true)) {
                    runCatching { appContext.unregisterReceiver(receiver) }
                    cont.resume(false)
                }
                return@suspendCancellableCoroutine
            }

            cont.invokeOnCancellation {
                if (completed.compareAndSet(false, true)) {
                    runCatching { appContext.unregisterReceiver(receiver) }
                }
            }
        }
    } ?: false
}

private fun securityFromCapabilities(capabilities: String): String {
    return when {
        capabilities.contains("WEP", ignoreCase = true) -> "WEP"
        capabilities.contains("SAE", ignoreCase = true) ||
            capabilities.contains("WPA3", ignoreCase = true) -> "WPA3"
        capabilities.contains("WPA2", ignoreCase = true) -> "WPA2"
        capabilities.contains("WPA", ignoreCase = true) -> "WPA"
        else -> "Open"
    }
}

private fun riskFromRssiAndSecurity(rssi: Int, security: String): Pair<String, String> {
    val baseRisk = riskFromRssi(rssi)
    val isUnsafeSecurity = security.equals("Open", ignoreCase = true) ||
        security.equals("WEP", ignoreCase = true)
    val adjustedRisk = when {
        isUnsafeSecurity && baseRisk == "Low" -> "Medium"
        isUnsafeSecurity && baseRisk == "Medium" -> "High"
        else -> baseRisk
    }
    val details = if (isUnsafeSecurity) {
        "$security network"
    } else {
        "Signal strength"
    }
    return adjustedRisk to details
}

private data class ConnectionResult(
    val message: String,
    val callback: ConnectivityManager.NetworkCallback?
)

private fun isCurrentlyConnectedTo(context: Context, ssid: String): Boolean {
    return try {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = readCurrentWifiInfo(context, wifiManager)
        val current = normalizeSsid(info?.ssid)
        current.isNotBlank() && current == ssid
    } catch (_: Exception) {
        false
    }
}

@SuppressLint("MissingPermission")
@Suppress("MissingPermission")
private fun connectToNetwork(
    context: Context,
    connectivityManager: ConnectivityManager?,
    network: WifiNetworkItem,
    password: String
): ConnectionResult {
    // If we're already connected, don't prompt for password again.
    if (isCurrentlyConnectedTo(context, network.ssid)) {
        return ConnectionResult("Already connected to ${network.ssid}.", null)
    }

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        openWifiSettings(context)
        return ConnectionResult(
            "Open Wi-Fi settings to connect to ${network.ssid}.",
            null
        )
    }

    if (!network.isOpen) {
        if (password.isBlank()) {
            // We cannot read stored Wi‑Fi passwords on modern Android; let the system UI handle it.
            openWifiPanel(context)
            return ConnectionResult(
                "Open the Wi‑Fi panel to connect to ${network.ssid}. Android may not allow apps to auto‑connect without your confirmation.",
                null
            )
        }
        if (password.length < 8 && !network.security.contains("WEP", ignoreCase = true)) {
            return ConnectionResult("WPA/WPA2/WPA3 password must be at least 8 characters.", null)
        }
    }

    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val suggestionBuilder = android.net.wifi.WifiNetworkSuggestion.Builder().setSsid(network.ssid)
            if (!network.isOpen) {
                val isWpa3 = network.security.equals("WPA3", ignoreCase = true) || network.security.contains("SAE", ignoreCase = true)
                if (isWpa3 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        suggestionBuilder.setWpa3Passphrase(password)
                    } catch (_: Exception) {
                        suggestionBuilder.setWpa2Passphrase(password)
                    }
                } else {
                    suggestionBuilder.setWpa2Passphrase(password)
                }
            }
            // Some devices require user approval before suggestions can be used.
            runCatching { wifiManager.addNetworkSuggestions(listOf(suggestionBuilder.build())) }
        }

        val specifierBuilder = WifiNetworkSpecifier.Builder()
            .setSsid(network.ssid)

        if (!network.isOpen) {
            val isWpa3 = network.security.equals("WPA3", ignoreCase = true) || network.security.contains("SAE", ignoreCase = true)
            
            if (isWpa3 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Not all implementations of WPA3 are supported by setWpa3Passphrase perfectly.
                try {
                    specifierBuilder.setWpa3Passphrase(password)
                } catch (e: NoSuchMethodError) {
                    specifierBuilder.setWpa2Passphrase(password)
                } catch (e: Exception) {
                    specifierBuilder.setWpa2Passphrase(password)
                }
            } else {
                specifierBuilder.setWpa2Passphrase(password)
            }
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifierBuilder.build())
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                // Bind this process to the requested Wi‑Fi so subsequent reads of activeNetwork/SSID work,
                // and networking uses the selected Wi‑Fi.
                try {
                    connectivityManager?.bindProcessToNetwork(network)
                } catch (_: Exception) {
                    // Ignore; some devices restrict binding.
                }
            }

            override fun onLost(network: android.net.Network) {
                try {
                    connectivityManager?.bindProcessToNetwork(null)
                } catch (_: Exception) {
                    // Ignore
                }
            }
        }

        connectivityManager?.requestNetwork(request, callback)

        // Many OEM devices don't show the confirmation dialog reliably; opening the Wi‑Fi panel gives
        // the user a guaranteed way to confirm / select the network.
        openWifiPanel(context)
        ConnectionResult(
            "Connecting to ${network.ssid}. Confirm the system prompt / Wi‑Fi panel if it appears.",
            callback
        )
    } catch (e: IllegalArgumentException) {
        ConnectionResult("Invalid details: ${e.localizedMessage}", null)
    } catch (e: SecurityException) {
        ConnectionResult("Permission denied to connect: ${e.localizedMessage}", null)
    } catch (e: Exception) {
        ConnectionResult("Failed to connect: ${e.localizedMessage}", null)
    }
}

private fun openWifiSettings(context: Context) {
    val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

private fun openWifiPanel(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        Intent(Settings.Panel.ACTION_WIFI)
    } else {
        Intent(Settings.ACTION_WIFI_SETTINGS)
    }.apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

private fun openLocationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    MyApplicationTheme {
        WifiInfoScreen()
    }
}