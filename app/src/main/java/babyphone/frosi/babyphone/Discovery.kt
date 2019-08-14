package babyphone.frosi.babyphone

import android.util.Log
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.json.JSONObject
import java.net.*
import kotlin.concurrent.thread


class Discovery {

    val socket = DatagramSocket(null)

    fun start() {
        socket.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), 31634))
        socket.broadcast = true
        thread {
            run()
        }


        EventBus.getDefault().register(this)

    }

    fun stop(){
        EventBus.getDefault().unregister(this)
    }

    fun run() {
        while (true) {
            val p = DatagramPacket(ByteArray(1024), 0, 1024)
            socket.receive(p)


            val parsed = JSONObject(p.data.toString(Charsets.UTF_8))
            Log.i("discovery", "received broadcast " + parsed.toString() + " from " + p.address.toString())
            if(parsed.optString("action") == "advertise"){
                val adv = Advertise(parsed.optString("host"))
                EventBus.getDefault().post(adv)
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    fun discover(discover:Discover) {
        val d = JSONObject()
        d.put("action", "discover")
        val data = d.toString().toByteArray(Charsets.UTF_8)
        val addr = getBroadcast()
        Log.i("discovery", "broadcast address " + addr.toString())
        socket.send(DatagramPacket(data, data.size, addr, 31634))
    }

    fun getBroadcast(): InetAddress {
        System.setProperty("java.net.preferIPv4Stack", "true")
        val niEnum = NetworkInterface.getNetworkInterfaces()
        while (niEnum.hasMoreElements()) {
            val ni = niEnum.nextElement()
            if (!ni.isLoopback && ni.isUp) {
                for (interfaceAddress in ni.getInterfaceAddresses()) {
                    val bc = interfaceAddress.broadcast
                    if (bc != null) {
                        return bc
                    }
                }
            }
        }
        return InetAddress.getByAddress("255.255.255.255".toByteArray())
    }

}