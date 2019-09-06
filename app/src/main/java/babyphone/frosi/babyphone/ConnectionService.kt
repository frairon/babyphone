package babyphone.frosi.babyphone

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.*
import android.provider.Settings
import android.text.TextUtils
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.codebutler.android_websockets.WebSocketClient
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.json.JSONObject
import org.threeten.bp.Duration
import org.threeten.bp.Instant
import java.io.Serializable
import java.net.URI
import java.nio.charset.Charset
import java.util.*
import kotlin.math.roundToInt


class Point(val time: Date, val volume: Int) : Serializable {
}

class History(private val maxSize: Int) {

    var alarms: MutableList<Instant> = ArrayList<Instant>()
        private set

    var volumes: MutableList<Point> = ArrayList<Point>()
        private set

    var movements: MutableList<Point> = ArrayList<Point>()
        private set


    fun addVolume(vol: Point) {
        volumes.add(vol)
        volumes.sortBy { vol.time }
        val earliest = Date(Instant.now().minus(Duration.ofMinutes(5)).toEpochMilli())
        this.volumes = this.volumes.filter { vol -> vol.time.after(earliest) }.toMutableList()
    }

    fun addMovement(mov: Point) {
        movements.add(mov)
        movements.sortBy { mov.time }
        val earliest = Date(Instant.now().minus(Duration.ofMinutes(5)).toEpochMilli())
        this.movements = this.movements.filter { mov -> mov.time.after(earliest) }.toMutableList()
    }

    fun clear() {
        this.alarms.clear()
        this.volumes.clear()
        this.movements.clear()
    }

    fun averageVolumeSince(past: Duration): Int {
        var sum = 0
        var count = 0
        var start = Instant.now().minus(past)
        for (vol: Point in volumes.reversed()) {
            // TODO: replace Date with instant in volume
            if (vol.time.time < start.toEpochMilli()) {
                break
            }
            sum += vol.volume
            count++
        }

        return sum / count
    }

    fun alarmsSince(past: Duration): Int {
        var start = Instant.now().minus(past)
        return alarms.filter { a -> a.isAfter(start) }.size
    }

    fun timesAboveThreshold(past: Duration, threshold: Int): Int {

        var start = Instant.now().minus(past)
        return volumes.filter { vol -> vol.time.time > start.toEpochMilli() && vol.volume > threshold }.size
    }

    fun triggerAlarm() {
        alarms.add(Instant.now())
        if (alarms.size > 100) {
            alarms = alarms.drop(alarms.size - 100) as MutableList
        }
    }

    fun timeSinceAlarm(): Duration {
        if (alarms.size == 0) {
            return Duration.ofDays(1000)
        }
        return Duration.between(alarms.lastOrNull()!!, Instant.now())
    }


    fun getAutoVolumeThreshold(): Int {
        if (volumes.size < 10) {
            return 50
        }
        return (volumes.map({ vol -> vol.volume.toDouble() }).average() + 10.0).roundToInt()
    }
}

class HeartbeatWatcher(private val notifier: ((LongArray) -> Unit)) {

    private var handler = Handler()

    private var lastBeat: Instant = Instant.now()
    private var alarmSkip: Duration = Duration.ofMillis(3000)

    private var running: Boolean = false

    fun start() {
        running = true
        lastBeat = Instant.now()
        schedule()
    }

    private fun schedule() {
        handler.postDelayed({
            check()
        }, 20000)
    }

    private fun check() {
        // stopped while we were sleeping
        if (!running) {
            return
        }

        if (Duration.between(lastBeat, Instant.now()) > alarmSkip) {
            notifier(arrayOf(0, 150L, 150L, 150L).toLongArray())
        }
        // schedule to run again
        schedule()
    }

    fun heartbeat() {
        lastBeat = Instant.now()
    }

    fun stop() {
        running = false
    }
}

class ConnectionService : Service(), WebSocketClient.Listener {

    private val mBinder = ConnectionServiceBinder()
    private var mWebSocketClient: WebSocketClient? = null

    private var heartbeat: HeartbeatWatcher = HeartbeatWatcher(::vibrate)


    var currentDevice: Device? = null
        get() = field
        private set(value) {
            field = value
        }

    var volumeThreshold: Int = 50
    val history = History(Babyphone.MAX_GRAPH_ELEMENTS)

    var handler = Handler()
    var reconnectScheduled: Boolean = false


    var lights: Boolean = false
        set(value) {
            field = value
            val data = JSONObject();
            data.put("action", if (value) "lightson" else "lightsoff");
            this.mWebSocketClient?.send(data.toString())
        }

    var alarmsEnabled: Boolean = true

    var autoVolumeLevel: Boolean = false

    fun getMotionUrl(): String? {
        if (currentDevice == null || currentDevice?.hostname == "") {
            return null
        }
        return "http://${currentDevice?.hostname}:8081/latest"
    }

    var connectionState: ConnectionState = ConnectionState.Disconnected
        private set(value) {
            Log.d(TAG, "Setting connection state $value")
            field = value
            EventBus.getDefault().post(ConnectionStateUpdated(value, currentDevice))
        }

    enum class ConnectionState(val action: String) {
        Disconnected("disconnected"),
        Connecting("connecting"),
        Connected("connected");

        companion object {
            fun findState(action: String): ConnectionState? {
                for (value: ConnectionState in ConnectionState.values()) {
                    if (value.action == action) {
                        return value
                    }
                }
                return null
            }
        }
    }

    private val mMessageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val networkIsOn = intent.getBooleanExtra(ACTION_NETWORK_STATE_CHANGED, false)
//            if (networkIsOn) {
//                startSocket()
//            } else {
//                stopSocket()
//            }
        }
    }

    init {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, getString(R.string.nfChannelName), importance);
            channel.description = getString(R.string.nfChannelDesc);
            channel.enableVibration(true)
            channel.enableLights(true)
            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    fun connect(device: Device, reconnect: Boolean = true) {
        this.currentDevice = device
        Log.i(TAG, "configuring websocket-uri ${this.currentDevice?.hostname}")

        this.startForeground()
        this.heartbeat.start()

        startSocket(reconnect)
    }

    fun disconnect() {
        Log.i(TAG, "service disconnect requested")
        this.currentDevice = null
        this.stopForeground(true)
        stopSocket()
        this.heartbeat.stop()

        Log.i(TAG, "removing notification")
        NotificationManagerCompat.from(this).cancel(NOTI_SERVICE_ID)
    }

    private fun shouldConnect(): Boolean {
        return this.currentDevice != null
    }

    var mRunning = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onstartcommand")
        if (mRunning) {
            Log.i(TAG, "service already running")
        }
        mRunning = true
        return START_STICKY
    }


    fun shutdown() {
        val data = JSONObject();
        data.put("action", "shutdown");
        this.mWebSocketClient?.send(data.toString())
    }

    fun restart() {
        val data = JSONObject();
        data.put("action", "restart");
        this.mWebSocketClient?.send(data.toString())
    }

    private fun createNotification(modify: ((NotificationCompat.Builder) -> Unit)?): Notification {
        val showBabyphone = Intent(this, Babyphone::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val stackBuilder = TaskStackBuilder.create(this)
        stackBuilder.addNextIntentWithParentStack(showBabyphone)
        val pshowBabyphone = stackBuilder.getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT)

        val icon = BitmapFactory.decodeResource(resources,
                R.mipmap.ic_launcher_round)

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(getString(R.string.nfTitle))
                .setSmallIcon(R.mipmap.ic_launcher_round)
                .setAutoCancel(true)
                .setLargeIcon(
                        Bitmap.createScaledBitmap(icon, 128, 128, false))
                .setContentIntent(pshowBabyphone)


        if (modify != null) {
            modify(builder)
        }
        return builder.build()
    }

    private fun startForeground() {
        startForeground(NOTI_SERVICE_ID, this.createNotification(null))
    }

    override fun onBind(intent: Intent): IBinder? {
        Log.i(TAG, "onBind")
        LocalBroadcastManager.getInstance(this).registerReceiver(mMessageReceiver, IntentFilter(ConnectionService.ACTION_NETWORK_STATE_CHANGED))
        return mBinder
    }

    override fun onUnbind(intent: Intent): Boolean {
        Log.i(TAG, "onUnbind")
        return true
    }


    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mMessageReceiver)
        stopForeground(true)
        currentDevice = null
        stopSocket()
        stopSelf()
        EventBus.getDefault().unregister(this)
        super.onDestroy()
    }

    @Subscribe(threadMode = ThreadMode.POSTING)
    fun onStreamAction(sa: StreamAction) {
        val data = JSONObject();
        if (sa.action == StreamAction.Action.Start) {
            data.put("action", "_startstream");
        } else {
            data.put("action", "_stopstream");
        }
        Log.i(TAG, "sending to socket" + data.toString())
        this.mWebSocketClient?.send(data.toString())
    }

    override fun onCreate() {
        Log.i(TAG, "onCreate")
        EventBus.getDefault().register(this)
        LocalBroadcastManager.getInstance(this).registerReceiver(mMessageReceiver, IntentFilter(ConnectionService.ACTION_NETWORK_STATE_CHANGED))
    }

    override fun onConnect() {
        Log.i(TAG, "Websocket onConnect()")
        connectionState = ConnectionState.Connected
        this.history.clear()
        doNotify({ x -> this.addConnectionState(x) })
    }

    override fun onMessage(message: String) {
        parseAndSendAction(message)
    }

    override fun onMessage(data: ByteArray) {
        parseAndSendAction(String(data, Charset.defaultCharset()))
    }

    private fun parseAndSendAction(message: String) {
        val parsed = JSONObject(message)

        when (parsed.optString("action")) {
            "volume" -> {
                val volume = parsed.optDouble("volume")
                val vol = Point(Date(), (volume * 100.0).toInt())
                history.addVolume(vol)
                this.handleVolume(vol)
                sendAction(ACTION_VOLUME_RECEIVED, { intent -> intent.putExtra(ACTION_EXTRA_VOLUME, vol) })
            }
            "movement" -> {
                val movement = parsed.optDouble("value")
                val mov = Point(Date(), (movement * 100.0).toInt())
                history.addMovement(mov)
                sendAction(ACTION_MOVEMENT_RECEIVED, { intent ->
                    intent.putExtra(ACTION_EXTRA_MOVEMENT, mov)
                    intent.putExtra(ACTION_EXTRA_MOVEMENT_MOVED, parsed.optBoolean("moved"))
                })
                if (parsed.optBoolean("moved")) {
                    vibrate(arrayOf(0L, 150L).toLongArray())
                }
            }
            "heartbeat" -> {
                heartbeat.heartbeat()
            }
            "vframe" -> {
                val type = VideoFrame.Type.fromInt(parsed.optInt("type"))
                val offset = parsed.optInt("offset")
                val timestamp = parsed.optLong("time")
                val partial = parsed.optBoolean("partial")
                val data = parsed.optString("data")
                val dataBytes = Base64.decode(data, Base64.DEFAULT)
                EventBus.getDefault().post(VideoFrame(
                        dataBytes,
                        offset,
                        timestamp,
                        type,
                        partial
                ))
            }
            "systemstatus" -> {
                val status = parsed.optString("status")
                when (status) {
                    "shutdown" -> disconnect()
                    "restart" -> {
                    } // do nothing on restart, we'll just try to reconnect
                }
            }
            else ->
                Log.d(TAG, "unhandled message " + parsed)
        }
    }


    private fun vibrate(pattern: LongArray) {
        if (Build.VERSION.SDK_INT >= 26) {
            (getSystemService(VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createWaveform(pattern, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            (getSystemService(VIBRATOR_SERVICE) as Vibrator).vibrate(pattern, -1)
        }
    }

    private fun doNotify(modify: ((NotificationCompat.Builder) -> Unit)? = null, isAlarm: Boolean = false) {
        val notification = this.createNotification(modify)
        NotificationManagerCompat.from(this).notify(if (isAlarm) NOTI_ALARM_ID else NOTI_SERVICE_ID, notification)
    }

    fun handleVolume(volume: Point) {
        if (!alarmsEnabled) {
            return
        }

        if (autoVolumeLevel) {
            val threshold = history.getAutoVolumeThreshold()
            if (threshold != this.volumeThreshold) {
                // notify that the gui via broadcast
                this.volumeThreshold = threshold
                sendAction(ACTION_AUTOTHRESHOLD_UPDATED, { intent -> intent.putExtra(ACTION_EXTRA_VOLUME, threshold) })
            }

        }

        if (volume.volume > this.volumeThreshold
                && history.timeSinceAlarm() > Duration.ofSeconds(10)) {
            // doAlarmVibrate()
            history.triggerAlarm()
            sendAction(ACTION_ALARM_TRIGGERED)

            val disableAlarmIntent = Intent(this, ConnectionService::class.java)
                    .setAction(ACTION_DISABLE_ALARM)
                    .putExtra("extra-field", 0)

            val disableAlarmPending =
                    PendingIntent.getBroadcast(this, 0, disableAlarmIntent, 0);

            doNotify({ builder ->
                builder.setLights(Color.RED, 500, 500)
                builder.setVibrate(arrayOf(0L, 300L, 300L, 300L, 300L, 300L, 300L, 300L, 300L, 300L).toLongArray())
                builder.setSound(Settings.System.DEFAULT_NOTIFICATION_URI)
                builder.setContentText("Luise is crying")
                builder.addAction(R.drawable.ic_snooze_black_24dp, "snooze", disableAlarmPending);
            }, isAlarm = true)
        }
    }

    fun addConnectionState(builder: NotificationCompat.Builder): NotificationCompat.Builder {
        when (this.connectionState) {
            ConnectionState.Connecting ->
                builder.setContentText(getString(R.string.nfTextConnecting))
            ConnectionState.Connected ->
                builder.setContentText(getString(R.string.nfTextConnected))
            ConnectionState.Disconnected ->
                builder.setContentText(getString(R.string.nfTextDisconnected))
        }

        return builder
    }

    override fun onDisconnect(code: Int, reason: String) {
        Log.i(TAG, "Websocket onDisconnect()")
        Log.i(TAG, "Code: $code - Reason: $reason")

        stopSocket()

        if (this.shouldConnect()) {
            Log.i(TAG, "got disconnected, will try to reconnect")
            scheduleConnect(false)
        } else {
            Log.i(TAG, "removing notification")
            NotificationManagerCompat.from(this).cancel(NOTI_SERVICE_ID)
        }
    }

    private fun scheduleConnect(reconnect: Boolean = false) {
        if (reconnectScheduled) {
            // do not double schedule
            return
        }
        handler.postDelayed({
            startSocket(reconnect)
        }, 3000)
        connectionState = ConnectionState.Connecting
        doNotify({ x -> this.addConnectionState(x) })

    }

    override fun onError(error: Exception) {
        Log.i(TAG, "onError ")
        if (this.shouldConnect()) {
            Log.e(TAG, "Websocket onError " + error.toString())
            scheduleConnect(true)
        } else {
            Log.i(TAG, "removing notification")
            NotificationManagerCompat.from(this).cancel(NOTI_SERVICE_ID)
        }
    }

    private fun startSocket(reconnect: Boolean = false) {
        Log.d(TAG, "startSocket")
        if (this.currentDevice == null) {
            Log.i(TAG, "No host configured. Will not attempt to connect")
            return
        }
        if (mWebSocketClient != null) {
            if (reconnect) {
                Log.d(TAG, "socket exists, will disconnect first")
                this.disconnect()
            } else {
                Log.i(TAG, "already connected, not restarting socket.")
                return
            }
        }


        mWebSocketClient = WebSocketClient(URI.create("ws://${currentDevice?.hostname}:8080"), this, null)
        mWebSocketClient!!.connect()

        connectionState = ConnectionState.Connecting
        doNotify({ x -> this.addConnectionState(x) })
    }


    private fun stopSocket() {
        Log.i(TAG, "stop socket called")
        if (mWebSocketClient != null) {
            mWebSocketClient!!.disconnect()
            mWebSocketClient = null
        }

        connectionState = ConnectionState.Disconnected
        NotificationManagerCompat.from(this).cancel(NOTI_SERVICE_ID)
    }

    private fun sendMessageReceivedEvent(message: String) {
        val intent = Intent(ACTION_MSG_RECEIVED).putExtra("message", message)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun sendAction(action: String, modify: ((Intent) -> Unit)? = null) {
        var intent = Intent(action)
        if (modify != null) {
            modify(intent)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    fun toggleLights() {
        lights = !lights
    }

    inner class ConnectionServiceBinder : Binder() {
        val service: ConnectionService
            get() = this@ConnectionService
    }

    companion object {
        val ACTION_VOLUME_RECEIVED = "volumeReceived"
        val ACTION_EXTRA_VOLUME = "volume"
        val ACTION_MOVEMENT_RECEIVED = "movementReceived"
        val ACTION_EXTRA_MOVEMENT = "movement"
        val ACTION_EXTRA_MOVEMENT_MOVED = "moved"
        val ACTION_MSG_RECEIVED = "msgReceived"
        val ACTION_NETWORK_STATE_CHANGED = "networkStateChanged"
        val ACTION_ALARM_TRIGGERED = "alarmTriggered"
        val ACTION_AUTOTHRESHOLD_UPDATED = "autothresholdUpdated"

        val ACTION_DISABLE_ALARM = "disableAlarm"

        val NOTI_SERVICE_ID = 105
        val NOTI_ALARM_ID = 107
        val NOTIFICATION_CHANNEL_ID = "babyphone_notifications"

        fun createActionIntentFilter(): IntentFilter {
            val filter = IntentFilter()
            filter.addAction(ACTION_VOLUME_RECEIVED)
            filter.addAction(ACTION_MOVEMENT_RECEIVED)
            filter.addAction(ConnectionState.Disconnected.action)
            filter.addAction(ConnectionState.Connected.action)
            filter.addAction(ConnectionState.Connecting.action)
            filter.addAction(ACTION_MSG_RECEIVED)
            filter.addAction(ACTION_ALARM_TRIGGERED)
            filter.addAction(ACTION_AUTOTHRESHOLD_UPDATED)
            return filter
        }

        val CONNECTION_ERROR_PATTERN = longArrayOf(200, 200)

        private val TAG = ConnectionService::class.java.simpleName
    }
}
