package babyphone.frosi.babyphone

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import io.reactivex.subjects.BehaviorSubject
import org.threeten.bp.Duration
import org.threeten.bp.Instant
import java.io.Serializable
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

class ConnectionService : Service() {

    private val mBinder = ConnectionServiceBinder()

    var conn: DeviceConnection? = null

    val discovery = Discovery()

    val connections = BehaviorSubject.create<DeviceConnection>()

    var volumeThreshold: Int = 50

    var lights: Boolean = false
        set(value) {
            field = value
        }

    var alarmsEnabled: Boolean = true

    var autoVolumeLevel: Boolean = false

    fun getMotionUrl(): String? {
        if (conn?.device?.hostname == "") {
            return null
        }
        return "http://${conn?.device?.hostname}:8081/latest"
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

        // start discovery
        discovery.start()
    }

    fun connect(device: Device, reconnect: Boolean = true) {

        // stop old connection if any
        this.stopConnection()
        val conn = DeviceConnection(device)
        this.connections.onNext(conn)
        this.conn = conn

        Log.i(TAG, "configuring websocket-uri ${this.conn?.device?.hostname}")

        this.startForeground()

    }

    private fun stopConnection() {
        val oldConn = this.conn ?: return
        oldConn.disconnect()
        this.conn = null
    }

    fun disconnect() {
        Log.i(TAG, "service disconnect requested")
        this.stopForeground(true)
        this.stopConnection()

        Log.i(TAG, "removing notification")
        NotificationManagerCompat.from(this).cancel(NOTI_SERVICE_ID)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onstartcommand")
        return START_STICKY
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
        return mBinder
    }

    override fun onUnbind(intent: Intent): Boolean {
        Log.i(TAG, "onUnbind")
        return true
    }


    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        stopForeground(true)
        this.conn?.disconnect()
        stopSelf()
        this.discovery.stop()
        super.onDestroy()
    }

    fun startStream() {
        this.conn?.startStream()
    }

    fun stopStream() {
        this.conn?.stopStream()
    }

//    @Subscribe(threadMode = ThreadMode.POSTING)
//    fun onStreamAction(sa: StreamAction) {

//    }


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

//
//    fun addConnectionState(builder: NotificationCompat.Builder): NotificationCompat.Builder {
//        when (this.connectionState) {
//            ConnectionState.Connecting ->
//                builder.setContentText(getString(R.string.nfTextConnecting))
//            ConnectionState.Connected ->
//                builder.setContentText(getString(R.string.nfTextConnected))
//            ConnectionState.Disconnected ->
//                builder.setContentText(getString(R.string.nfTextDisconnected))
//        }
//
//        return builder
//    }


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
//            filter.addAction(DeviceConnection.ConnectionState.Disconnected.action)
//            filter.addAction(DeviceConnection.ConnectionState.Connected.action)
//            filter.addAction(DeviceConnection.ConnectionState.Connecting.action)
            filter.addAction(ACTION_MSG_RECEIVED)
            filter.addAction(ACTION_ALARM_TRIGGERED)
            filter.addAction(ACTION_AUTOTHRESHOLD_UPDATED)
            return filter
        }

        val CONNECTION_ERROR_PATTERN = longArrayOf(200, 200)

        private val TAG = ConnectionService::class.java.simpleName

        fun startService(context: Context) {
            val componentName = context.startService(Intent(context, ConnectionService::class.java))
            if (componentName == null) {
                throw RuntimeException("Could not start connection service. does not exist")
            }
        }
    }
}
