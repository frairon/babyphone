package babyphone.frosi.babyphone

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.*
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.rxkotlin.addTo
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.ReplaySubject
import org.threeten.bp.Duration
import org.threeten.bp.Instant
import java.io.Serializable
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt


class Point(val time: Date, val volume: Int) : Serializable {
}

class ConnectionService : Service() {

    private var audioPlayer: AudioPlayer? = null
    val audioPlayStatus = BehaviorSubject.create<Boolean>()
    var audioPlayRequested = false
        private set

    private val mBinder = ConnectionServiceBinder()

    var conn: DeviceConnection? = null

    val discovery = Discovery()

    val connections = BehaviorSubject.create<DeviceConnection>()

    var lights: Boolean = false
        set(value) {
            field = value
        }

    var autoVolumeLevelEnabled: Boolean = true

    val volumeThreshold = ReplaySubject.create<Int>(Babyphone.MAX_GRAPH_ELEMENTS)
    val alarm = ReplaySubject.create<Volume>(10)

    // Configures how the alarm triggering behaves
    // 0 - enabled
    // -1 - disabled
    // 1-n - enabled but snoozing for n seconds
    val alarmTrigger = BehaviorSubject.create<Int>()
    var alarmTriggerDisp: Disposable? = null

    var alarmNoiseLevel = BehaviorSubject.create<Int>()
    var alarmState = BehaviorSubject.create<Boolean>()

    val autoSoundEnabled = BehaviorSubject.create<Boolean>()

    private var connDisposables = CompositeDisposable()

    private var svcDisposables = CompositeDisposable()

    private lateinit var wifiLock: WifiManager.WifiLock

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

        // assume 50% volume per default
        volumeThreshold.onNext(50)

        // enable alarms per default
        alarmTrigger.onNext(0)

        autoSoundEnabled.onNext(true)

        this.connections.subscribe {
            this.updateConnection(it)
        }.addTo(svcDisposables)


    }

    override fun onCreate() {
        super.onCreate()

        val wm = getSystemService(WifiManager::class.java) as WifiManager
        this.wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "babyphone")

    }

    fun disableAlarm() {
        alarmTriggerDisp?.dispose()
        this.alarmTrigger.onNext(-1)
    }

    fun enableAlarm() {
        alarmTriggerDisp?.dispose()
        this.alarmTrigger.onNext(0)
    }

    fun snoozeAlarm() {
        alarmTriggerDisp?.dispose()
        alarmTriggerDisp = Observable.interval(1, 1, TimeUnit.SECONDS).forEach {
            val triggerval = alarmTrigger.value
            if (triggerval != null && triggerval > 0) {
                alarmTrigger.onNext(triggerval - 1)
            } else {
                // stop myself
                alarmTriggerDisp?.dispose()
            }
        }
        this.alarmTrigger.onNext(600)
    }

    fun setAlarmNoiseLevel(level: Int) {
        alarmNoiseLevel.onNext(level)
    }

    fun connect(device: Device): DeviceConnection {

        // let's make wifi stay on
        if (!this.wifiLock.isHeld) {
            this.wifiLock.acquire()
        }

        // stop old connection if any
        this.stopConnection()
        val conn = DeviceConnection(device)
        this.connections.onNext(conn)
        this.conn = conn
        return conn
    }

    private fun updateConnection(conn: DeviceConnection) {
        this.connDisposables.clear()
        this.connDisposables = CompositeDisposable()

        Log.d(TAG, "updating connection")

        if (conn == NullConnection.INSTANCE) {
            Log.d(TAG, "got null connection, will not wire up the observables")
            return
        }


        // connecting to different device -> stop audio of previous
        this.stopAudio()

        this.startForeground()

        conn.volumes
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    if (alarmTrigger.value == 0
                            && it.volume > this.volumeThreshold.value!!) {
                        this.alarm.onNext(it)
                    }
                }
                .addTo(this.connDisposables)

        conn.volumes
                .observeOn(Schedulers.computation())
                .map { it.volume }
                .startWith(MutableList(120) { -1 }.asIterable())
                .buffer(120, 1)
                .subscribe {
                    if (this.autoVolumeLevelEnabled && this.alarmTrigger.value == 0) {
                        val valids = it.filter { it != -1 }.toIntArray()
                        if (valids.isNotEmpty()) {
                            valids.sort()
                            val quant75 = valids.get((valids.size.toDouble() * 0.75).toInt())
                            val median = valids.get(valids.size / 2)
                            var newThreshold = (quant75 + median + 10)

                            alarmNoiseLevel.value?.let {
                                val multiplier = (it - (ALARM_MAX_LEVEL / 2)).toFloat() * 0.2
                                Log.i(TAG, "multiplier is $multiplier")
                                newThreshold += (multiplier * newThreshold.toFloat()).toInt()

                                if (newThreshold < 0) {
                                    newThreshold = 0
                                }
                                if (newThreshold > 100) {
                                    newThreshold = 100
                                }
                            }
                            this.volumeThreshold.onNext(newThreshold)
                        } else {
                            this.volumeThreshold.onNext(50)
                        }
                    }
                }
                .addTo(this.connDisposables)

        this.alarm
                .filter {
                    // ignore outdated alarms in case we're doing a replay
                    it.time.after(Date(System.currentTimeMillis() - 100))
                }
                .throttleFirst(10, TimeUnit.SECONDS)
                .subscribe {
                    doNotify({ builder ->
                        builder.setLights(Color.RED, 500, 500)
                        builder.setVibrate(arrayOf(0L, 300L, 300L, 300L, 300L, 300L, 300L, 300L, 300L, 300L).toLongArray())
                        builder.setSound(Settings.System.DEFAULT_NOTIFICATION_URI)
                        builder.setContentText("Luise is crying")
                    }, isAlarm = true)
                }
                .addTo(connDisposables)


        this.alarm
                .filter {
                    // ignore outdated alarms in case we're doing a replay
                    it.time.after(Date(System.currentTimeMillis() - 100))
                }
                .subscribe {
                    // if we're not playing already
                    if (!this.audioPlayRequested && this.autoSoundEnabled.value == true) {
                        this.playAudio()

                        // start a timer that triggers in 10 seconds that checks:
                        // was there another alarm-volume-value in the last 10 seconds?
                        // was the audio playing requested in the mean time?
                        Observable.timer(11000, TimeUnit.MILLISECONDS, Schedulers.computation()).subscribe {
                            if (this.alarm.value?.time?.after(Date(System.currentTimeMillis() - 10500)) != true
                                    && !this.audioPlayRequested) {
                                this.stopAudio()
                            }
                        }.addTo(this.connDisposables)
                    }
                }
                .addTo(connDisposables)

        conn.missingHeartbeat
                .subscribe {
                    doNotify({ builder ->
                        builder.setVibrate(arrayOf(0L, 200L, 100L, 100L).toLongArray())
                        builder.setContentText(resources.getString(R.string.nfTextConnectionProblems))
                        builder.setSmallIcon(R.drawable.ic_error_outline_black_24dp)
                        builder.color = Color.RED
                    }, isAlarm = false)
                }
                .addTo(connDisposables)

        conn.movement
                .filter { it.moved }
                .subscribe {
                    vibrate(arrayOf(0L, 50L).toLongArray())
                }
                .addTo(connDisposables)

        conn.connectionState
                .distinctUntilChanged()
                .subscribe {
                    var text = ""
                    var icon = 0
                    var color = Color.WHITE
                    when (it) {
                        DeviceConnection.ConnectionState.Connected -> {
                            text = resources.getString(R.string.nfTextConnected)
                            icon = R.drawable.ic_check_black_24dp
                            color = Color.GREEN
                        }
                        DeviceConnection.ConnectionState.Disconnected -> {
                            text = resources.getString(R.string.nfTextDisconnected)
                            icon = R.drawable.ic_close_black_24dp
                            color = Color.GRAY
                        }
                        DeviceConnection.ConnectionState.Connecting -> {
                            text = resources.getString(R.string.nfTextConnecting)
                            icon = R.drawable.ic_autorenew_black_24dp
                            color = Color.BLUE
                        }
                        else -> {
                            // make the compiler happy
                        }
                    }
                    doNotify({ builder ->
                        builder.setContentText(text)
                        builder.setSmallIcon(icon)
                        builder.color = color
                    }, isAlarm = false)

                }
                .addTo(connDisposables)

        conn.systemStatus
                .subscribe {
                    when (it) {
                        DeviceOperation.Shutdown -> {
                            this@ConnectionService.disconnect()
                        }
                    }
                }
                .addTo(connDisposables)
    }

    private fun stopConnection() {
        val oldConn = this.conn ?: return
        oldConn.disconnect()
        this.conn = null
    }

    fun disconnect() {
        // turn off the wifi lock if we don't need it anymore
        if (this.wifiLock.isHeld) {
            this.wifiLock.release()
        }
        Log.i(TAG, "service disconnect requested")
        this.stopForeground(true)
        this.stopConnection()
        this.connections.onNext(NullConnection.INSTANCE)

        Log.i(TAG, "removing notifications")
        NotificationManagerCompat.from(this).cancel(NOTI_SERVICE_ID)
        NotificationManagerCompat.from(this).cancel(NOTI_ALARM_ID)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onstartcommand")
        return START_STICKY
    }

    private fun createNotification(modify: ((NotificationCompat.Builder) -> Unit)?): Notification {
        val showBabyphone = Intent(this, Babyphone::class.java)
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
        this.connDisposables.clear()
        this.svcDisposables.clear()
        this.discovery.stop()
        stopAudio()
        disconnect()
        stopSelf()
        super.onDestroy()
    }

    fun startStream() {
        this.conn?.startStream()
    }

    fun stopStream() {
        this.conn?.stopStream()
    }

    private fun vibrate(pattern: LongArray) {
        if (Build.VERSION.SDK_INT >= 26) {
            (getSystemService(VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createWaveform(pattern, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            (getSystemService(VIBRATOR_SERVICE) as Vibrator).vibrate(pattern, -1)
        }
    }

    private fun doNotify(modify: ((NotificationCompat.Builder) -> Unit)? = null, isAlarm: Boolean = false) {
        val notification = this.createNotification { builder ->
            // alarms should be cancelled when we tap on them
            if (isAlarm) {
                builder.setAutoCancel(true)
            }
            modify?.invoke(builder)
        }
        NotificationManagerCompat.from(this).notify(if (isAlarm) NOTI_ALARM_ID else NOTI_SERVICE_ID, notification)
    }

    fun playAudio(setRequested: Boolean = false) {
        Log.d(TAG, "starting audio")
        val conn = this.conn
        if (conn == null || conn.connectionState.value != DeviceConnection.ConnectionState.Connected) {
            return
        }

        // avoid double play
        if (this.audioPlayer?.isRunning() == true || this.audioPlayStatus.value == true) {
            return
        }

        if (setRequested) {
            this.audioPlayRequested = true
        }

        this.audioPlayStatus.onNext(true)
        this.audioPlayer = AudioPlayer(conn)
    }


    fun stopAudio(setRequested: Boolean = false) {
        val player = this.audioPlayer
        if (player != null && player.isRunning()) {
            this.audioPlayStatus.onNext(false)

            if (setRequested) {
                this.audioPlayRequested = false
            }
            player.stop()
            this.audioPlayer = null
        }
    }

    fun setAutoSoundEnabled(autoSoundEnabled: Boolean) {
        this.autoSoundEnabled.onNext(autoSoundEnabled)
    }

    fun toggleAudio() {
        if (this.audioPlayer?.isRunning() == true) {
            this.stopAudio(true)
        } else {
            this.playAudio(true)
        }
    }

    inner class ConnectionServiceBinder : Binder() {
        val service: ConnectionService
            get() = this@ConnectionService
    }

    companion object {


        val ACTION_DISABLE_ALARM = "disableAlarm"
        val NOTI_SERVICE_ID = 105
        val NOTI_ALARM_ID = 107
        val NOTIFICATION_CHANNEL_ID = "babyphone_notifications"

        val WEBSOCKET_DEFAULTPORT = "8080"
        val IMAGE_DEFAULTPORT = "8081"
        private val urlReg = "(.*?):([0-9]+)$".toRegex()

        val ALARM_MAX_LEVEL = 4

        private val TAG = ConnectionService::class.java.simpleName

        fun startService(context: Context) {
            val componentName = context.startService(Intent(context, ConnectionService::class.java))
            if (componentName == null) {
                throw RuntimeException("Could not start connection service. does not exist")
            }
        }

        fun getMotionUrlForHost(hostname: String, refresh: Boolean): String {

            var url = ""
            val match = urlReg.find(hostname)
            if (match != null && match.groupValues.size == 3) {
                url = "http://${match.groupValues[1]}:${match.groupValues[2].toInt() + 1}/latest"
            } else {
                url = "http://${hostname}:${ConnectionService.IMAGE_DEFAULTPORT}/latest"
            }
            if (refresh) {
                url += "?refresh=1"
            }
            return url
        }

        fun getWebSocketUrl(hostname: String): String {
            if (urlReg.matches(hostname)) {
                return "ws://${hostname}"
            } else {
                return "ws://${hostname}:${ConnectionService.WEBSOCKET_DEFAULTPORT}"
            }
        }
    }
}
