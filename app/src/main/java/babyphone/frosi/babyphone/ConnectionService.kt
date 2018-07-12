package babyphone.frosi.babyphone

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.IBinder
import android.support.v4.content.LocalBroadcastManager
import android.util.Log
import com.codebutler.android_websockets.WebSocketClient
import org.json.JSONObject
import java.net.URI
import java.nio.charset.Charset

class ConnectionService : Service(), WebSocketClient.Listener {

    private val mBinder = WebSocketsBinder()
    private var mWebSocketClient: WebSocketClient? = null
    private var currentUri: String? = null

    private val mMessageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val networkIsOn = intent.getBooleanExtra(ACTION_NETWORK_STATE_CHANGED, false)
            if (networkIsOn) {
                startSocket()
            } else {
                stopSocket()
            }
        }
    }

    private fun configureHost(host: String) {
        this.currentUri = "ws://$host:8080"
        Log.i(TAG, "configuring websocket-uri ${this.currentUri}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onstartcommand")
        if (intent == null) {
            Log.i(TAG, "> null intent. ignoring")
            throw RuntimeException("Intent was null")
        }

        this.configureHost(intent.getStringExtra("host"))
        LocalBroadcastManager.getInstance(this).registerReceiver(mMessageReceiver, IntentFilter(ConnectionService.ACTION_NETWORK_STATE_CHANGED))
        startSocket()
        sendAction(ACTION_CONNECTING, null)
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent): IBinder? {
        Log.i(TAG, "onBind")
        LocalBroadcastManager.getInstance(this).registerReceiver(mMessageReceiver, IntentFilter(ConnectionService.ACTION_NETWORK_STATE_CHANGED))
        startSocket()
        return mBinder
    }

    override fun onUnbind(intent: Intent): Boolean {
        Log.i(TAG, "onUnbind")
        return true
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mMessageReceiver)
        stopSocket()
    }

    override fun onCreate() {
        Log.i(TAG, "onCreate")
    }

    override fun onConnect() {
        Log.i(TAG, "Websocket onConnect()")
        sendAction(ACTION_CONNECTED, null)
    }

    override fun onMessage(message: String) {
        Log.i(TAG, "Websocket onMessage()")
        Log.i(TAG, "Message (String) received: $message")
        parseAndSendAction(message)
    }

    override fun onMessage(data: ByteArray) {
        Log.d(TAG, "Message (byte[]) received: " + String(data, Charset.defaultCharset()))
        parseAndSendAction(String(data, Charset.defaultCharset()))
    }

    private fun parseAndSendAction(message: String) {
        val parsed = JSONObject(message)
        if (parsed.has("volume")) {
            val volume = parsed.getDouble("volume")
            sendAction(ACTION_VOLUME_RECEIVED, { intent -> intent.putExtra(ACTION_EXTRA_VOLUME, volume) })
        } else {
            Log.w(TAG, "Unparsed message from websocket received. Ignoring")
        }

    }

    override fun onDisconnect(code: Int, reason: String) {
        Log.i(TAG, "Websocket onDisconnect()")
        Log.i(TAG, "Code: $code - Reason: $reason")
        sendAction(ACTION_DISCONNECTED, null)
    }

    override fun onError(error: Exception) {
        Log.i(TAG, "Websocket onError()")
        if (mWebSocketClient != null) {
            Log.e(TAG, "Error (connection may be lost)", error)
        }
    }

    private fun startSocket() {
        if (this.currentUri == null) {
            Log.e(TAG, "Error starting socket: no uri configured")
            return
        }
        mWebSocketClient = WebSocketClient(URI.create(this.currentUri), this, null)
        mWebSocketClient!!.connect()
    }

    private fun stopSocket() {
        if (mWebSocketClient != null) {
            mWebSocketClient!!.disconnect()
            mWebSocketClient = null
        }
    }

    private fun sendMessageReceivedEvent(message: String) {
        val intent = Intent(ACTION_MSG_RECEIVED).putExtra("message", message)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun sendAction(action: String, modify: ((Intent) -> Unit)?) {
        var intent = Intent(action)
        if (modify != null) {
            modify(intent)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    inner class WebSocketsBinder : Binder() {
        val service: ConnectionService
            get() = this@ConnectionService
    }

    companion object {
        val ACTION_VOLUME_RECEIVED = "volumeReceived"
        val ACTION_EXTRA_VOLUME = "volume"
        val ACTION_CONNECTING = "connecting"
        val ACTION_CONNECTED = "connected"
        val ACTION_DISCONNECTED = "disconnected"
        val ACTION_MSG_RECEIVED = "msgReceived"
        val ACTION_NETWORK_STATE_CHANGED = "networkStateChanged"

        fun createActionIntentFilter():IntentFilter{
            val filter = IntentFilter()
            filter.addAction(ACTION_VOLUME_RECEIVED)
            filter.addAction(ACTION_CONNECTING)
            filter.addAction(ACTION_CONNECTED)
            filter.addAction(ACTION_DISCONNECTED)
            filter.addAction(ACTION_MSG_RECEIVED)
            return filter
        }
        private val TAG = ConnectionService::class.java.simpleName
    }
}