package babyphone.frosi.babyphone

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.*
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
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
    val alarmEnabled = BehaviorSubject.create<Boolean>()

    val autoSoundEnabled = BehaviorSubject.create<Boolean>()

    private var connDisposables = CompositeDisposable()

    private var svcDisposables = CompositeDisposable()

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

        // assume 50% volume per default
        volumeThreshold.onNext(50)

        // enable alarms per default
        alarmEnabled.onNext(true)

        autoSoundEnabled.onNext(true)

        this.connections.subscribe {
            this.updateConnection(it)
        }.addTo(svcDisposables)
    }

    fun setAlarmEnabled(enabled: Boolean) {
        this.alarmEnabled.onNext(enabled)
    }

    fun connect(device: Device, reconnect: Boolean = true): DeviceConnection {

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
                    if (alarmEnabled.value == true
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
                    if (this.autoVolumeLevelEnabled && this.alarmEnabled.value == true) {
                        var valids = it.filter { it != -1 }.toIntArray()
                        if (valids.size > 10) {
                            valids.sort()
                            val quant75 = valids.get((valids.size.toDouble() * 0.75).toInt())
                            val median = valids.get(valids.size / 2)
                            val newThreshold = (quant75 + median + 10)
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
                    if (!this.audioPlayRequested) {
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
                    }
                    doNotify({ builder ->
                        builder.setContentText(text)
                        builder.setSmallIcon(icon)
                        builder.color = color
                    }, isAlarm = false)

                }
                .addTo(connDisposables)
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


        private val TAG = ConnectionService::class.java.simpleName

        fun startService(context: Context) {
            val componentName = context.startService(Intent(context, ConnectionService::class.java))
            if (componentName == null) {
                throw RuntimeException("Could not start connection service. does not exist")
            }
        }
    }
}
