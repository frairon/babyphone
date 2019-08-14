package babyphone.frosi.babyphone

class ConnectionUpdated(val state: ConnectionService.ConnectionState)

class VideoFrame(val data: ByteArray,
                 val offset: Int,
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

class StreamAction(val action: Action) {
    enum class Action(val value:Int) {
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

class Advertise(val host:String){
}