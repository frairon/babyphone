package babyphone.frosi.babyphone

import android.util.Log
import io.reactivex.subjects.BehaviorSubject
import org.json.JSONObject
import java.io.IOException
import java.net.*
import kotlin.concurrent.thread


class Discovery {

    private val socket = DatagramSocket(null)

    companion object {
        const val TAG = "discovery"
    }

    val advertisements = BehaviorSubject.create<Advertise>()

    fun start() {
        socket.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), 31634))
        socket.broadcast = true
        thread {
            run()
        }
    }

    fun stop() {
        Log.i(TAG, "disconnecting socket")
        socket.disconnect()
        socket.close()
    }

    private fun run() {
        while (true) {
            val p = DatagramPacket(ByteArray(1024), 0, 1024)
            try {
                this.socket.receive(p)


                val parsed = JSONObject(p.data.toString(Charsets.UTF_8))
                Log.i(TAG, "received broadcast $parsed from ${p.address}")
                if (parsed.optString("action") == "advertise") {
                    val adv = Advertise(parsed.optString("host"))
                    advertisements.onNext(adv)
                }
            } catch (se: SocketException) {
                Log.i(TAG, "got SocketException ${se.localizedMessage}")

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
        Log.d(TAG, "broadcast address $addr")
        try {
            socket.send(DatagramPacket(data, data.size, addr, 31634))
        } catch (e: IOException) {
            Log.i(TAG, "exception broadcasting for devices: ${e.localizedMessage}")
        }
    }

    private fun getBroadcast(): InetAddress {
        System.setProperty("java.net.preferIPv4Stack", "true")
        val niEnum = NetworkInterface.getNetworkInterfaces()
        while (niEnum.hasMoreElements()) {
            val ni = niEnum.nextElement()
            if (!ni.isLoopback && ni.isUp) {
                for (interfaceAddress in ni.interfaceAddresses) {
                    val bc = interfaceAddress.broadcast
                    if (bc != null) {
                        return bc
                    }
                }
            }
        }

        return InetAddress.getByName("255.255.255.255")
    }

    fun checkHostIsAlive(hostname: String): Boolean {
        return try {
            val url = URL("http://$hostname:8081/ruok")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 200
            conn.connect()
            val content = conn.inputStream.bufferedReader().readText()
            conn.responseCode == 200 && content.trim() == "imok"
        } catch (e: IOException) {
            false
        }
    }

}