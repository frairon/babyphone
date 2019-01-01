package babyphone.frosi.babyphone

import android.content.*
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.support.constraint.Group
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
import com.jakewharton.threetenabp.AndroidThreeTen
import com.jjoe64.graphview.GraphView
import com.jjoe64.graphview.helper.DateAsXAxisLabelFormatter
import com.jjoe64.graphview.series.DataPoint
import com.jjoe64.graphview.series.LineGraphSeries
import com.jjoe64.graphview.series.PointsGraphSeries
import kotlinx.android.synthetic.main.activity_babyphone.*
import org.threeten.bp.Instant
import java.text.SimpleDateFormat
import java.util.*


class Babyphone : AppCompatActivity(), ServiceConnection {

    var player: Player? = null

    var service: ConnectionService? = null


    private var lastExitPrompt: Instant? = null
    private var volumeSeries = LineGraphSeries<DataPoint>()
    private var thresholdSeries = LineGraphSeries<DataPoint>()
    private var alarmSeries = PointsGraphSeries<DataPoint>()

    enum class StreamMode(val suffix: String) {
        Audio("audio"),
        AudioVideo("audiovideo")
    }

    fun createUrl(streamMode: StreamMode): String {
        val hostInput = this.findViewById<View>(R.id.text_host) as TextView
        return "rtsp://" + hostInput.text.toString() + ":8554/" + streamMode.suffix
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_babyphone)
        setSupportActionBar(toolbar)
        AndroidThreeTen.init(this);

        try {
            this.player = Player(this)
        } catch (e: Exception) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        initVolumeGraph()

        val componentName = this.startService(Intent(this, ConnectionService::class.java))
        if (componentName == null) {
            throw RuntimeException("Could not start connection service. does not exist")
        }

        val sv = this.findViewById<View>(R.id.surface_video) as SurfaceView
        val sh = sv.holder
        sh.addCallback(this.player)

//        this.findViewById<View>(R.id.surface_video).visibility = View.INVISIBLE

        val playAudio = this.findViewById<View>(R.id.button_audio) as ImageButton
        playAudio.setOnClickListener {
            this.player?.play(createUrl(StreamMode.Audio))
//            this.findViewById<View>(R.id.surface_video).visibility = View.INVISIBLE
        }
        val playVideo = this.findViewById<View>(R.id.button_video) as ImageButton
        playVideo.setOnClickListener {
            this.player?.play(createUrl(StreamMode.AudioVideo))
//            this.findViewById<View>(R.id.surface_video).visibility = View.VISIBLE
        }

        val pause = this.findViewById<View>(R.id.button_stop) as ImageButton
        pause.setOnClickListener {
            this.player?.pause()
//            this.findViewById<View>(R.id.surface_video).visibility = View.INVISIBLE
        }
        val activity = this
        val connect = this.findViewById<View>(R.id.switch_connection) as Switch
        connect.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                val hostInput = this.findViewById<View>(R.id.text_host) as TextView
                Log.i("websocket", "connecting to " + hostInput.text.toString())
                this.service?.connectToHost(hostInput.text.toString())
            } else {
                this.service?.disconnect()
            }
        }



        val btnLights = this.findViewById<View>(R.id.btn_lights) as ImageButton
        btnLights.setOnClickListener {
            this.service?.toggleLights();
        }

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
                activity.service?.volumeThreshold = volumeSeek.progress

                activity.setGraphThreshold(progress)

                activity.setVolumeThresholdIcon()
            }
        })
        setGraphThreshold(volumeSeek.progress)

        val volAlarmAuto = this.findViewById<View>(R.id.vol_alarm_auto) as Switch
        volAlarmAuto.setOnCheckedChangeListener { _, isChecked ->
            volumeSeek.isEnabled = !isChecked
            activity.service?.autoVolumeLevel=isChecked
        }

        val volAlarmEnabled = this.findViewById<View>(R.id.vol_alarm_enabled) as Switch
        volAlarmEnabled.setOnCheckedChangeListener { _, isChecked ->
            volAlarmAuto.isEnabled = isChecked
            volumeSeek.isEnabled = isChecked && !volAlarmAuto.isChecked
            this.service?.alarmsEnabled = isChecked
        }

        volAlarmAuto.isEnabled = volAlarmEnabled.isChecked
        volumeSeek.isEnabled = volAlarmEnabled.isChecked && !volAlarmAuto.isChecked
        activity.service?.autoVolumeLevel=volAlarmAuto.isChecked


        val connecting = activity.findViewById<View>(R.id.spinner_connecting) as ProgressBar
        connecting.visibility = View.GONE


        connectToServiceBroadcast()
        this.player?.initialize()
        this.bindService(Intent(this, ConnectionService::class.java), this, 0)

    }

    fun setVolumeThresholdIcon() {

        val volumeSeek = this.findViewById<View>(R.id.vol_alarm_seeker) as SeekBar
        val progress = volumeSeek.progress
        if (progress == 0 || progress == 100) {
            volumeSeek.thumb = ContextCompat.getDrawable(this, R.drawable.ic_volume_off_black_24dp)
        } else if (progress < 50) {
            volumeSeek.thumb = ContextCompat.getDrawable(this, R.drawable.ic_volume_down_black_24dp)
        } else {
            volumeSeek.thumb = ContextCompat.getDrawable(this, R.drawable.ic_volume_up_black_24dp)
        }
    }

    fun setGraphThreshold(threshold: Int) {
        this.thresholdSeries.resetData(arrayOf(DataPoint(0.0, threshold.toDouble()), DataPoint(Double.MAX_VALUE, threshold.toDouble())))
    }

    fun initVolumeGraph() {
        val graph = findViewById(R.id.graph_volume) as GraphView

        graph.addSeries(this.volumeSeries)
        graph.addSeries(this.thresholdSeries)
        graph.addSeries(this.alarmSeries)

        this.alarmSeries.shape = PointsGraphSeries.Shape.TRIANGLE
        this.alarmSeries.color = Color.MAGENTA

        graph.gridLabelRenderer.labelFormatter = DateAsXAxisLabelFormatter(this, SimpleDateFormat("HH:mm:ss"))
        this.volumeSeries.thickness = 4


        this.thresholdSeries.color = Color.RED
        this.thresholdSeries.thickness = 2
        val vp = graph.viewport
        vp.isScrollable = true
        vp.isScalable = true
        vp.isXAxisBoundsManual = true
        vp.isYAxisBoundsManual = true
        vp.setMinY(0.0)
        vp.setMaxY(100.0)
        vp.setMinX(this.volumeSeries.lowestValueX)
        vp.setMaxX(this.volumeSeries.highestValueX)

        graph.getGridLabelRenderer().setNumHorizontalLabels(3)
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        this.service = null
    }

    companion object {
        val MAX_GRAPH_ELEMENTS = 120
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        this.service = (service as ConnectionService.ConnectionServiceBinder).service

        if (this.service == null) {
            return
        }
        this.setConnectionStatus(this.service!!.connectionState, true)

        this.initVolumeHistory()

        val volumeSeek = this.findViewById<View>(R.id.vol_alarm_seeker) as SeekBar
        volumeSeek.progress = this.service!!.volumeThreshold
        this.setGraphThreshold(volumeSeek.progress)

        val volAlarmAuto = this.findViewById<View>(R.id.vol_alarm_auto) as Switch
        this.service!!.autoVolumeLevel=volAlarmAuto.isChecked


    }

    fun initVolumeHistory() {
        if (this.service == null) {
            return
        }

        val alarmPoints = this.service!!.history.alarms.map { it -> DataPoint(Date(it.toEpochMilli()), 0.0) }
        this.alarmSeries.resetData(alarmPoints.toTypedArray())

        val dataPoints = this.service!!.history.volumes.map { it -> DataPoint(it.time, it.volume.toDouble()) }
        this.volumeSeries.resetData(dataPoints.toTypedArray())

        val graph = this.findViewById(R.id.graph_volume) as GraphView?

        graph?.viewport?.setMinX(volumeSeries.lowestValueX)
        graph?.viewport?.setMaxX(volumeSeries.highestValueX)
    }

    fun setConnectionStatus(state: ConnectionService.ConnectionState, setButton: Boolean = false) {
        val btnShutdown = this.findViewById<View>(R.id.button_shutdown) as ImageButton
        val playerGroup = this.findViewById<View>(R.id.player_group) as Group
        val connecting = this.findViewById<View>(R.id.spinner_connecting) as ProgressBar
        val connect = this.findViewById<View>(R.id.switch_connection) as Switch

        when(state){
            ConnectionService.ConnectionState.Connected -> {
                runOnUiThread {
                    playerGroup.visibility= View.VISIBLE
                }
            }
            else ->{
                runOnUiThread {
                    playerGroup.visibility= View.GONE
                }
            }
        }
        when (state) {
            ConnectionService.ConnectionState.Connecting -> {
                runOnUiThread {
                    connecting.visibility = View.VISIBLE
                    btnShutdown.isEnabled = false
                    connect.text = getString(R.string.switchConnect_Connecting)
                    if (setButton) connect.isChecked = true
                }
            }
            ConnectionService.ConnectionState.Connected -> {
                runOnUiThread {
                    connecting.visibility = View.GONE
                    btnShutdown.isEnabled = true
                    connect.text = getString(R.string.switchConnect_Connected)
                    if (setButton) connect.isChecked = true
                }
            }
            ConnectionService.ConnectionState.Disconnected -> {
                runOnUiThread {
                    connect.isChecked = false
                    connecting.visibility = View.GONE
                    btnShutdown.isEnabled = false
                    connect.text = getString(R.string.switchConnect_Disconnected)
                    if (setButton) connect.isChecked = false
                }
            }
        }
    }

    private fun connectToServiceBroadcast() {
        val activity = this
        LocalBroadcastManager.getInstance(this).registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        when (intent.action) {
                            ConnectionService.ConnectionState.findState(intent.action)?.action -> {
                                setConnectionStatus(ConnectionService.ConnectionState.findState(intent.action)!!)
                            }
                            ConnectionService.ACTION_VOLUME_RECEIVED -> {
                                val vol = intent.getSerializableExtra(ConnectionService.ACTION_EXTRA_VOLUME) as Volume?
                                if (vol == null) {
                                    Log.e("babyphone", "Receivd null volume in volume intent. Ignoring.")
                                    return
                                }
                                activity.addVolumeToGraph(vol)
                            }
                            ConnectionService.ACTION_ALARM_TRIGGERED -> {
                                activity.alarmSeries.appendData(DataPoint(Date(), 0.0), false, MAX_GRAPH_ELEMENTS)
                            }
                            ConnectionService.ACTION_AUTOTHRESHOLD_UPDATED ->{
                                val volume = intent.getIntExtra(ConnectionService.ACTION_EXTRA_VOLUME, 50)
                                setGraphThreshold(volume)
                                val volumeSeek = activity.findViewById<View>(R.id.vol_alarm_seeker) as SeekBar
                                volumeSeek.progress = volume
                            }
                            else -> {
                                Log.w("websocket", "unhandled action in intent:" + intent.action)
                            }
                        }
                    }
                },
                ConnectionService.createActionIntentFilter()
        )
    }


    fun addVolumeToGraph(vol: Volume) {
        val newPoint = DataPoint(vol.time, vol.volume.toDouble())
        runOnUiThread {
            volumeSeries.appendData(newPoint, true, MAX_GRAPH_ELEMENTS)
            val graph = this.findViewById(R.id.graph_volume) as GraphView
            graph.viewport.setMinX(volumeSeries.lowestValueX)
            graph.viewport.setMaxX(volumeSeries.highestValueX)
        }
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

    override fun onBackPressed() {
        if (this.isTaskRoot) {

            if (lastExitPrompt == null || (Instant.now().toEpochMilli() - lastExitPrompt!!.toEpochMilli()) > 2000) {
                Toast.makeText(this, "Tab again to exit Babyphone", Toast.LENGTH_SHORT).show()
                this.lastExitPrompt = Instant.now()
                return
            }
            this.stopService(Intent(this, ConnectionService::class.java))
            this.finish()
        }
    }

    override fun onDestroy() {
        this.player?.destroy()
        this.unbindService(this)
        super.onDestroy()
    }

}

