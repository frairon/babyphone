package babyphone.frosi.babyphone

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.app.NotificationCompat
import android.support.v4.app.NotificationManagerCompat
import android.support.v4.content.LocalBroadcastManager
import android.util.Log
import com.codebutler.android_websockets.WebSocketClient
import org.json.JSONObject
import java.net.URI
import java.nio.charset.Charset


class ConnectionService : Service(), WebSocketClient.Listener {

    private val mBinder = ConnectionServiceBinder()
    private var mWebSocketClient: WebSocketClient? = null
    private var currentUri: String? = null

    var volumeThreshold: Double = 0.5

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

    init {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, getString(R.string.nfChannelName), importance);
            channel.description = getString(R.string.nfChannelDesc);
            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
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
        this.startForeground()

        return START_STICKY
    }

    private fun createNotification(modify: (( NotificationCompat.Builder) -> Unit)?):Notification{
        val showBabyphone = Intent(this, Babyphone::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pshowBabyphone = PendingIntent.getService(this, 0,
                showBabyphone, 0)

        val icon = BitmapFactory.decodeResource(resources,
                R.mipmap.ic_launcher_round)
        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(getString(R.string.nfTitle))
                .setTicker(getString(R.string.nfTicker))
                .setContentText("Babyphone connected")
                .setSmallIcon(R.mipmap.ic_launcher_round)
                .setLargeIcon(
                        Bitmap.createScaledBitmap(icon, 128, 128, false))
                .setContentIntent(pshowBabyphone)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                // TODO: make that configurable
//                .setLights(Color.RED, 3000, 3000)
                // TODO: make that configurable
//                .setVibrate(longArrayOf(0,200,200,200,200,1000))
                .setOngoing(true)
        if(modify != null){
            modify(builder)
        }
        return builder.build()
    }

    private fun startForeground() {
        startForeground(NOTIFICATION_ID, this.createNotification(null))
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
        stopForeground(true)
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
        parseAndSendAction(message)
    }

    override fun onMessage(data: ByteArray) {
        parseAndSendAction(String(data, Charset.defaultCharset()))
    }

    private fun parseAndSendAction(message: String) {
        val parsed = JSONObject(message)
        if (parsed.has("volume")) {
            val volume = parsed.getDouble("volume")
            this.handleVolume(volume)
            sendAction(ACTION_VOLUME_RECEIVED, { intent -> intent.putExtra(ACTION_EXTRA_VOLUME, volume) })
        } else {
            Log.w(TAG, "Unparsed message from websocket received. Ignoring")
        }
    }
    private fun doNotify(modify: ((NotificationCompat.Builder) -> Unit)?){
        val notification = this.createNotification(modify)
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID,notification)
    }

    fun configureVolumeThreshold(threshold:Double){
        this.volumeThreshold=threshold
    }

    fun handleVolume(volume:Double) {
//        if (this.notification != null) {
//                this.notification?.setProgress(100, (volume * 100.0).toInt(), false)
//                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID,this.notification?.build())
//            }
//        }
        if (volume > this.volumeThreshold){
            doNotify({builder -> builder.setVibrate(longArrayOf(0,100))})
        }
    }

    override fun onDisconnect(code: Int, reason: String) {
        Log.i(TAG, "Websocket onDisconnect()")
        Log.i(TAG, "Code: $code - Reason: $reason")
        sendAction(ACTION_DISCONNECTED, null)
    }

    override fun onError(error: Exception) {
        Log.e(TAG, "Websocket connection: " + error.toString())
        sendAction(ACTION_DISCONNECTED, null)
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

    inner class ConnectionServiceBinder : Binder() {
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

        val NOTIFICATION_ID = 101
        val NOTIFICATION_CHANNEL_ID = "babyphone_notifications"

        fun createActionIntentFilter(): IntentFilter {
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