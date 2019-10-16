package babyphone.frosi.babyphone

import android.util.Base64
import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
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
import io.reactivex.functions.BiFunction
import io.reactivex.rxkotlin.mergeAllSingles
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.ReplaySubject
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.*
import java.util.concurrent.TimeUnit


class ConnectionLifecycle(
        private val lifecycleRegistry: LifecycleRegistry = LifecycleRegistry()
) : Lifecycle by lifecycleRegistry {

    val running = BehaviorSubject.create<Lifecycle.State>()

    fun start() {
        val event = Lifecycle.State.Started
        lifecycleRegistry.onNext(event)
        running.onNext(event)
    }

    fun stop() {
        val event = Lifecycle.State.Stopped.WithReason(ShutdownReason.GRACEFUL)
        lifecycleRegistry.onNext(event)
        running.onNext(event)
        lifecycleRegistry.onComplete()
    }
}

interface SchedulerProvider {
    fun io(): Scheduler
    fun computation(): Scheduler
}

class DeviceConnection(val device: Device,
                       socketFactory: ((Device, ConnectionLifecycle) -> DeviceConnectionService) = scarletSocketFactory,
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

    val volumes: Observable<Volume>
    private val alarms = ReplaySubject.createWithTimeAndSize<Alarm>(300, TimeUnit.SECONDS, schedProvider.computation(), 1000)
    val movement: Observable<Movement>

    val systemStatus: Observable<DeviceOperation>

    val missingHeartbeat: Observable<Long>

    val connectionState: Observable<ConnectionState>

    val frames: Observable<VideoFrame>

    val config: Observable<Configuration>

    enum class ConnectionState {
        Disconnected,
        Connecting,
        Connected
    }


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

            return Scarlet.Builder()
                    .webSocketFactory(okHttpClient.newWebSocketFactory("ws://${device.hostname}:8080"))
                    .addMessageAdapterFactory(MoshiMessageAdapter.Factory(moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()))
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

//        disposables.add(socket.observeWebSocketEvent().filter { it is WebSocket.Event.OnMessageReceived }.subscribe {
//            val msg = it as WebSocket.Event.OnMessageReceived
//            Log.i(TAG, "received " + msg.message.toString())
//        })

        // our connection state is combined of two observables:
        // (1) the socket's websocket events filtered for connection events
        // (2) the connection lifecycle.
        // The combiner function (BiFunction) checks both events and converts it into our
        // correct ConnectionState.
        // The observable is triggered on both events (websocket event and lifecycle event)
        connectionState = Observable.combineLatest(socket.observeWebSocketEvent()
                .filter { data ->
                    data is WebSocket.Event.OnConnectionClosed
                            || data is WebSocket.Event.OnConnectionClosing
                            || data is WebSocket.Event.OnConnectionFailed
                            || data is WebSocket.Event.OnConnectionOpened<*>
                },
                connLifecycle.running,
                BiFunction<WebSocket.Event, Lifecycle.State, ConnectionState> { event, state ->
                    if (state == Lifecycle.State.Started) {
                        when (event) {
                            is WebSocket.Event.OnConnectionOpened<*> -> ConnectionState.Connected
                            else -> ConnectionState.Connecting
                        }
                    } else {
                        ConnectionState.Disconnected
                    }
                })
                .startWith(ConnectionState.Connecting)
                .replay(1).autoConnect()

        // send a configuration-request every time we get a connection
        disposables.add(this.socket.observeWebSocketEvent().subscribe {
            if (it is WebSocket.Event.OnConnectionOpened<*>) {
                Log.d(TAG, "requesting configuration")
                this.socket.sendAction(Action(action = "configuration_request"))
            }
        })

        // Volume:
        // filter the received actions, remap it to 0-100 and let's keep
        // only the last 1000 items or 300 seconds
        volumes = socket.observeActions()
                .filter { a -> a.action == "volume" }
                .map { a -> Volume(Date(), (a.volume * 100.0).toInt()) }
                .replay(1000, 300, TimeUnit.SECONDS, schedProvider.computation())
                .autoConnect()

        movement = socket.observeActions()
                .filter { it.action == "movement" && it.movement != null }
                .map { it.movement!! }
        missingHeartbeat = socket.observeActions()
                .filter { a -> a.action == "heartbeat" }
                .window(20, TimeUnit.SECONDS, schedProvider.computation())
                .map { w -> w.count() }
                .mergeAllSingles()
                .filter { c -> c == 0L }

        systemStatus = socket.observeActions()
                .filter { a -> a.action == "systemstatus" }
                .map {
                    when (it.status) {
                        "shutdown" -> DeviceOperation.Shutdown
                        "restart" -> DeviceOperation.Restart
                        else -> DeviceOperation.Invalid
                    }
                }

        frames = socket.observeActions()
                .filter { a -> a.action == "vframe" }
                .map {
                    VideoFrame(
                            Base64.decode(it.data, Base64.DEFAULT),
                            it.offset,
                            it.time,
                            VideoFrame.Type.fromInt(it.type),
                            it.partial
                    )
                }

        val cfgConnector = socket.observeActions()
                .filter { it.action == "configuration" && it.configuration != null }
                .map { it.configuration!! }
                .map {
                    Log.d(TAG, "received configuration $it")
                    it
                }
//                .startWith(Configuration(motionDetection = true))
                .replay(1)
        disposables.add(cfgConnector.connect())
        config = cfgConnector

        // start the connection after we have wired up the Observables
        connLifecycle.start()
    }

    fun disconnect() {
        Log.i(TAG, "Disconnecting from device")
        connLifecycle.stop()
        Log.i(TAG, "clearing all disposables")
        disposables.clear()
//        NotificationManagerCompat.from(this).cancel(ConnectionService.NOTI_SERVICE_ID)
    }

    fun startStream() {
        this.socket.sendAction(Action(action = "_startstream"))
    }

    fun stopStream() {
        this.socket.sendAction(Action(action = "_stopstream"))
    }

    fun updateConfig(cfg: Configuration) {
        this.socket.sendAction(Action(action = "configuration_update", configuration = cfg))
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
//        if (volumeThreshold) {
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
        this.socket.sendAction(Action(action = "shutdown"))
    }

    fun restart() {
        this.socket.sendAction(Action(action = "restart"))
    }
}