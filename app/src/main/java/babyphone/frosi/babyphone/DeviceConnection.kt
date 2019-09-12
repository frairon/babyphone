package babyphone.frosi.babyphone

import android.util.Log
import com.tinder.scarlet.Lifecycle
import com.tinder.scarlet.Scarlet
import com.tinder.scarlet.ShutdownReason
import com.tinder.scarlet.WebSocket
import com.tinder.scarlet.lifecycle.LifecycleRegistry
import com.tinder.scarlet.messageadapter.moshi.MoshiMessageAdapter
import com.tinder.scarlet.retry.ExponentialWithJitterBackoffStrategy
import com.tinder.scarlet.streamadapter.rxjava2.RxJava2StreamAdapterFactory
import com.tinder.scarlet.websocket.okhttp.newWebSocketFactory
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.rxkotlin.mergeAllSingles
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.ReplaySubject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.greenrobot.eventbus.EventBus
import org.json.JSONObject
import java.util.*
import java.util.concurrent.TimeUnit

class ConnectionLifecycle(
        private val lifecycleRegistry: LifecycleRegistry = LifecycleRegistry()
) : Lifecycle by lifecycleRegistry {

    init {
        lifecycleRegistry.onNext(Lifecycle.State.Started)
    }

    fun start() {
        lifecycleRegistry.onNext(Lifecycle.State.Started)
    }

    fun stop() {
        lifecycleRegistry.onNext(Lifecycle.State.Stopped.WithReason(ShutdownReason.GRACEFUL))
        lifecycleRegistry.onComplete()
    }
}

interface SchedulerProvider {
    fun io(): Scheduler
    fun computation(): Scheduler
}

class DeviceConnection(val device: Device,
                       socketFactory: ((Device, ConnectionLifecycle) -> DeviceConnectionService) = DeviceConnection.scarletSocketFactory,
                       schedProvider: SchedulerProvider = reactiveXSchedulers()
) {

    private val socket: DeviceConnectionService

    private val connLifecycle = ConnectionLifecycle()

    private class reactiveXSchedulers : SchedulerProvider {
        override fun io(): Scheduler {
            return Schedulers.io()
        }

        override fun computation(): Scheduler {
            return Schedulers.computation()
        }
    }

    private val hbWatcher = Observable.interval(20, 20, TimeUnit.SECONDS, schedProvider.computation())
    //    private val history = History(Babyphone.MAX_GRAPH_ELEMENTS)

    val volumes: Observable<Volume>
    private val movements = ReplaySubject.createWithTimeAndSize<Movement>(300, TimeUnit.SECONDS, schedProvider.computation(), 1000)
    private val alarms = ReplaySubject.createWithTimeAndSize<Alarm>(300, TimeUnit.SECONDS, schedProvider.computation(), 1000)

    val systemStatus: Observable<DeviceOperation>

    private val missingHeartbeat: Observable<Long>

    private val disposables = CompositeDisposable()

    companion object {
        const val TAG = "DeviceConnection"

        val scarletSocketFactory = fun(device: Device, lifecycle: ConnectionLifecycle): DeviceConnectionService {
            val backoffStrategy = ExponentialWithJitterBackoffStrategy(5000, 5000)

            val okHttpClient = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .writeTimeout(10, TimeUnit.SECONDS)
                    .build()

            Log.d(TAG, "beore creating socket")
            return Scarlet.Builder()
                    .webSocketFactory(okHttpClient.newWebSocketFactory("ws://${device.hostname}:8080"))
                    .addMessageAdapterFactory(MoshiMessageAdapter.Factory())
                    .addStreamAdapterFactory(RxJava2StreamAdapterFactory())
                    .backoffStrategy(backoffStrategy)
                    .lifecycle(lifecycle)
                    .build()
                    .create(DeviceConnectionService::class.java)
        }
    }

    init {

        socket = socketFactory(device, connLifecycle)
        Log.d(TAG, "after creating socket")

        disposables.add(socket.observeWebSocketEvent().subscribe { data ->

            Log.i(TAG, "received event ${data.toString()}")
            when (data) {
                is WebSocket.Event.OnConnectionClosed -> {
                    connectionState = ConnectionState.Disconnected
                }
                is WebSocket.Event.OnConnectionClosing -> {
                    connectionState = ConnectionState.Disconnected
                }
                is WebSocket.Event.OnConnectionFailed -> {
                    connectionState = ConnectionState.Disconnected
                }
                is WebSocket.Event.OnConnectionOpened<*> -> {
                    connectionState = ConnectionState.Connected
                }
            }
        })
        disposables.add(socket.observeActions().subscribe { m -> Log.i(TAG, "receiving ${m.toString()}") })
        Log.i(TAG, "observing to actions")
        val volConnector = socket.observeActions()
                .filter { a -> a.action == "volume" }
                .map { a -> Volume(Date(), (a.volume * 100.0).toInt()) }
                .replay(1000, 300, TimeUnit.SECONDS, schedProvider.computation())
        volConnector.connect()
        volumes = volConnector

        socket.observeActions()
                .filter { a -> a.action == "movement" }
                .map { a -> Movement(Date(), (a.value * 100.0).toInt()) }
                .sorted { o1, o2 -> o1.time.compareTo(o2.time) }.subscribe(movements)

        disposables.add(socket.observeActions()
                .filter { a -> a.action == "heartbeat" }
                .forEach { hb -> }
        )

        missingHeartbeat = socket.observeActions()
                .filter { a -> a.action == "heartbeat" }
                .window(20, TimeUnit.SECONDS, schedProvider.computation())
                .map { w -> w.count() }.mergeAllSingles().filter { c -> c == 0L }

        systemStatus = socket.observeActions()
                .filter { a -> a.action == "systemstatus" }.map {
                    when (it.status) {
                        "shutdown" -> DeviceOperation.Shutdown
                        "restart" -> DeviceOperation.Restart
                        else -> DeviceOperation.Invalid
                    }
                }
    }


    var connectionState: ConnectionState = ConnectionState.Connecting
        private set(value) {
            Log.d(TAG, "Setting connection state $value")
            field = value
            EventBus.getDefault().post(ConnectionStateUpdated(value, device))
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

    fun disconnect() {
        Log.i(TAG, "Disconnecting from device")
        connLifecycle.stop()
        Log.i(TAG, "clearing all disposables")
        disposables.clear()
//        NotificationManagerCompat.from(this).cancel(ConnectionService.NOTI_SERVICE_ID)
    }


//    override fun onConnect() {
//        Log.i(TAG, "Websocket onConnect()")
//        connectionState = ConnectionState.Connected
//        this.history.clear()
//        doNotify({ x -> this.addConnectionState(x) })
//    }

//    override fun onMessage(message: String) {
//        parseAndSendAction(message)
//    }
//
//    override fun onMessage(data: ByteArray) {
//        parseAndSendAction(String(data, Charset.defaultCharset()))
//    }


//    private fun parseAndSendAction(message: String) {
//        val parsed = JSONObject(message)
//
//        when (parsed.optString("action")) {
//            "volume" -> {
//                val volume = parsed.optDouble("volume")
//                val vol = Point(Date(), (volume * 100.0).toInt())
//                history.addVolume(vol)
////                this.handleVolume(vol)
//                sendAction(ConnectionService.ACTION_VOLUME_RECEIVED, { intent -> intent.putExtra(ConnectionService.ACTION_EXTRA_VOLUME, vol) })
//            }
//            "movement" -> {
//                val movement = parsed.optDouble("value")
//                val mov = Point(Date(), (movement * 100.0).toInt())
//                history.addMovement(mov)
//                sendAction(ConnectionService.ACTION_MOVEMENT_RECEIVED, { intent ->
//                    intent.putExtra(ConnectionService.ACTION_EXTRA_MOVEMENT, mov)
//                    intent.putExtra(ConnectionService.ACTION_EXTRA_MOVEMENT_MOVED, parsed.optBoolean("moved"))
//                })
//                if (parsed.optBoolean("moved")) {
//                    vibrate(arrayOf(0L, 150L).toLongArray())
//                }
//            }
//            "heartbeat" -> {
//                heartbeat.heartbeat()
//            }
//            "vframe" -> {
//                val type = VideoFrame.Type.fromInt(parsed.optInt("type"))
//                val offset = parsed.optInt("offset")
//                val timestamp = parsed.optLong("time")
//                val partial = parsed.optBoolean("partial")
//                val data = parsed.optString("data")
//                val dataBytes = Base64.decode(data, Base64.DEFAULT)
//                EventBus.getDefault().post(VideoFrame(
//                        dataBytes,
//                        offset,
//                        timestamp,
//                        type,
//                        partial
//                ))
//            }
//            "systemstatus" -> {
//                val status = parsed.optString("status")
//                when (status) {
//                    "shutdown" -> disconnect()
//                    "restart" -> {
//                    } // do nothing on restart, we'll just try to reconnect
//                }
//            }
//            else ->
//                Log.d(TAG, "unhandled message " + parsed)
//        }
//    }

//    private fun handleVolume(volume: Point) {
//        if (!alarmsEnabled) {
//            return
//        }
//
//        if (autoVolumeLevel) {
//            val threshold = history.getAutoVolumeThreshold()
//            if (threshold != this.volumeThreshold) {
//                // notify that the gui via broadcast
//                this.volumeThreshold = threshold
//                sendAction(ConnectionService.ACTION_AUTOTHRESHOLD_UPDATED, { intent -> intent.putExtra(ConnectionService.ACTION_EXTRA_VOLUME, threshold) })
//            }
//
//        }
//
//        if (volume.volume > this.volumeThreshold
//                && history.timeSinceAlarm() > Duration.ofSeconds(10)) {
//            // doAlarmVibrate()
//            history.triggerAlarm()
//            sendAction(ConnectionService.ACTION_ALARM_TRIGGERED)
//
//            val disableAlarmIntent = Intent(this, ConnectionService::class.java)
//                    .setAction(ConnectionService.ACTION_DISABLE_ALARM)
//                    .putExtra("extra-field", 0)
//
//            val disableAlarmPending =
//                    PendingIntent.getBroadcast(this, 0, disableAlarmIntent, 0);
//
//            doNotify({ builder ->
//                builder.setLights(Color.RED, 500, 500)
//                builder.setVibrate(arrayOf(0L, 300L, 300L, 300L, 300L, 300L, 300L, 300L, 300L, 300L).toLongArray())
//                builder.setSound(Settings.System.DEFAULT_NOTIFICATION_URI)
//                builder.setContentText("Luise is crying")
//                builder.addAction(R.drawable.ic_snooze_black_24dp, "snooze", disableAlarmPending);
//            }, isAlarm = true)
//        }
//    }

    fun lights(value: Boolean) {
        val data = JSONObject();
        data.put("action", if (value) "lightson" else "lightsoff");
        this.socket.sendRaw(data.toString())
    }

    fun shutdown() {
        val data = JSONObject();
        data.put("action", "shutdown");
        this.socket.sendRaw(data.toString())
    }

    fun restart() {
        val data = JSONObject();
        data.put("action", "restart");
        this.socket.sendRaw(data.toString())
    }
}