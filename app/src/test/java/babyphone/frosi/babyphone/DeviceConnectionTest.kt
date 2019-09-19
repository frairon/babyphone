package babyphone.frosi.babyphone

import com.tinder.scarlet.WebSocket
import org.junit.Test
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.schedulers.TestScheduler
import io.reactivex.subjects.PublishSubject
import java.util.concurrent.TimeUnit

class MockDeviceConnectionService() : DeviceConnectionService {

    val webSocketEvents = PublishSubject.create<com.tinder.scarlet.WebSocket.Event>()
    val actionEvents = PublishSubject.create<Action>()

    override fun observeWebSocketEvent(): Observable<com.tinder.scarlet.WebSocket.Event> {
        return webSocketEvents
    }

    override fun sendRaw(raw: String) {
    }

    override fun observeActions(): Observable<Action> {
        return actionEvents
    }

}

class MockSchedulers() : SchedulerProvider {
    val scheduler = TestScheduler()

    override fun io(): Scheduler {
        return scheduler
    }

    override fun computation(): Scheduler {
        return scheduler
    }

    fun advanceMillis(millis: Long) {
        scheduler.advanceTimeBy(millis, TimeUnit.MILLISECONDS)
    }

    fun advanceSecs(secs: Long) {
        scheduler.advanceTimeBy(secs, TimeUnit.SECONDS)
    }
}


class DeviceConnectionTest {

    private val sched = MockSchedulers()

    @Test
    fun TestVolumeReplay() {

        val d = Device(hostIp = "1.2.3.4", hostname = "test")
        val svc = MockDeviceConnectionService()
        val dc = DeviceConnection(d, socketFactory = { _, _ -> svc }, schedProvider = sched)


        val tester = dc.volumes.test()
        // emit an action event
        svc.actionEvents.onNext(Action(action = "volume", volume = 1.0))

        tester.assertValueAt(0) { v -> v.volume == 100 }

        sched.advanceSecs(10)
        // send a second message
        svc.actionEvents.onNext(Action(action = "volume", volume = 0.5))


        // the first tester has the second message too:
        tester.assertValueAt(1) { v -> v.volume == 50 }

        // a newly subscribed gets both values
        dc.volumes.test().assertValueCount(2)

        // the old one is gone after 300, the new one
        sched.advanceSecs(291)
        dc.volumes.test().assertValueCount(1)
        // then the second one too
        sched.advanceSecs(10)
        dc.volumes.test().assertValueCount(0)

        dc.disconnect()
    }

    @Test
    fun TestMissingHeartbeat() {
        val d = Device(hostIp = "1.2.3.4", hostname = "test")
        val svc = MockDeviceConnectionService()
        val dc = DeviceConnection(d, socketFactory = { _, _ -> svc }, schedProvider = sched)

        // emit an action event
        svc.actionEvents.onNext(Action(action = "heartbeat"))
        var tester = dc.missingHeartbeat.test()
        tester.assertValueCount(0)
        sched.advanceSecs(19)
        tester.assertValueCount(0)
        svc.actionEvents.onNext(Action(action = "heartbeat"))
        sched.advanceSecs(2)
        tester.assertValueCount(0)
        sched.advanceSecs(20)
        tester.assertValueCount(1)
        sched.advanceSecs(20)
        tester.assertValueCount(2)
    }

    @Test
    fun testConnectionState() {
        val d = Device(hostIp = "1.2.3.4", hostname = "test")
        val svc = MockDeviceConnectionService()
        val dc = DeviceConnection(d, socketFactory = { _, _ -> svc }, schedProvider = sched)

        val tester = dc.connectionState.test()
        // it always starts with connecting
        tester.assertValueAt(0, DeviceConnection.ConnectionState.Connecting)
        tester.assertValueCount(1)
        svc.webSocketEvents.onNext(WebSocket.Event.OnConnectionOpened(0))
        tester.assertValueAt(1, DeviceConnection.ConnectionState.Connected)
        tester.assertValueCount(2)

        // check for a new subscriber
        val tester2 = dc.connectionState.test()
        // it only gets the recent proxyState
        tester2.assertValueAt(0, DeviceConnection.ConnectionState.Connected)
        tester2.assertValueCount(1)
    }
}