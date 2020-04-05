package babyphone.frosi.babyphone

import androidx.core.app.NotificationCompat

// SystemState defines the aggregated state of the system
// based on connection state, alarm state and partly
// configuration.
abstract class SystemState {
    abstract fun notify(builder: NotificationCompat.Builder)

    override fun equals(other: Any?): Boolean {
        if()
        return super.equals(other)
    }
}

class Ok : SystemState() {
    override fun notify(builder: NotificationCompat.Builder) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
}

class Connecting : SystemState() {
    override fun notify(builder: NotificationCompat.Builder) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
}

class ConnectionProblems(val since: Long) : SystemState() {
    override fun notify(builder: NotificationCompat.Builder) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
}

class SoundAlarm(val since: Long) : SystemState() {
    override fun notify(builder: NotificationCompat.Builder) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
}
