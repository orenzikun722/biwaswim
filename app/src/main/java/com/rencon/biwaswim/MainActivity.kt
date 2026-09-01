package com.rencon.biwaswim

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.rencon.biwaswim.bluetooth.DiscoveredBluetoothDevice
import com.rencon.biwaswim.map.MapManager
import com.rencon.biwaswim.nmea.GpsLocation
import com.rencon.biwaswim.nmea.NmeaParseDetail
import com.rencon.biwaswim.nmea.NmeaParser
import com.rencon.biwaswim.nmea.calculateDistance
import com.rencon.biwaswim.nmea.calculateDistanceBetween
import com.rencon.biwaswim.nmea.isSwimming
import com.rencon.biwaswim.notification.sendNotification
import com.rencon.biwaswim.permission.checkPermission
import com.rencon.biwaswim.service.GpsConnectionService
import com.rencon.biwaswim.vibration.VibrationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView
import java.security.SecureRandom
import java.util.Locale
import androidx.core.content.edit
import androidx.core.view.isInvisible
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.rencon.biwaswim.party.PartyWebSocketManager
import com.rencon.biwaswim.party.PartyWebSocketManager.companion.join
import com.rencon.biwaswim.party.PartyWebSocketManager.PartyConnectionCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

class MainActivity : AppCompatActivity(), GpsConnectionService.ServiceListener {

    companion object {
        private const val TAG = "MainActivity"
        private const val INITIAL_DATA_WAIT_MS = 3000L
        private const val DATA_TIMEOUT_MS = 4000L
        private const val ERROR_HOLD_MS = 3000L

        private const val SWIM_NOTIFICATION_ID = 2000
        private const val OUT_OF_WATER_TOLERANCE = 3

        private const val FAR_WARNING_NOTIFICATION_ID = 1000
        private const val FAR_WARNING_INTERVAL_MS = 20_000L
        private const val FAR_SHORE_THRESHOLD_METERS = 30.0
        var hostAppId: String? = null
    }

    private class ConnectionHealth {
        var isConnected: Boolean = false
        var connectedAt: Long = 0L
        var lastDataReceivedTime: Long = 0L
        var lastValidLocationTime: Long = 0L
        var lastChecksumErrorTime: Long = 0L
        var lastMalformedTime: Long = 0L
        var lastNoFixTime: Long = 0L

        fun onConnected() {
            isConnected = true
            connectedAt = System.currentTimeMillis()
            lastDataReceivedTime = 0L
            lastValidLocationTime = 0L
            lastChecksumErrorTime = 0L
            lastMalformedTime = 0L
            lastNoFixTime = 0L
        }

        fun onDisconnected() {
            isConnected = false
            connectedAt = 0L
            lastDataReceivedTime = 0L
            lastValidLocationTime = 0L
            lastChecksumErrorTime = 0L
            lastMalformedTime = 0L
            lastNoFixTime = 0L
        }
    }

    private enum class HealthStatus {
        HEALTHY,           // 正常に測位中
        WAITING_DATA,      // 接続直後（データ待機中）
        TIMEOUT,           // データ未受信・途絶
        CHECKSUM_ERROR,    // チェックサムエラー
        MALFORMED,         // データフォーマット異常
        NO_FIX             // 測位中（有効なFixなし）
    }

    private lateinit var connectionStatus: TextView
    private lateinit var mapManager: MapManager
    private val nmeaParser = NmeaParser()
    private lateinit var context: Context
    private var openedSettings = false
    private val dialogs = mutableListOf<AlertDialog>()
    private var selectionDialog: AlertDialog? = null

    private val usbHealth = ConnectionHealth()
    private val btHealth = ConnectionHealth()
    private var connectedBluetoothDeviceName: String? = null
    private var healthMonitorJob: Job? = null
    private lateinit var distanceFromShore: TextView
    private lateinit var swimStats: TextView
    private lateinit var mapView: MapView
    private lateinit var mainView: View
    private lateinit var overlayDrawable: Drawable
    private val STROKE_WIDTH = 12
    private lateinit var farWarningChannel: NotificationChannel
    private lateinit var weatherWarningChannel: NotificationChannel
    private lateinit var swimmingDetailChannel: NotificationChannel
    private lateinit var vibrationHelper: VibrationHelper
    private var isFarWarningActive: Boolean = false
    private var farWarningJob: Job? = null
    private lateinit var openSettingsButton: Button
    private var isMenuOpen: Boolean = false
    private lateinit var openMenuButton: ImageButton
    private lateinit var sideMenuView: LinearLayout
    private lateinit var sideMenuGroup: LinearLayout
    private lateinit var sideMenuContainer: LinearLayout
    private lateinit var jumpToMarkerButton: Button
    lateinit var joinPartyButton: Button
    lateinit var createPartyButton: Button
    lateinit var invitePartyButton: Button
    lateinit var leavePartyButton: Button
    private lateinit var partyMemberTextView: TextView
    private val partyMembers = linkedSetOf<String>()
    private val partyMemberNames = mutableMapOf<String, String>()

    private fun getMyUserName(): String {
        val prefs = getSharedPreferences("app_data", Context.MODE_PRIVATE)
        return prefs.getString("user_name", null)?.takeIf { it.isNotBlank() }
            ?: getString(R.string.party_default_member_name)
    }

    private fun isUserNameRegistered(): Boolean {
        val prefs = getSharedPreferences("app_data", Context.MODE_PRIVATE)
        return !prefs.getString("user_name", null).isNullOrBlank()
    }

    private fun ensureUserNameRegistered(
        title: String = getString(R.string.set_username),
        positiveButtonText: String = getString(R.string.ok),
        onDone: () -> Unit,
        onCancel: (() -> Unit)? = null
    ) {
        if (isUserNameRegistered()) {
            onDone()
        } else {
            showUserNameRegistrationDialog(
                title = title,
                positiveButtonText = positiveButtonText,
                onDone = onDone,
                onCancel = onCancel
            )
        }
    }

    private fun showUserNameRegistrationDialog(
        title: String = getString(R.string.set_username),
        positiveButtonText: String = getString(R.string.ok),
        onDone: (() -> Unit)? = null,
        onCancel: (() -> Unit)? = null
    ) {
        val currentName = getMyUserName().let {
            if (it == getString(R.string.party_default_member_name)) "" else it
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, (8 * resources.displayMetrics.density).toInt(), pad, 0)
        }

        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.username_hint)
            setText(currentName)
            setSelection(text.length)
            isSingleLine = true
        }
        container.addView(input)

        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(getString(R.string.username_dialog_message))
            .setView(container)
            .setPositiveButton(positiveButtonText) { _, _ ->
                val newName = input.text.toString().trim()
                val finalName =
                    if (newName.isNotBlank()) newName else currentName.ifBlank { "ユーザー" }
                val prefs = getSharedPreferences("app_data", Context.MODE_PRIVATE)
                prefs.edit {
                    putString("user_name", finalName)
                }
                PartyWebSocketManager.companion.USER_NAME = finalName
                val selfId = PartyWebSocketManager.companion.APP_ID
                if (!selfId.isNullOrEmpty()) {
                    partyMemberNames[selfId] = finalName
                }
                PartyWebSocketManager.companion.sendUserName(finalName)
                updatePartyMembersUI()
                onDone?.invoke()
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                onCancel?.invoke()
            }
            .setOnCancelListener {
                onCancel?.invoke()
            }
            .create()

        dialog.show()
    }

    // --- 遊泳トラッキング状態 ---
    private var isSwimmingActive: Boolean = false
    private var swimStartTimeMs: Long = 0L
    private var swimTotalDistanceMeters: Double = 0.0
    private var lastSwimLat: Double? = null
    private var lastSwimLon: Double? = null
    private var swimTimerJob: Job? = null
    private var outOfWaterCounter: Int = 0

    private var gpsService: GpsConnectionService? = null
    private var serviceBound = false

    lateinit var partyId: String

    // --- パーティ位置送信および受信機接続状態 ---
    private var lastKnownLatitude: Double? = null
    private var lastKnownLongitude: Double? = null
    private var partyLocationSenderJob: Job? = null

    private fun isReceiverConnected(): Boolean {
        val usbConnected = gpsService?.isUsbConnected ?: usbHealth.isConnected
        val btConnected = gpsService?.isBtConnected ?: btHealth.isConnected
        return usbConnected || btConnected
    }

    private fun startPartyLocationSender() {
        partyLocationSenderJob?.cancel()
        partyLocationSenderJob = lifecycleScope.launch {
            Log.d(TAG, "Party location sender loop started")
            while (isActive) {
                delay(5000L)
                val receiverConnected = isReceiverConnected()
                val wsConn = PartyWebSocketManager.companion.wsConnection
                val lat = lastKnownLatitude
                val lon = lastKnownLongitude
                /*
                Log.d(
                    TAG,
                    "Party location sender tick: isReceiverConnected=$receiverConnected, wsConnected=${wsConn != null}, lat=$lat, lon=$lon"
                )*/
                if (receiverConnected && wsConn != null) {
                    if (lat != null && lon != null && (lat != 0.0 || lon != 0.0)) {
                        val sent = PartyWebSocketManager.companion.sendLocation(lat, lon)
                        Log.d(
                            TAG,
                            "Party location broadcast result: success=$sent, lat=$lat, lon=$lon"
                        )
                    } else {
                        Log.w(TAG, "Party location sender: lat/lon is null or 0.0 ($lat, $lon)")
                    }
                }
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val localBinder = binder as GpsConnectionService.LocalBinder
            gpsService = localBinder.getService()
            gpsService?.setServiceListener(this@MainActivity)
            serviceBound = true
            Log.d(TAG, "GpsConnectionService bound")
            // バインド後に接続状態を同期して表示更新
            usbHealth.isConnected = gpsService?.isUsbConnected ?: false
            btHealth.isConnected = gpsService?.isBtConnected ?: false
            connectedBluetoothDeviceName = gpsService?.connectedBluetoothDeviceName
            updateOverallConnectionStatus()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            gpsService?.setServiceListener(null)
            gpsService = null
            serviceBound = false
            Log.d(TAG, "GpsConnectionService unbound")
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val btConnectGranted = grants[Manifest.permission.BLUETOOTH_CONNECT] ?: true
        val btScanGranted = grants[Manifest.permission.BLUETOOTH_SCAN] ?: true
        if (!btConnectGranted || !btScanGranted) {
            val dialog = AlertDialog.Builder(this)
                .setTitle(getString(R.string.needed_permission))
                .setMessage(getString(R.string.needed_nearby_permission))
                .setPositiveButton(getString(R.string.open_settings)) { _, _ ->
                    openedSettings = true
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                }
                .setNegativeButton(getString(R.string.close_app)) { _, _ ->
                    finish()
                }
                .create()
                .apply {
                    setCanceledOnTouchOutside(false)
                }
            dialogs.add(dialog)

            dialog.setOnDismissListener {
                dialogs.remove(dialog)
            }
            dialog.show()
        }
        grants[Manifest.permission.POST_NOTIFICATIONS]?.let { granted ->
            if (!granted) {
                val dialog = AlertDialog.Builder(this)
                    .setTitle(getString(R.string.needed_permission))
                    .setMessage(getString(R.string.needed_notification_permission))
                    .setPositiveButton(getString(R.string.open_settings)) { _, _ ->
                        openedSettings = true
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName")
                        )
                        startActivity(intent)
                    }
                    .setNegativeButton(getString(R.string.close_app)) { _, _ ->
                        finish()
                    }
                    .create()
                    .apply {
                        setCanceledOnTouchOutside(false)
                    }
                dialogs.add(dialog)

                dialog.setOnDismissListener {
                    dialogs.remove(dialog)
                }
                dialog.show()
            }
        }
        grants[Manifest.permission.ACCESS_FINE_LOCATION]?.let { granted ->
            if (!granted) {
                val dialog = AlertDialog.Builder(this)
                    .setTitle(getString(R.string.needed_permission))
                    .setMessage(getString(R.string.needed_location_permission))
                    .setPositiveButton(getString(R.string.open_settings)) { _, _ ->
                        openedSettings = true
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName")
                        )
                        startActivity(intent)
                    }
                    .setNegativeButton(getString(R.string.close_app)) { _, _ ->
                        finish()
                    }
                    .create()
                    .apply {
                        setCanceledOnTouchOutside(false)
                    }
                dialogs.add(dialog)

                dialog.setOnDismissListener {
                    dialogs.remove(dialog)
                }
                dialog.show()
            }
        }
    }

    private fun requestPermissions() {
        val permissions = buildList {
            if (!checkPermission.checkBluetoothPermission(context)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                    add(Manifest.permission.BLUETOOTH_SCAN)
                }
            }
            if (!checkPermission.checkNotificationPermission(context)) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (!checkPermission.checkLocationPermission(context)) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        if (permissions.isEmpty()) {
            setupClasses()
        } else {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            openQrScanner()
        } else {
            Snackbar.make(mainView, getString(R.string.needed_permission), Snackbar.LENGTH_LONG)
                .show()
        }
    }

    private fun openQrScanner() {
        showQrScannerDialog(
            this,
            this
        ) { qrText ->
            Log.d(TAG, "QR code scanned: $qrText")
            invitedFromLink(qrText)
        }
    }

    private fun requestCameraPermission() {
        if (!checkPermission.checkCameraPermission(context)) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /** パーティ未接続時のボタン状態に戻す（必ず UI スレッドから呼ぶこと） */
    private fun resetPartyButtons() {
        joinPartyButton.visibility = View.VISIBLE
        createPartyButton.visibility = View.VISIBLE
        invitePartyButton.visibility = View.GONE
        leavePartyButton.visibility = View.GONE
        joinPartyButton.isEnabled = true
        createPartyButton.isEnabled = true
    }

    /** パーティ接続時のボタン状態に切り替える（必ず UI スレッドから呼ぶこと） */
    private fun showPartyButtons() {
        joinPartyButton.visibility = View.GONE
        createPartyButton.visibility = View.GONE
        invitePartyButton.visibility = View.VISIBLE
        leavePartyButton.visibility = View.VISIBLE
        joinPartyButton.isEnabled = true
        createPartyButton.isEnabled = true
    }

    /** パーティメンバー表示の更新（必ず UI スレッドから呼ぶこと） */
    private fun updatePartyMembersUI() {
        runOnUiThread {
            if (!::partyMemberTextView.isInitialized) return@runOnUiThread
            if (partyMembers.isEmpty()) {
                partyMemberTextView.visibility = View.GONE
                partyMemberTextView.text = ""
            } else {
                val selfId = PartyWebSocketManager.companion.APP_ID
                val memberLines = partyMembers.map { memberId ->
                    val isSelf = memberId == selfId
                    val displayName = if (isSelf) {
                        getMyUserName()
                    } else {
                        partyMemberNames[memberId] ?: getString(R.string.party_default_member_name)
                    }
                    val roleLabel = when {
                        memberId == selfId && memberId == hostAppId -> " (${getString(R.string.party_role_host_you)})"
                        memberId == hostAppId -> " (${getString(R.string.party_role_host)})"
                        memberId == selfId -> " (${getString(R.string.party_role_you)})"
                        else -> ""
                    }
                    "• $displayName$roleLabel"
                }.joinToString("\n")

                val header = getString(R.string.party_members_header, partyMembers.size)
                partyMemberTextView.text = "$header\n$memberLines"
                partyMemberTextView.visibility = View.VISIBLE
            }
        }
    }

    /** パーティ接続コールバックの生成 */
    private fun createPartyConnectionCallback(isCreator: Boolean = false): PartyConnectionCallback {
        return object : PartyConnectionCallback {
            override fun onConnected() {
                runOnUiThread {
                    showPartyButtons()
                    if (isCreator) {
                        showInviteDialog()
                    }
                    val selfId = PartyWebSocketManager.companion.APP_ID
                    if (!selfId.isNullOrEmpty()) {
                        partyMembers.add(selfId)
                        partyMemberNames[selfId] = getMyUserName()
                    }
                    if (!hostAppId.isNullOrEmpty()) {
                        partyMembers.add(hostAppId!!)
                    }
                    PartyWebSocketManager.companion.sendUserName(getMyUserName())
                    updatePartyMembersUI()
                }
            }

            override fun onError(message: String) {
                Log.d(TAG, "Party connection error: $message")
                runOnUiThread {
                    partyMembers.clear()
                    partyMemberNames.clear()
                    updatePartyMembersUI()
                    mapManager.clearMemberLocations()
                    resetPartyButtons()
                    val title =
                        if (isCreator) getString(R.string.error_create_party_title) else getString(R.string.error_join_party_title)
                    val msg =
                        if (isCreator) getString(R.string.error_create_party_message) else getString(
                            R.string.error_join_party_message
                        )
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(title)
                        .setMessage(msg)
                        .setPositiveButton(getString(R.string.close), null)
                        .show()
                }
            }

            override fun onClosed() {
                runOnUiThread {
                    partyMembers.clear()
                    partyMemberNames.clear()
                    updatePartyMembersUI()
                    mapManager.clearMemberLocations()
                    resetPartyButtons()
                    Snackbar.make(
                        mainView,
                        getString(R.string.party_disconnected),
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            }

            override fun onMemberJoined(clientId: String) {
                val isSelf = clientId == PartyWebSocketManager.companion.APP_ID
                val displayName = if (isSelf) getMyUserName() else (partyMemberNames[clientId]
                    ?: getString(R.string.party_default_member_name))
                val msg = if (isSelf) {
                    getString(R.string.party_you_joined)
                } else {
                    getString(R.string.party_member_joined, displayName)
                }
                runOnUiThread {
                    partyMembers.add(clientId)
                    if (isSelf) {
                        partyMemberNames[clientId] = getMyUserName()
                    }
                    updatePartyMembersUI()
                    Snackbar.make(mainView, msg, Snackbar.LENGTH_SHORT).show()
                }
                PartyWebSocketManager.companion.sendUserName(getMyUserName())
            }

            override fun onMemberLeft(clientId: String) {
                val isSelf = clientId == PartyWebSocketManager.companion.APP_ID
                val displayName = if (isSelf) getMyUserName() else (partyMemberNames[clientId]
                    ?: getString(R.string.party_default_member_name))
                val msg = if (isSelf) {
                    getString(R.string.party_you_left)
                } else {
                    getString(R.string.party_member_left, displayName)
                }
                runOnUiThread {
                    partyMembers.remove(clientId)
                    partyMemberNames.remove(clientId)
                    mapManager.removeMemberLocation(clientId)
                    updatePartyMembersUI()
                    Snackbar.make(mainView, msg, Snackbar.LENGTH_SHORT).show()
                }
            }

            override fun onMemberNameUpdated(clientId: String, userName: String) {
                Log.d(TAG, "onMemberNameUpdated callback: clientId=$clientId, userName=$userName")
                runOnUiThread {
                    partyMemberNames[clientId] = userName
                    updatePartyMembersUI()
                }
                mapManager.updateMemberName(clientId, userName)
            }

            override fun onMembersUpdated(clientIds: List<String>) {
                Log.d(TAG, "Party members updated: ${clientIds.size}")
                runOnUiThread {
                    val removedMembers = partyMembers.filter { it !in clientIds }
                    for (removed in removedMembers) {
                        mapManager.removeMemberLocation(removed)
                        partyMemberNames.remove(removed)
                    }
                    partyMembers.clear()
                    partyMembers.addAll(clientIds)
                    updatePartyMembersUI()
                }
                PartyWebSocketManager.companion.sendUserName(getMyUserName())
            }

            override fun onMemberLocationUpdated(
                clientId: String,
                latitude: Double,
                longitude: Double
            ) {
                val selfId = PartyWebSocketManager.companion.APP_ID
                val isSelf = clientId == selfId
                Log.d(
                    TAG,
                    "onMemberLocationUpdated callback received: clientId=$clientId, selfId=$selfId, isSelf=$isSelf, lat=$latitude, lon=$longitude"
                )
                if (!isSelf) {
                    val displayName = partyMemberNames[clientId]
                    runOnUiThread {
                        if (!partyMembers.contains(clientId)) {
                            partyMembers.add(clientId)
                            updatePartyMembersUI()
                        }
                    }
                    mapManager.updateMemberLocation(clientId, latitude, longitude, displayName)
                }
            }
        }
    }

    private fun invitedFromLink(url: String) {
        try {
            val uri = Uri.parse(url)
            val parsedHostAppId =
                uri.getQueryParameter("hostAppId") ?: uri.getQueryParameter("hostClientId")
            val parsedPartyId = uri.getQueryParameter("partyId") ?: uri.getQueryParameter("roomId")

            if (!parsedHostAppId.isNullOrEmpty() && !parsedPartyId.isNullOrEmpty()) {
                ensureUserNameRegistered(
                    title = getString(R.string.join_party),
                    positiveButtonText = getString(R.string.join_party),
                    onDone = {
                        hostAppId = parsedHostAppId
                        partyId = parsedPartyId
                        partyMembers.clear()
                        partyMemberNames.clear()
                        updatePartyMembersUI()
                        // 接続中はボタンを無効化して二重タップを防止する
                        joinPartyButton.isEnabled = false
                        createPartyButton.isEnabled = false
                        lifecycleScope.launch {
                            PartyWebSocketManager.companion.join(
                                partyId,
                                hostAppId!!,
                                callback = createPartyConnectionCallback(isCreator = false)
                            )
                        }
                    },
                    onCancel = {
                        resetPartyButtons()
                    }
                )
            } else {
                Snackbar.make(mainView, getString(R.string.error_join_party), Snackbar.LENGTH_LONG)
                    .show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse invite link/QR: $url", e)
            Snackbar.make(mainView, getString(R.string.error_join_party), Snackbar.LENGTH_LONG)
                .show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        context = this
        MapLibre.getInstance(context)

        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.mapView)
        mapManager = MapManager(this, mapView)

        mapManager.initialize(savedInstanceState)
        mainView = findViewById(R.id.main)

        setupWindowInsets()

        connectionStatus = findViewById(R.id.connectionStatus)
        connectionStatus.setOnClickListener {
            if (gpsService != null) {
                showManualDeviceSelectionDialog()
            }
        }
        distanceFromShore = findViewById(R.id.distanceFromShore)
        swimStats = findViewById(R.id.swimStats)
        partyMemberTextView = findViewById(R.id.partyMember)

        overlayDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.TRANSPARENT) // 背景を透明に
            setStroke(STROKE_WIDTH, Color.BLUE) // 線の太さと色
        }

        mapView.post {
            overlayDrawable.setBounds(0, 0, mapView.width, mapView.height)
            mapView.overlay.clear()
            mapView.overlay.add(overlayDrawable)
        }
        vibrationHelper = VibrationHelper(this)
        val manager = getSystemService(NotificationManager::class.java)
        farWarningChannel = NotificationChannel(
            "farWarning",
            "岸から離れすぎたときの通知",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 150, 500, 150, 800)
        }


        openSettingsButton = findViewById<Button>(R.id.openSettings)
        openSettingsButton.setOnClickListener {
            showSideMenu()
            showSettings()
        }

        sideMenuGroup = findViewById(R.id.sideMenuGroup)
        sideMenuContainer = findViewById(R.id.sideMenuContainer)
        openMenuButton = findViewById(R.id.openSideMenu)

        openMenuButton.setOnClickListener { showSideMenu() }

        sideMenuGroup.post {
            sideMenuGroup.translationX = -sideMenuContainer.width.toFloat() - 30f
        }

        jumpToMarkerButton = findViewById(R.id.jumpToMarker)
        jumpToMarkerButton.setOnClickListener {
            showSideMenu()
            mapManager.jumpToMarker()
        }

        MapManager.attributionTextView = findViewById<TextView>(R.id.attribution)


        joinPartyButton = findViewById<Button>(R.id.joinParty)
        createPartyButton = findViewById<Button>(R.id.createParty)
        invitePartyButton = findViewById<Button>(R.id.inviteParty)
        leavePartyButton = findViewById<Button>(R.id.leaveParty)
        joinPartyButton.setOnClickListener {
            if (checkPermission.checkCameraPermission(context)) {
                openQrScanner()
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
        createPartyButton.setOnClickListener {
            ensureUserNameRegistered(
                title = getString(R.string.create_party),
                positiveButtonText = getString(R.string.create_party),
                onDone = {
                    // 接続中はボタンを無効化して二重タップを防止する
                    joinPartyButton.isEnabled = false
                    createPartyButton.isEnabled = false
                    hostAppId = PartyWebSocketManager.companion.APP_ID ?: ""
                    partyMembers.clear()
                    partyMemberNames.clear()
                    updatePartyMembersUI()
                    lifecycleScope.launch {
                        try {
                            val createdPartyId: String = PartyWebSocketManager.companion.create()
                            partyId = createdPartyId
                            join(
                                partyId,
                                hostAppId!!,
                                callback = createPartyConnectionCallback(isCreator = true)
                            )
                        } catch (e: Exception) {
                            // create() 自体が失敗した場合（ネットワークエラーなど）
                            Log.e(TAG, "Failed to create party: ${e.message}", e)
                            val message = e.message
                            runOnUiThread {
                                resetPartyButtons()
                                var msg = getString(R.string.error_create_party_message)
                                if (message.toString().startsWith("failed to connect to")) {
                                    msg += getString(R.string.error_failed_to_connect)
                                } else {
                                    msg += getString(R.string.error_unknown_message)
                                }
                                AlertDialog.Builder(this@MainActivity)
                                    .setTitle(getString(R.string.error_create_party_title))
                                    .setMessage(msg)
                                    .setPositiveButton(getString(R.string.close), null)
                                    .show()
                            }
                        }
                    }
                },
                onCancel = {
                    resetPartyButtons()
                }
            )
        }

        invitePartyButton.setOnClickListener {
            showSideMenu()
            showInviteDialog()
        }


        val prefs = context.getSharedPreferences("app_data", Context.MODE_PRIVATE)
        var id = prefs.getString("app_id", null)
        if (id == null) {
            val generatedId = generateRandomString()
            prefs.edit {
                putString("app_id", generatedId)
            }
            id = generatedId
        }
        PartyWebSocketManager.companion.APP_ID = id

        val savedUserName = prefs.getString("user_name", null)
        if (!savedUserName.isNullOrBlank()) {
            PartyWebSocketManager.companion.USER_NAME = savedUserName
            partyMemberNames[id] = savedUserName
        }

        val uri = intent.data
        if (uri != null) {
            invitedFromLink(uri.toString())
        }

        leavePartyButton.setOnClickListener {
            hostAppId = ""
            partyMembers.clear()
            partyMemberNames.clear()
            updatePartyMembersUI()
            mapManager.clearMemberLocations()
            lifecycleScope.launch {
                PartyWebSocketManager.companion.leave()
            }
            resetPartyButtons()
            Snackbar.make(mainView, getString(R.string.party_you_left), Snackbar.LENGTH_SHORT)
                .show()
        }
        weatherWarningChannel = NotificationChannel(
            "weatherWarning",
            "天気の通知",
            NotificationManager.IMPORTANCE_DEFAULT
        )

        swimmingDetailChannel = NotificationChannel(
            "swimmingDetail",
            "泳いでいるときの通知",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        manager.createNotificationChannel(farWarningChannel)
        manager.createNotificationChannel(weatherWarningChannel)
        manager.createNotificationChannel(swimmingDetailChannel)

        requestPermissions()

        startPartyLocationSender()
    }

    private fun setupClasses() {
        // GpsConnectionService をフォアグラウンドサービスとして起動してバインド
        val serviceIntent = Intent(context, GpsConnectionService::class.java)
        ContextCompat.startForegroundService(context, serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        Log.d(TAG, "GpsConnectionService start and bind initiated")
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, systemBars.top, 0, systemBars.bottom)
            insets
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED == intent.action) {
            gpsService?.connectUsb()
        }
        val uri = intent.data
        if (uri != null) {
            invitedFromLink(uri.toString())
        }
    }

    private fun ConnectionHealth.evaluateStatus(now: Long): HealthStatus {
        if (!isConnected) return HealthStatus.TIMEOUT

        val hasRecentValidLocation =
            (lastValidLocationTime > 0 && now - lastValidLocationTime < DATA_TIMEOUT_MS)

        // 1. 直近でエラーが発生している場合（まだ正常な位置情報が得られていない場合）
        if (lastChecksumErrorTime > 0 && now - lastChecksumErrorTime < ERROR_HOLD_MS && !hasRecentValidLocation) {
            return HealthStatus.CHECKSUM_ERROR
        }

        if (lastMalformedTime > 0 && now - lastMalformedTime < ERROR_HOLD_MS && !hasRecentValidLocation) {
            return HealthStatus.MALFORMED
        }

        // 2. データ受信の有無・タイムアウト判定
        if (lastDataReceivedTime == 0L) {
            return if (now - connectedAt < INITIAL_DATA_WAIT_MS) {
                HealthStatus.WAITING_DATA
            } else {
                HealthStatus.TIMEOUT
            }
        }

        if (now - lastDataReceivedTime >= DATA_TIMEOUT_MS) {
            return HealthStatus.TIMEOUT
        }

        // 3. 有効な位置情報が得られているか
        if (hasRecentValidLocation) {
            return HealthStatus.HEALTHY
        }

        // 4. データは届いているが衛星未捕捉 (No Fix)
        return HealthStatus.NO_FIX
    }

    private fun showSettings() {
        val padH = (24 * resources.displayMetrics.density).toInt()
        val padV = (16 * resources.displayMetrics.density).toInt()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, padV, padH, padV)
        }

        // --- ユーザー名設定セクション ---
        val userNameSectionTitle = TextView(this).apply {
            text = getString(R.string.username)
            textSize = 15f
            setTextColor(Color.BLACK)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, (6 * resources.displayMetrics.density).toInt())
        }
        layout.addView(userNameSectionTitle)

        val userNameRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (20 * resources.displayMetrics.density).toInt())
        }

        val userNameDisplay = TextView(this).apply {
            text = getMyUserName()
            textSize = 17f
            setTextColor(Color.DKGRAY)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        userNameRow.addView(userNameDisplay)

        val changeUserNameButton = MaterialButton(this).apply {
            text = getString(R.string.change)
            setOnClickListener {
                showUserNameRegistrationDialog(
                    title = getString(R.string.change_username),
                    positiveButtonText = getString(R.string.save),
                    onDone = {
                        userNameDisplay.text = getMyUserName()
                    }
                )
            }
        }
        userNameRow.addView(changeUserNameButton)
        layout.addView(userNameRow)

        // --- マップスタイルセクション ---
        val mapStyleSectionTitle = TextView(this).apply {
            text = getString(R.string.map_style)
            textSize = 15f
            setTextColor(Color.BLACK)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, (6 * resources.displayMetrics.density).toInt())
        }
        layout.addView(mapStyleSectionTitle)

        val mapStyleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val options =
            listOf(getString(R.string.map_default), getString(R.string.map_aerial_photograph))
        var selectedButton: Button? = null

        options.forEach { text ->
            val button = Button(this).apply {
                this.text = text

                setOnClickListener {
                    selectedButton?.setBackgroundColor(Color.LTGRAY)
                    if (this.text == getString(R.string.map_default)) {
                        mapManager.changeStyleToOSM(context)
                        mapManager.nowMapStyleType = "OSM"
                    }
                    if (this.text == getString(R.string.map_aerial_photograph)) {
                        mapManager.changeStyleToGSI(context)
                        mapManager.nowMapStyleType = "GSI"
                    }

                    setBackgroundColor(Color.YELLOW)
                    selectedButton = this
                }
            }
            if (text == getString(R.string.map_default) && mapManager.nowMapStyleType == "OSM") {
                selectedButton = button
                button.setBackgroundColor(Color.YELLOW)
            } else if (text == getString(R.string.map_aerial_photograph) && mapManager.nowMapStyleType == "GSI") {
                selectedButton = button
                button.setBackgroundColor(Color.YELLOW)
            } else {
                button.setBackgroundColor(Color.LTGRAY)
            }
            button.setTextColor(Color.BLACK)

            mapStyleRow.addView(
                button,
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    0.5f
                )
            )
        }
        layout.addView(mapStyleRow)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings))
            .setView(layout)
            .setPositiveButton(getString(R.string.close), null)
            .show()
    }

    private fun showSideMenu() {
        val targetX = if (!isMenuOpen) {
            0f
        } else {
            -sideMenuContainer.width.toFloat() - 30f
        }

        sideMenuGroup.animate()
            .translationX(targetX)
            .setDuration(300)
            .start()

        openMenuButton.setImageResource(
            if (!isMenuOpen) R.drawable.outline_close_24
            else R.drawable.outline_dehaze_24
        )

        isMenuOpen = !isMenuOpen
    }

    // --- 遊泳トラッキングロジック ---

    private fun formatElapsedTime(elapsedSeconds: Long): String {
        val hours = elapsedSeconds / 3600
        val minutes = (elapsedSeconds % 3600) / 60
        val seconds = elapsedSeconds % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    private fun formatDistance(meters: Double): String {
        return if (meters >= 1000.0) {
            String.format(Locale.getDefault(), "%.2f km", meters / 1000.0)
        } else {
            String.format(Locale.getDefault(), "%d m", meters.toInt())
        }
    }

    private fun startSwimSession(startLat: Double, startLon: Double) {
        isSwimmingActive = true
        swimStartTimeMs = System.currentTimeMillis()
        swimTotalDistanceMeters = 0.0
        lastSwimLat = startLat
        lastSwimLon = startLon
        outOfWaterCounter = 0

        mapManager.clearTrack()
        mapManager.addTrackPoint(startLat, startLon)

        startSwimTimer()
        updateSwimNotificationAndUI()
    }

    private fun updateSwimProgress(lat: Double, lon: Double) {
        outOfWaterCounter = 0
        val prevLat = lastSwimLat
        val prevLon = lastSwimLon
        if (prevLat != null && prevLon != null) {
            val dist = calculateDistanceBetween(prevLat, prevLon, lat, lon)
            // 0.5m 〜 50m の妥当な移動（静止時のGPSジッターやテレポートを除外）を加算
            if (dist in 0.5f..50.0f) {
                swimTotalDistanceMeters += dist
                lastSwimLat = lat
                lastSwimLon = lon
                mapManager.addTrackPoint(lat, lon)
            }
        } else {
            lastSwimLat = lat
            lastSwimLon = lon
            mapManager.addTrackPoint(lat, lon)
        }
        updateSwimNotificationAndUI()
    }

    private fun checkSwimExit() {
        outOfWaterCounter++
        if (outOfWaterCounter >= OUT_OF_WATER_TOLERANCE) {
            finishSwimSession()
        }
    }

    private fun finishSwimSession() {
        if (!isSwimmingActive) return
        isSwimmingActive = false
        stopFarShoreWarning()
        swimTimerJob?.cancel()
        swimTimerJob = null

        // フォアグラウンドサービスの通知を通常の接続ステータスに戻す
        gpsService?.updateSwimStatus(false)

        val elapsedSec = (System.currentTimeMillis() - swimStartTimeMs) / 1000
        val timeStr = formatElapsedTime(elapsedSec)
        val distStr = formatDistance(swimTotalDistanceMeters)
        val notifMessage = getString(R.string.swim_finished_text, timeStr, distStr)

        // 遊泳完了通知（消去可能通知）
        sendNotification(
            context = context,
            channelid = "swimmingDetail",
            title = getString(R.string.swim_finished_title),
            message = notifMessage,
            isOnGoing = false,
            notifyId = SWIM_NOTIFICATION_ID
        )

        runOnUiThread {
            if (::swimStats.isInitialized) {
                swimStats.text = "${getString(R.string.swim_finished_title)}: $timeStr / $distStr"
            }
        }
    }

    private fun startSwimTimer() {
        swimTimerJob?.cancel()
        swimTimerJob = lifecycleScope.launch {
            while (isActive && isSwimmingActive) {
                updateSwimNotificationAndUI()
                delay(1000L)
            }
        }
    }

    private fun updateSwimNotificationAndUI() {
        if (!isSwimmingActive) return
        val elapsedSec = (System.currentTimeMillis() - swimStartTimeMs) / 1000
        val timeStr = formatElapsedTime(elapsedSec)
        val distStr = formatDistance(swimTotalDistanceMeters)

        // フォアグラウンドサービス（Ongoing通知）に遊泳ステータスを紐づけて反映
        if (gpsService != null) {
            gpsService?.updateSwimStatus(true, timeStr, distStr)
        } else {
            val notifMessage = getString(R.string.swim_notification_text, timeStr, distStr)
            sendNotification(
                context = context,
                channelid = "swimmingDetail",
                title = getString(R.string.swim_notification_title),
                message = notifMessage,
                isOnGoing = true,
                notifyId = SWIM_NOTIFICATION_ID
            )
        }

        runOnUiThread {
            if (::swimStats.isInitialized) {
                swimStats.text = getString(R.string.swim_stats_display, timeStr, distStr)
                swimStats.visibility = View.VISIBLE
            }
        }
    }

    private fun handleNmeaDetail(detail: NmeaParseDetail, isUsb: Boolean) {
        val health = if (isUsb) usbHealth else btHealth
        val now = System.currentTimeMillis()
        health.lastDataReceivedTime = now

        when (detail) {
            is NmeaParseDetail.LocationUpdate -> {
                health.lastValidLocationTime = now
                val lat = detail.location.latitude
                val lon = detail.location.longitude
                lastKnownLatitude = lat
                lastKnownLongitude = lon

                val distance = calculateDistance(context, lat, lon)
                val inWater = isSwimming(context, lat, lon)

                runOnUiThread {
                    // 遊泳トラッキングの判定・更新（UIスレッドで安全に実行）
                    if (inWater) {
                        if (!isSwimmingActive) {
                            startSwimSession(lat, lon)
                        } else {
                            updateSwimProgress(lat, lon)
                        }
                    } else {
                        if (isSwimmingActive) {
                            checkSwimExit()
                        }
                    }

                    mapManager.updateLocation(
                        latitude = lat,
                        longitude = lon
                    )
                    if (::distanceFromShore.isInitialized) {
                        val distancestr = distance.toInt().toString()
                        distanceFromShore.text = distancestr + "m"
                        distanceFromShore.visibility = View.VISIBLE
                        var color = Color.rgb(0, 75, 175)
                        if (inWater) {
                            color = when {
                                distance < 20.0 -> Color.GREEN
                                distance < FAR_SHORE_THRESHOLD_METERS -> Color.rgb(
                                    255,
                                    165,
                                    0
                                ) // オレンジ
                                else -> Color.RED
                            }
                        }
                        (overlayDrawable.mutate() as? GradientDrawable)?.setStroke(
                            STROKE_WIDTH,
                            color
                        )

                        // 岸から30m離れたときの警告（通知＋20秒周期のはっきりとした振動）
                        if (inWater && distance >= FAR_SHORE_THRESHOLD_METERS) {
                            startFarShoreWarning()
                        } else {
                            stopFarShoreWarning()
                        }
                    }
                }
            }

            is NmeaParseDetail.NoFix -> {
                health.lastNoFixTime = now
            }

            is NmeaParseDetail.InvalidChecksum -> {
                health.lastChecksumErrorTime = now
                Log.w(
                    TAG,
                    "NMEA Checksum error (${if (isUsb) "USB" else "BT"}): ${detail.rawSentence}"
                )
            }

            is NmeaParseDetail.Malformed -> {
                health.lastMalformedTime = now
                Log.w(
                    TAG,
                    "NMEA Malformed sentence (${if (isUsb) "USB" else "BT"}): ${detail.rawSentence}"
                )
            }

            is NmeaParseDetail.Unsupported -> {
                // GSV, GSA などの補助センテンス（正常ストリームの一部）
            }
        }
    }

    private fun startHealthMonitor() {
        healthMonitorJob?.cancel()
        healthMonitorJob = lifecycleScope.launch {
            while (isActive) {
                delay(1000)
                updateOverallConnectionStatus()
            }
        }
    }

    private fun stopHealthMonitor() {
        healthMonitorJob?.cancel()
        healthMonitorJob = null
    }

    // --- UsbSerialListener 実装 ---

    override fun onConnected() {
        usbHealth.onConnected()
        updateOverallConnectionStatus()
    }

    override fun onDisconnected() {
        usbHealth.onDisconnected()
        updateOverallConnectionStatus()
    }

    override fun onRawDataReceived(data: ByteArray) {
        val details = nmeaParser.parseRawDataWithDetails(data)
        for (detail in details) {
            handleNmeaDetail(detail, isUsb = true)
        }
        updateOverallConnectionStatus()
    }

    override fun onError(exception: Exception) {
        usbHealth.onDisconnected()
        updateOverallConnectionStatus()
    }

    // --- BluetoothGpsListener 実装 ---

    override fun onBluetoothConnected(device: BluetoothDevice) {
        btHealth.onConnected()
        connectedBluetoothDeviceName = try {
            device.name ?: device.address
        } catch (e: Exception) {
            device.address
        }
        Log.d(TAG, "Bluetooth GNSS connected: $connectedBluetoothDeviceName")
        updateOverallConnectionStatus()
    }

    override fun onBluetoothDisconnected() {
        btHealth.onDisconnected()
        connectedBluetoothDeviceName = null
        Log.d(TAG, "Bluetooth GNSS disconnected")
        updateOverallConnectionStatus()
    }

    override fun onBluetoothError(exception: Exception) {
        btHealth.onDisconnected()
        connectedBluetoothDeviceName = null
        Log.e(TAG, "Bluetooth GNSS error: ${exception.message}", exception)
        updateOverallConnectionStatus()
    }

    override fun onRawNmeaReceived(line: String) {
        btHealth.lastDataReceivedTime = System.currentTimeMillis()
    }

    override fun onNmeaParseDetail(detail: NmeaParseDetail) {
        handleNmeaDetail(detail, isUsb = false)
        updateOverallConnectionStatus()
    }

    override fun onLocationReceived(location: GpsLocation) {
        lastKnownLatitude = location.latitude
        lastKnownLongitude = location.longitude
        // handleNmeaDetail で処理されるためここでは追加処理なし
    }

    override fun onMultipleDevicesFound(devices: List<DiscoveredBluetoothDevice>) {
        runOnUiThread {
            showDeviceSelectionDialog(devices)
        }
    }

    override fun onDiscoveryFinished(devices: List<DiscoveredBluetoothDevice>) {
        Log.d(TAG, "Bluetooth discovery finished with ${devices.size} devices")
    }

    /**
     * 検出されたBluetoothデバイス（電波強度順）を選択するためのダイアログを表示します。
     */
    private fun showDeviceSelectionDialog(devices: List<DiscoveredBluetoothDevice>) {
        selectionDialog?.dismiss()

        if (devices.isEmpty()) {
            val dialog = AlertDialog.Builder(this)
                .setTitle(getString(R.string.select_bluetooth_device))
                .setMessage(getString(R.string.no_device_found))
                .setPositiveButton(getString(R.string.rescan)) { _, _ ->
                    gpsService?.startBluetoothDiscovery(autoConnect = false)
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .create()
            dialogs.add(dialog)
            dialog.setOnDismissListener { dialogs.remove(dialog) }
            dialog.show()
            selectionDialog = dialog
            return
        }

        val itemLabels = devices.map { dev ->
            val signalStrength = when {
                dev.rssi >= -60 -> "強"
                dev.rssi >= -80 -> "中"
                dev.rssi > -100 -> "弱"
                else -> "未測定"
            }
            val rssiText =
                if (dev.rssi > -100) "${dev.rssi} dBm ($signalStrength)" else "ペアリング済"
            "${dev.name}\n${dev.address}  [$rssiText]"
        }.toTypedArray()

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.select_bluetooth_device))
            .setItems(itemLabels) { _, which ->
                val selected = devices[which]
                Log.d(TAG, "User selected Bluetooth device: ${selected.name} (${selected.address})")
                gpsService?.connectBluetooth(selected.device)
            }
            .setPositiveButton(getString(R.string.rescan)) { _, _ ->
                gpsService?.startBluetoothDiscovery(autoConnect = false)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .create()

        dialogs.add(dialog)
        dialog.setOnDismissListener {
            dialogs.remove(dialog)
            selectionDialog = null
        }
        dialog.show()
        selectionDialog = dialog
    }

    /**
     * 手動でBluetoothデバイス選択ダイアログを開き、最新の一覧表示とスキャンを行います。
     */
    private fun showManualDeviceSelectionDialog() {
        val currentDevices = gpsService?.getSortedDiscoveredDevices() ?: emptyList()
        showDeviceSelectionDialog(currentDevices)
        gpsService?.startBluetoothDiscovery(autoConnect = false)
    }

    /**
     * USBおよびBluetoothの接続・受信状況（およびエラー警告）に応じてステータス表示を更新します。
     */
    private fun updateOverallConnectionStatus() {
        val now = System.currentTimeMillis()
        val isUsbConnected = usbHealth.isConnected
        val isBtConnected = btHealth.isConnected

        runOnUiThread {
            if (!::connectionStatus.isInitialized) return@runOnUiThread

            if (!isUsbConnected && !isBtConnected) {
                connectionStatus.setBackgroundColor(
                    ContextCompat.getColor(
                        this,
                        R.color.disconnected
                    )
                )
                connectionStatus.text = getString(R.string.disconnected)
                return@runOnUiThread
            }

            val btName = connectedBluetoothDeviceName ?: "BT"
            val btStatus = if (isBtConnected) btHealth.evaluateStatus(now) else null
            val usbStatus = if (isUsbConnected) usbHealth.evaluateStatus(now) else null

            val hasWarning = (btStatus != null && btStatus != HealthStatus.HEALTHY) ||
                    (usbStatus != null && usbStatus != HealthStatus.HEALTHY)

            val colorRes = if (hasWarning) R.color.warning else R.color.connected
            connectionStatus.setBackgroundColor(ContextCompat.getColor(this, colorRes))

            val statusText = when {
                isUsbConnected && isBtConnected -> {
                    val usbText = formatSourceStatus("USB", usbStatus ?: HealthStatus.TIMEOUT)
                    val btText = formatSourceStatus("BT: $btName", btStatus ?: HealthStatus.TIMEOUT)
                    if (!hasWarning) {
                        getString(R.string.connected_both, btName)
                    } else {
                        "$usbText | $btText"
                    }
                }

                isUsbConnected -> {
                    formatSourceStatus(
                        getString(R.string.connected_usb),
                        usbStatus ?: HealthStatus.TIMEOUT
                    )
                }

                isBtConnected -> {
                    val baseName = if (connectedBluetoothDeviceName != null) {
                        getString(R.string.connected_bluetooth, connectedBluetoothDeviceName)
                    } else {
                        getString(R.string.connected_bluetooth_simple)
                    }
                    formatSourceStatus(baseName, btStatus ?: HealthStatus.TIMEOUT)
                }

                else -> getString(R.string.disconnected)
            }
            connectionStatus.text = statusText
        }
    }

    private fun formatSourceStatus(name: String, status: HealthStatus): String {
        return when (status) {
            HealthStatus.HEALTHY -> name
            HealthStatus.WAITING_DATA -> getString(R.string.status_warning_no_data, name)
            HealthStatus.TIMEOUT -> getString(R.string.status_warning_timeout, name)
            HealthStatus.CHECKSUM_ERROR -> getString(R.string.status_warning_checksum, name)
            HealthStatus.MALFORMED -> getString(R.string.status_warning_malformed, name)
            HealthStatus.NO_FIX -> getString(R.string.status_warning_no_fix, name)
        }
    }

    /**
     * 岸から30m以上離れた際の警告（通知および20秒ごとの明確な振動）を開始します。
     */
    private fun startFarShoreWarning() {
        if (isFarWarningActive) return
        isFarWarningActive = true
        farWarningJob?.cancel()
        farWarningJob = lifecycleScope.launch {
            while (isActive && isFarWarningActive) {
                sendNotification(
                    context = context,
                    channelid = "farWarning",
                    title = getString(R.string.far_warning_message),
                    message = getString(R.string.far_warning_description),
                    isOnGoing = true,
                    notifyId = FAR_WARNING_NOTIFICATION_ID
                )
                vibrationHelper.vibrateDistinctWarning()
                delay(FAR_WARNING_INTERVAL_MS)
            }
        }
    }

    /**
     * 岸から30m以内に戻った場合や遊泳終了時に警告を停止し、通知を消去します。
     */
    private fun stopFarShoreWarning() {
        if (!isFarWarningActive && farWarningJob == null) return
        isFarWarningActive = false
        farWarningJob?.cancel()
        farWarningJob = null
        vibrationHelper.cancel()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(FAR_WARNING_NOTIFICATION_ID)
    }

    // --- ライフサイクル委譲 ---

    override fun onStart() {
        super.onStart()
        mapManager.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapManager.onResume()
        startHealthMonitor()
        if (openedSettings) {
            openedSettings = false
            if (!checkPermission.checkLocationPermission(context)) {
                requestPermissions()
                return
            }
            if (!checkPermission.checkBluetoothPermission(context)) {
                requestPermissions()
                return
            }
            if (!checkPermission.checkNotificationPermission(context)) {
                requestPermissions()
                return
            }
            dialogs.forEach {
                if (it.isShowing) {
                    it.dismiss()
                }
            }

            dialogs.clear()
        }
    }

    override fun onPause() {
        super.onPause()
        stopHealthMonitor()
        mapManager.onPause()
    }

    override fun onStop() {
        super.onStop()
        mapManager.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        partyLocationSenderJob?.cancel()
        partyLocationSenderJob = null
        stopFarShoreWarning()
        selectionDialog?.dismiss()
        selectionDialog = null
        swimTimerJob?.cancel()
        swimTimerJob = null
        // サービスのバインドを解除（未接続かつアプリ終了時はサービスも停止）
        if (serviceBound) {
            if (isFinishing) {
                gpsService?.stopServiceIfDisconnected()
            }
            gpsService?.setServiceListener(null)
            unbindService(serviceConnection)
            serviceBound = false
        } else if (isFinishing) {
            stopService(Intent(this, GpsConnectionService::class.java))
        }
        mapManager.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapManager.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapManager.onSaveInstanceState(outState)
    }

    fun generateRandomString(length: Int = 16): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val random = SecureRandom()

        return buildString(length) {
            repeat(length) {
                append(chars[random.nextInt(chars.length)])
            }
        }
    }

    fun generateQRCode(text: String, width: Int, height: Int): Bitmap? {
        return try {
            val bitMatrix: BitMatrix = MultiFormatWriter().encode(
                text,
                BarcodeFormat.QR_CODE,
                width,
                height
            )

            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bmp.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bmp
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun showInviteDialog() {
        val display: Display = this.windowManager.defaultDisplay
        val point = Point()
        display.getSize(point)
        val bitmapSize = (point.x * 0.6).toInt()
        val shareButtonSize = (point.x * 0.8).toInt()
        val shareUrl =
            "https://orenzikun722.github.io/biwaswim-page/invite.html?partyId=$partyId&hostAppId=$hostAppId"
        val bitmap = generateQRCode(shareUrl, bitmapSize, bitmapSize)


        val imageView = ImageView(context).apply {
            setImageBitmap(bitmap)

            val padding = 50
            setPadding(padding, padding, padding, padding)
        }
        val shareButton = MaterialButton(context).apply {
            icon = getDrawable(R.drawable.baseline_share_24)
            text = getString(R.string.invite_with_link)
            setPadding(20, 0, 20, 0)
            layoutParams = ViewGroup.LayoutParams(
                shareButtonSize,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        shareButton.setOnClickListener {
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, getString(R.string.invite_message, shareUrl))
                type = "text/plain"
            }
            val shareIntent = Intent.createChooser(sendIntent, null)
            startActivity(shareIntent)
        }
        val linearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(imageView)
            addView(shareButton)
        }

        android.app.AlertDialog.Builder(this)
            .setView(linearLayout)
            .setPositiveButton(getString(R.string.close)) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    fun showQrScannerDialog(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        onQrDetected: (String) -> Unit
    ) {
        val view = LayoutInflater.from(context)
            .inflate(R.layout.dialog_qrcode_scanner, null)

        val previewView = view.findViewById<PreviewView>(R.id.previewView)

        val dialog = AlertDialog.Builder(context)
            .setTitle(getString(R.string.scan_qrcode))
            .setView(view)
            .setNegativeButton(getString(R.string.cancel), null)
            .create()

        dialog.setOnShowListener {
            startCamera(
                context,
                lifecycleOwner,
                previewView
            ) { result ->

                onQrDetected(result)

                dialog.dismiss()
            }
        }

        dialog.show()
    }

    fun startCamera(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onQrDetected: (String) -> Unit
    ) {
        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({

            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.surfaceProvider =
                        previewView.surfaceProvider
                }

            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_QR_CODE
                )
                .build()

            val scanner =
                BarcodeScanning.getClient(options)

            var detected = false

            val imageAnalysis =
                ImageAnalysis.Builder()
                    .build()

            imageAnalysis.setAnalyzer(
                ContextCompat.getMainExecutor(context)
            ) { imageProxy ->

                if (detected) {
                    imageProxy.close()
                    return@setAnalyzer
                }

                val mediaImage = imageProxy.image

                if (mediaImage != null) {
                    val image = InputImage.fromMediaImage(
                        mediaImage,
                        imageProxy.imageInfo.rotationDegrees
                    )

                    scanner.process(image)
                        .addOnSuccessListener { barcodes ->

                            for (barcode in barcodes) {
                                val value = barcode.rawValue

                                if (value != null && !detected) {
                                    detected = true
                                    onQrDetected(value)
                                }
                            }
                        }
                        .addOnCompleteListener {
                            imageProxy.close()
                        }
                } else {
                    imageProxy.close()
                }
            }

            val cameraSelector =
                CameraSelector.DEFAULT_BACK_CAMERA

            cameraProvider.unbindAll()

            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis
            )

        }, ContextCompat.getMainExecutor(context))
    }
}