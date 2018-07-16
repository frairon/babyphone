package babyphone.frosi.babyphone

import android.app.PendingIntent.getActivity
import android.content.*
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.support.v4.content.ContextCompat
import android.support.v4.content.LocalBroadcastManager
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.SurfaceView
import android.view.View
import android.widget.*
import android.widget.CompoundButton
import com.jjoe64.graphview.GraphView
import com.jjoe64.graphview.helper.DateAsXAxisLabelFormatter
import com.jjoe64.graphview.series.DataPoint
import com.jjoe64.graphview.series.LineGraphSeries
import kotlinx.android.synthetic.main.activity_babyphone.*
import java.text.SimpleDateFormat


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

        initVolumeGraph()

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
        val connect = this.findViewById<View>(R.id.switch_connection) as Switch
        connect.requestFocus()
        connect.setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked) {
                val hostInput = this.findViewById<View>(R.id.text_host) as TextView
                Log.i("websocket", "connecting to " + hostInput.text.toString())
                val componentName = this.startService(Intent(this, ConnectionService::class.java).putExtra("host", hostInput.text.toString()))
                if (componentName == null) {
                    Log.e("websocket", "Could not start connection service. does not exist")
                }
                this.bindService(Intent(this, ConnectionService::class.java), this, 0)
            } else {
                this.service?.disconnect()
            }
        })

        val btnShutdown = this.findViewById<View>(R.id.button_shutdown) as ImageButton
        btnShutdown.isEnabled = false
        btnShutdown.setOnClickListener {
            this.service?.shutdown()
        }

        val volumeSeek = this.findViewById<View>(R.id.vol_alarm_seeker) as SeekBar
        setVolumeThresholdIcon()
        volumeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}

            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                activity.service?.volumeThreshold = volumeSeek.progress.toDouble() / 100.0

                activity.setGraphThreshold(progress.toDouble())

                activity.setVolumeThresholdIcon()
            }
        })
        setGraphThreshold(volumeSeek.progress.toDouble())
        val connecting = activity.findViewById<View>(R.id.spinner_connecting) as ProgressBar
        connecting.visibility = View.GONE
        connectToServiceBroadcast()

        this.player?.initialize()
    }

    fun setVolumeThresholdIcon(){

        val volumeSeek= this.findViewById<View>(R.id.vol_alarm_seeker) as SeekBar
        val progress = volumeSeek.progress
        if(progress==0||progress==100){
            volumeSeek.thumb = ContextCompat.getDrawable(this, R.drawable.ic_volume_off_black_24dp)
        }else if(progress < 50){
            volumeSeek.thumb = ContextCompat.getDrawable(this, R.drawable.ic_volume_down_black_24dp)
        }else{
            volumeSeek.thumb = ContextCompat.getDrawable(this, R.drawable.ic_volume_up_black_24dp)
        }
    }

    private var volumeSeries = LineGraphSeries<DataPoint>()
    private var thresholdSeries = LineGraphSeries<DataPoint>()


    fun setGraphThreshold(threshold: Double) {
        this.thresholdSeries.resetData(arrayOf(DataPoint(0.0, threshold), DataPoint(Double.MAX_VALUE, threshold)))
    }

    fun initVolumeGraph() {
        val graph = findViewById(R.id.graph_volume) as GraphView
        graph.addSeries(this.volumeSeries)
        graph.addSeries(this.thresholdSeries)

        graph.gridLabelRenderer.labelFormatter = DateAsXAxisLabelFormatter(this, SimpleDateFormat("HH:mm:ss"))
//        graph.gridLabelRenderer.numHorizontalLabels = 6

        this.thresholdSeries.color=Color.RED
        this.thresholdSeries.thickness=3
        val vp = graph.viewport
        vp.isScrollable = true
        vp.isScalable = true
        vp.isXAxisBoundsManual = true
        vp.isYAxisBoundsManual = true
        vp.setMinY(0.0)
        vp.setMaxY(100.0)
        vp.setMinX(this.volumeSeries.lowestValueX)
        vp.setMaxX(this.volumeSeries.highestValueX)
    }

    fun setConnectionStatus(status: String) {
        val btnShutdown = this.findViewById<View>(R.id.button_shutdown) as ImageButton
        val connecting = this.findViewById<View>(R.id.spinner_connecting) as ProgressBar
        val connect = this.findViewById<View>(R.id.switch_connection) as Switch

        when (status) {
            ConnectionService.ACTION_CONNECTING -> {
                runOnUiThread {
                    connecting.visibility = View.VISIBLE
                    btnShutdown.isEnabled = false
                    connect.text = getString(R.string.switchConnect_Connecting)
                }
            }
            ConnectionService.ACTION_CONNECTED -> {
                runOnUiThread {
                    connecting.visibility = View.GONE
                    btnShutdown.isEnabled = true
                    connect.text = getString(R.string.switchConnect_Connected)
                }
            }
            ConnectionService.ACTION_DISCONNECTED -> {
                runOnUiThread {
                    connect.isChecked = false
                    connecting.visibility = View.GONE
                    btnShutdown.isEnabled = false
                    connect.text = getString(R.string.switchConnect_Disconnected)
                }
            }
            else -> throw IllegalArgumentException("Invalid status $status")
        }
    }

    fun connectToServiceBroadcast() {
        val activity = this
        LocalBroadcastManager.getInstance(this).registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        when (intent.action) {
                            ConnectionService.ACTION_CONNECTING, ConnectionService.ACTION_CONNECTED, ConnectionService.ACTION_DISCONNECTED -> {
                                setConnectionStatus(intent.action)
                            }
                            ConnectionService.ACTION_VOLUME_RECEIVED -> {
                                val vol = intent.getSerializableExtra(ConnectionService.ACTION_EXTRA_VOLUME) as Volume?
                                if (vol == null) {
                                    Log.e("babyphone", "Receivd null volume in volume intent. Ignoring.")
                                    return
                                }
                                val newPoint = DataPoint(vol.time, vol.volume * 100)
                                runOnUiThread {
                                    volumeSeries.appendData(newPoint, true, 100)
                                    val graph = activity.findViewById(R.id.graph_volume) as GraphView
                                    graph.viewport.setMinX(volumeSeries.lowestValueX)
                                    graph.viewport.setMaxX(volumeSeries.highestValueX)
                                }


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
            this.unbindService(this)
            this.stopService(Intent(this, ConnectionService::class.java))
            this.finish()
        }
    }

    override fun onDestroy() {
        this.player?.destroy()
        super.onDestroy()
    }

}

