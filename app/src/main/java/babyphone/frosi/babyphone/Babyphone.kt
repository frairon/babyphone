package babyphone.frosi.babyphone

import android.content.*
import android.os.Bundle
import android.os.IBinder
import android.support.v4.content.LocalBroadcastManager
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.SurfaceView
import android.view.View
import android.widget.*
import kotlinx.android.synthetic.main.activity_babyphone.*

class Babyphone : AppCompatActivity(), ServiceConnection {
    override fun onServiceDisconnected(name: ComponentName?) {
        this.service = null
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        this.service = (service as ConnectionService.ConnectionServiceBinder).service
    }

    var player: Player? = null

    var currentMedia: String = "rtsp://babyphone.fritz.box:8554/audiovideo"

    var service: ConnectionService? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_babyphone)
        setSupportActionBar(toolbar)
        try {
            this.player = Player(this)
        } catch (e: Exception) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val sv = this.findViewById<View>(R.id.surface_video) as SurfaceView
        val sh = sv.holder
        sh.addCallback(this.player)

        val play = this.findViewById<View>(R.id.button_play) as ImageButton
        play.setOnClickListener {
            this.player?.play(currentMedia)
        }

        val pause = this.findViewById<View>(R.id.button_stop) as ImageButton
        pause.setOnClickListener {
            this.player?.pause()
        }
        val activity = this
        val connect = this.findViewById<View>(R.id.button_connect) as Button
        connect.setOnClickListener {
            val hostInput = this.findViewById<View>(R.id.text_host) as TextView
            Log.i("websocket", "connecting to " + hostInput.text.toString())
            val componentName = this.startService(Intent(this, ConnectionService::class.java).putExtra("host", hostInput.text.toString()))
            if (componentName == null) {
                Log.e("websocket", "Could not start connection service. does not exist")
            }
            this.bindService(Intent(this, ConnectionService::class.java), this, 0)
        }

        val volumeSeek = this.findViewById<View>(R.id.vol_alarm_seeker) as SeekBar
        volumeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}

            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                activity.service?.volumeThreshold = volumeSeek.progress.toDouble() / 100.0
            }


        })


        LocalBroadcastManager.getInstance(this).registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        when (intent.action) {
                            ConnectionService.ACTION_CONNECTING -> {
                                val tv = activity.findViewById<View>(R.id.status_text) as TextView
                                runOnUiThread { tv.text = "connecting..." }
                            }
                            ConnectionService.ACTION_CONNECTED -> {
                                val tv = activity.findViewById<View>(R.id.status_text) as TextView
                                runOnUiThread { tv.text = "connected" }
                            }
                            ConnectionService.ACTION_DISCONNECTED -> {
                                val tv = activity.findViewById<View>(R.id.status_text) as TextView
                                runOnUiThread { tv.text = "disconnected" }
                            }
                            ConnectionService.ACTION_VOLUME_RECEIVED -> {
                                val volBar = activity.findViewById<View>(R.id.prog_vol_level) as ProgressBar
                                runOnUiThread { volBar.progress = (intent.getDoubleExtra(ConnectionService.ACTION_EXTRA_VOLUME, 0.0) * 100.0).toInt() }
                            }
                            else -> {
                                Log.w("websocket", "unhandled action in intent:" + intent.action)
                            }
                        }
                        val message = intent.getStringExtra("message")
                    }
                },
                ConnectionService.createActionIntentFilter()
        )

        this.player?.initialize()
    }


    // Called from native code when the size of the media changes or is first detected.
    // Inform the video surface about the new size and recalculate the layout.
    fun onMediaSizeChanged(width: Int, height: Int) {
        Log.i("GStreamer", "Media size changed to " + width + "x" + height)
        val gsv = this.findViewById<View>(R.id.surface_video) as GStreamerSurfaceView
        gsv.media_width = width
        gsv.media_height = height
        runOnUiThread { gsv.requestLayout() }
    }

    // Called from native code. This sets the content of the TextView from the UI thread.
    fun setMessage(message: String) {
        val tv = this.findViewById<View>(R.id.textview_message) as TextView
        runOnUiThread { tv.text = message }
    }

    // Called from native code. Native code calls this once it has created its pipeline and
    // the main loop is running, so it is ready to accept commands.
    fun onGStreamerInitialized() {
        Log.i("GStreamer", "GStreamer initialized:")
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_babyphone, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        return when (item.itemId) {
            R.id.action_settings -> true
            else -> super.onOptionsItemSelected(item)
        }
    }

    private var mExitPrompt: Boolean = false

    override fun onBackPressed() {
        if (this.isTaskRoot) {
            if (!mExitPrompt) {
                Toast.makeText(this, "Tab again to exit Babyphone", Toast.LENGTH_SHORT).show()
                this.mExitPrompt = true
                return
            }
            this.stopService(Intent(this, ConnectionService::class.java))
            this.finish()
        }
    }

    override fun onDestroy() {
        this.player?.destroy()
        super.onDestroy()
    }

}

