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
import io.reactivex.rxkotlin.addTo
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


class NullConnection {

    companion object {
        val INSTANCE = DeviceConnection(Device(name = "null", hostname = "null"),
                socketFactory = { device, lc -> NullSocketFactory.factory(device, lc) },
                connectOnConstruct = false)
    }

    private class NullSocketFactory : DeviceConnectionService {
        override fun observeWebSocketEvent(): Observable<WebSocket.Event> {
            return Observable.empty<WebSocket.Event>()
        }

        override fun sendRaw(raw: String) {
        }

        override fun sendAction(action: Action) {
        }

        override fun observeActions(): Observable<Action> {
            return Observable.empty<Action>()
        }

        companion object {
            fun factory(device: Device, lc: ConnectionLifecycle): DeviceConnectionService {
                return NullSocketFactory()
            }
        }
    }
}

interface SchedulerProvider {
    fun io(): Scheduler
    fun computation(): Scheduler
}

open class DeviceConnection(val device: Device,
                            socketFactory: ((Device, ConnectionLifecycle) -> DeviceConnectionService) = scarletSocketFactory,
                            schedProvider: SchedulerProvider = ReactiveXSchedulers(),
                            connectOnConstruct: Boolean = true) {

    private val socket: DeviceConnectionService

    private val connLifecycle = ConnectionLifecycle()

    // scheduler interface so we can replace them unit tests
    private class ReactiveXSchedulers : SchedulerProvider {
        override fun io(): Scheduler {
            return Schedulers.io()
        }

        override fun computation(): Scheduler {
            return Schedulers.computation()
        }
    }

    val volumes: Observable<Volume>

    val movement: Observable<Movement>

    val audio: Observable<Audio>

    val systemStatus: Observable<DeviceOperation>

    val missingHeartbeat: Observable<Long>

    val connectionState = BehaviorSubject.create<ConnectionState>()

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
        Observable.combineLatest(socket.observeWebSocketEvent()
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
                .startWith(ConnectionState.Connecting).subscribe {
                    this.connectionState.onNext(it)
                }
                .addTo(disposables)


        // send a configuration-request every time we get a connection
        this.socket.observeWebSocketEvent()
                .subscribe {
                    if (it is WebSocket.Event.OnConnectionOpened<*>) {
                        Log.d(TAG, "requesting configuration")
                        this.socket.sendAction(Action(action = "configuration_request"))
                    }
                }
                .addTo(disposables)

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
                            data = Base64.decode(it.data, Base64.DEFAULT),
                            offset = it.offset,
                            type = VideoFrame.Type.fromInt(it.type),
                            partial = it.partial
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

        audio = socket.observeActions()
                .filter { it.action == "audio" && it.audio?.data != "" }
                .map { it.audio!! }

        // start the connection after we have wired up the Observables
        if (connectOnConstruct) {
            connLifecycle.start()
        }
    }

    fun disconnect() {
        Log.i(TAG, "Disconnecting from device")
        connLifecycle.stop()
        Log.i(TAG, "clearing all disposables")
        disposables.clear()
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

    fun startAudio() {
        this.socket.sendAction(Action(action = "startaudio"))
    }

    fun stopAudio() {
        this.socket.sendAction(Action(action = "stopaudio"))
    }
}
