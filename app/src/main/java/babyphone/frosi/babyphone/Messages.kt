package babyphone.frosi.babyphone

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonQualifier
import com.tinder.scarlet.Stream
import com.tinder.scarlet.WebSocket
import com.tinder.scarlet.ws.Receive
import com.tinder.scarlet.ws.Send
import io.reactivex.Flowable
import io.reactivex.Observable
import java.util.*
import kotlin.Comparator

class ConnectionStateUpdated(val state: DeviceConnection.ConnectionState,
                             val device: Device?)

class VideoFrame(val data: ByteArray,
                 val pts: Long,
                 val timestamp: Long,
                 val type: Type,
                 val partial: Boolean) {
    enum class Type(val value: Int) {
        Frame(0),
        Config(1);

        companion object {
            private val map = Type.values().associateBy(Type::value)
            fun fromInt(type: Int) = map.getValue(type)
        }
    }

}

enum class DeviceOperation {
    Invalid,
    Restart,
    Shutdown
}

class StreamAction(val action: Action) {
    enum class Action(val value: Int) {
        Start(0),
        Stop(1);

        companion object {
            private val map = Action.values().associateBy(Action::value)
            fun fromInt(type: Int) = map.getValue(type)
        }
    }
}

class Discover {

}

class Advertise(val host: String) {
}

//@JsonClass(generateAdapter = true)
//data class SubscribeAction(
//        @Json(name = "type") val type: String = "subscribe",
//        @Json(name = "product_ids") val productIds: List<String>,
//        @Json(name = "channels") val channels: List<TickerRequest>
//)
data class Action(
        @Json(name = "action") val action: String,
        @Json(name = "volume") val volume: Double = 0.0,
        @Json(name = "value") val value: Double = 0.0,
        @Json(name = "status") val status: String = "",
        @Json(name = "type") val type: Int = 0,
        @Json(name = "time") val time: Long = 0,
        @Json(name = "partial") val partial: Boolean = false,
        @Json(name = "data") val data: String = "",
        // pts = presentation time stamp
        @Json(name = "pts") val pts: Long = 0,
        // wall clock timestamp
        @Json(name = "timestamp") val timestamp: Long = 0,
        @Json(name = "configuration") val configuration: Configuration? = null,
        @Json(name = "movement") val movement: Movement? = null,
        @Json(name = "audio") val audio: Audio? = null
)

data class Volume(val time: Date, val volume: Int)
data class Alarm(val time: Date)

data class Movement(
        @Json(name = "value") val value: Double = 0.0,
        @Json(name = "moved") val moved: Boolean = false,
        @Json(name = "interval_millis") val intervalMillis: Long = 0
)

data class Configuration(
        @Json(name = "night_mode") val nightMode: Boolean? = null,
        @Json(name = "motion_detection") val motionDetection: Boolean? = null
)

data class Audio(
        @Json(name = "data") val data: String = "",
        @Json(name = "pts") val pts: Long = 0,
        @Json(name = "timestamp") val timestamp: Long = 0
)

interface DeviceConnectionService {

    @Receive
    fun observeWebSocketEvent(): Observable<WebSocket.Event>

    @Send
    fun sendRaw(raw: String)

    @Send
    fun sendAction(action: Action)

    @Receive
    fun observeActions(): Observable<Action>
}