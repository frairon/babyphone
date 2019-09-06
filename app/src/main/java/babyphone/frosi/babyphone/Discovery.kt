package babyphone.frosi.babyphone

import android.util.Log
import org.greenrobot.eventbus.EventBus
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
    }

    fun stop() {
        Log.i("discovery", "disconnecting socket")
        socket.disconnect()
        socket.close()
    }

    fun run() {
        while (true) {
            val p = DatagramPacket(ByteArray(1024), 0, 1024)
            try {
                socket.receive(p)


                val parsed = JSONObject(p.data.toString(Charsets.UTF_8))
                Log.i("discovery", "received broadcast " + parsed.toString() + " from " + p.address.toString())
                if (parsed.optString("action") == "advertise") {
                    val adv = Advertise(parsed.optString("host"))
                    EventBus.getDefault().post(adv)
                }
            } catch (se: SocketException) {
                Log.i("discovery", "got SocketException " + se.localizedMessage)

                if (socket.isConnected) {
                    Thread.sleep(1000)
                } else {
                    return
                }
            }
        }
    }

    fun discover() {
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