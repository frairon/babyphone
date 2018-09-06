package babyphone.frosi.babyphone

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.app.NotificationCompat
import android.support.v4.app.NotificationManagerCompat
import android.support.v4.app.TaskStackBuilder
import android.support.v4.content.LocalBroadcastManager
import android.util.Log
import com.codebutler.android_websockets.WebSocketClient
import org.json.JSONObject
import org.threeten.bp.Duration
import org.threeten.bp.Instant
import java.io.Serializable
import java.net.URI
import java.nio.charset.Charset
import java.util.*


class Volume(val time: Date, val volume: Int) : Serializable {
}

class History(private val maxSize: Int) {

    var alarms: MutableList<Instant> = ArrayList<Instant>()

    var volumes: MutableList<Volume> = ArrayList<Volume>()
        private set

    fun add(vol: Volume) {
        volumes.add(vol)
        volumes.sortBy { vol.time }
        if (volumes.size > this.maxSize) {
            this.volumes = this.volumes.drop(volumes.size - this.maxSize) as MutableList
        }
    }

    fun clear() {
        this.alarms.clear()
        this.volumes.clear()
    }

    fun averageVolumeSince(past: Duration): Int {
        var sum = 0
        var count = 0
        var start = Instant.now().minus(past)
        for (vol: Volume in volumes.reversed()) {
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
}

class ConnectionService : Service(), WebSocketClient.Listener {

    private val mBinder = ConnectionServiceBinder()
    private var mWebSocketClient: WebSocketClient? = null
    private var currentUri: String? = null
    var volumeThreshold: Int = 50
    val history = History(Babyphone.MAX_GRAPH_ELEMENTS)

    var lights: Boolean = false

    var connectionState: ConnectionState = ConnectionState.Disconnected
        private set(value) {
            Log.d(TAG, "Setting connection state $value")
            field = value
            sendAction(value.action)
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
            if (networkIsOn) {
                startSocket()
            } else {
                stopSocket()
            }
        }
    }

    init {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, getString(R.string.nfChannelName), importance);
            channel.description = getString(R.string.nfChannelDesc);
            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    fun connectToHost(host: String) {
        this.currentUri = "ws://$host:8080"
        Log.i(TAG, "configuring websocket-uri ${this.currentUri}")

        startSocket()
    }

    fun disconnect() {
        stopSocket()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onstartcommand")

        this.startForeground()

        return START_STICKY
    }


    fun shutdown() {
        val data = JSONObject();
        data.put("action", "shutdown");
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
                .setLargeIcon(
                        Bitmap.createScaledBitmap(icon, 128, 128, false))
                .setContentIntent(pshowBabyphone)
                .setOngoing(true)

        when (this.connectionState) {
            ConnectionState.Connecting ->
                builder.setContentText(getString(R.string.nfTextConnecting))
            ConnectionState.Connected ->
                builder.setContentText(getString(R.string.nfTextConnected))
            ConnectionState.Disconnected ->
                builder.setContentText(getString(R.string.nfTextDisconnected))
        }

        if (modify != null) {
            modify(builder)
        }
        return builder.build()
    }

    private fun startForeground() {
        startForeground(NOTIFICATION_ID, this.createNotification(null))
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
        stopSocket()
    }

    override fun onCreate() {
        Log.i(TAG, "onCreate")
        LocalBroadcastManager.getInstance(this).registerReceiver(mMessageReceiver, IntentFilter(ConnectionService.ACTION_NETWORK_STATE_CHANGED))
    }

    override fun onConnect() {
        Log.i(TAG, "Websocket onConnect()")
        connectionState = ConnectionState.Connected
        this.history.clear()
        doNotify()
    }

    override fun onMessage(message: String) {
        parseAndSendAction(message)
    }

    override fun onMessage(data: ByteArray) {
        parseAndSendAction(String(data, Charset.defaultCharset()))
    }

    private fun parseAndSendAction(message: String) {
        val parsed = JSONObject(message)
        if (parsed.has("volume")) {
            val volume = parsed.getDouble("volume")
            val vol = Volume(Date(), (volume * 100.0).toInt())
            history.add(vol)
            this.handleVolume(vol)
            sendAction(ACTION_VOLUME_RECEIVED, { intent -> intent.putExtra(ACTION_EXTRA_VOLUME, vol) })
        } else {
            Log.w(TAG, "Unparsed message from websocket received. Ignoring")
        }
    }

    private fun doNotify(modify: ((NotificationCompat.Builder) -> Unit)? = null) {
        val notification = this.createNotification(modify)
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
    }

    fun handleVolume(volume: Volume) {
        if (volume.volume > this.volumeThreshold
                && history.timeSinceAlarm() > Duration.ofSeconds(20)
                && history.timesAboveThreshold(Duration.ofSeconds(20), this.volumeThreshold) > 3) {

            val alarmsInPast = history.alarmsSince(Duration.ofSeconds(90))

            val vibratePattern = mutableListOf(0L, 100L)
            if (alarmsInPast == 1) {
                vibratePattern.add(100)
                vibratePattern.add(200)
            }
            if (alarmsInPast == 2) {
                vibratePattern.add(100)
                vibratePattern.add(300)
            }
            if (alarmsInPast >= 3) {
                vibratePattern.add(100)
                vibratePattern.add(500)
            }
            history.triggerAlarm()
            sendAction(ACTION_ALARM_TRIGGERED)
            doNotify { builder ->
                builder.setVibrate(vibratePattern.toLongArray())
                val lastMinuteAlarms = history.alarmsSince(Duration.ofSeconds(60))
                builder.setContentInfo("$lastMinuteAlarms Alarms in the last minute")
            }
        }
    }

    override fun onDisconnect(code: Int, reason: String) {
        Log.i(TAG, "Websocket onDisconnect()")
        Log.i(TAG, "Code: $code - Reason: $reason")
        connectionState = ConnectionState.Disconnected
        stopSocket()
        doNotify()
    }

    override fun onError(error: Exception) {
        Log.e(TAG, "Websocket connection: " + error.toString())
        val oldConnectionState = connectionState
        connectionState = ConnectionState.Disconnected
        // TODO: should we do a reconnect?
        stopSocket()
        doNotify { builder ->
            // if we were connected before, let's vibrate. Otherwise it's an error
            // while connecting, which is not something unexpected
            if (oldConnectionState == ConnectionState.Connected) {
                builder.setVibrate(CONNECTION_ERROR_PATTERN)
            }
        }
    }

    private fun startSocket() {
        if (this.currentUri == null) {
            Log.i(TAG, "No host configured. Will not attempt to connect")
            return
        }
        if (mWebSocketClient != null) {
            Log.i(TAG, "already connected, not restarting socket.")
            return
        }
        mWebSocketClient = WebSocketClient(URI.create(this.currentUri), this, null)
        mWebSocketClient!!.connect()

        connectionState = ConnectionState.Connecting
    }

    private fun stopSocket() {
        if (mWebSocketClient != null) {
            mWebSocketClient!!.disconnect()
            mWebSocketClient = null
        }

        this.currentUri = null
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
        val data = JSONObject();
        if (lights) {
            data.put("action", "lightsoff");
            lights = false
        } else {
            data.put("action", "lightson");
            lights = true
        }
        this.mWebSocketClient?.send(data.toString())
    }

    inner class ConnectionServiceBinder : Binder() {
        val service: ConnectionService
            get() = this@ConnectionService
    }

    companion object {
        val ACTION_VOLUME_RECEIVED = "volumeReceived"
        val ACTION_EXTRA_VOLUME = "volume"
        val ACTION_MSG_RECEIVED = "msgReceived"
        val ACTION_NETWORK_STATE_CHANGED = "networkStateChanged"
        val ACTION_ALARM_TRIGGERED = "alarmTriggered"

        val NOTIFICATION_ID = 101
        val NOTIFICATION_CHANNEL_ID = "babyphone_notifications"

        fun createActionIntentFilter(): IntentFilter {
            val filter = IntentFilter()
            filter.addAction(ACTION_VOLUME_RECEIVED)
            filter.addAction(ConnectionState.Disconnected.action)
            filter.addAction(ConnectionState.Connected.action)
            filter.addAction(ConnectionState.Connecting.action)
            filter.addAction(ACTION_MSG_RECEIVED)
            filter.addAction(ACTION_ALARM_TRIGGERED)
            return filter
        }

        val CONNECTION_ERROR_PATTERN = longArrayOf(200, 200, 200, 200, 200, 200)

        private val TAG = ConnectionService::class.java.simpleName
    }
}