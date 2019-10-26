package babyphone.frosi.babyphone

import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.*
import android.view.animation.LinearInterpolator
import android.widget.PopupWindow
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import androidx.lifecycle.ViewModelProviders
import babyphone.frosi.babyphone.databinding.ActivityMonitorBinding
import babyphone.frosi.babyphone.databinding.SoundOptionsBinding
import babyphone.frosi.babyphone.databinding.VisualOptionsBinding
import babyphone.frosi.babyphone.models.DeviceViewModel
import babyphone.frosi.babyphone.models.ImagePager
import babyphone.frosi.babyphone.models.MonitorViewModel
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.jakewharton.threetenabp.AndroidThreeTen
import com.jjoe64.graphview.GraphView
import com.jjoe64.graphview.helper.DateAsXAxisLabelFormatter
import com.jjoe64.graphview.series.PointsGraphSeries
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import kotlinx.android.synthetic.main.activity_monitor.*
import kotlinx.android.synthetic.main.conn_details.*
import kotlinx.android.synthetic.main.monitor_audio.*
import kotlinx.android.synthetic.main.monitor_picture.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.threeten.bp.Instant
import java.text.SimpleDateFormat
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine context that automatically is cancelled when UI is destroyed
 */
class UiLifecycleScope : CoroutineScope, LifecycleObserver {

    private lateinit var job: Job
    override val coroutineContext: CoroutineContext
        get() = job + Dispatchers.Main

    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    fun onCreate() {
        job = Job()
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    fun destroy() = job.cancel()
}

class Babyphone : AppCompatActivity(), ServiceConnection, View.OnClickListener {


    var service: ConnectionService? = null

    private val loaderJob = Job()
    private val loaderScope = CoroutineScope(Dispatchers.IO + loaderJob)

    private val uiScope = UiLifecycleScope()

    private val imagePager = ImagePager(this)

    private lateinit var model: MonitorViewModel
    private lateinit var deviceModel: DeviceViewModel

    private val disposables = CompositeDisposable()

    private var motionReloadAnimator: ValueAnimator? = null

    private val player = VideoPlayer()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        model = ViewModelProviders
                .of(this, MonitorViewModel.Factory(this.application))
                .get(MonitorViewModel::class.java)

        deviceModel = ViewModelProviders
                .of(this, DeviceViewModel.Factory(this.application))
                .get(DeviceViewModel::class.java)

        lifecycle.addObserver(uiScope)
        // create the layout and bind the model to it
        val binding = DataBindingUtil.setContentView<ActivityMonitorBinding>(this, R.layout.activity_monitor)
        binding.model = model
        binding.deviceModel = deviceModel
        binding.lifecycleOwner = this

        this.btn_live.setOnClickListener(this)
        this.hide_menu.setOnClickListener(this)
        this.inactive_blocker.setOnClickListener { true }
        this.btn_visual_settings.setOnClickListener(this)
        this.btn_sound_settings.setOnClickListener(this)

//        val coordinatorLayout = findViewById<View>(R.id.coordinator) as CoordinatorLayout
        setSupportActionBar(findViewById<View>(R.id.toolbar) as Toolbar)

        val actionbar = getSupportActionBar()
        if (actionbar != null) {
            actionbar.setDisplayHomeAsUpEnabled(true)
            actionbar.title = "Babyphone"
        }

        val collapsingToolbar = findViewById<View>(R.id.collapsing_toolbar) as CollapsingToolbarLayout
        collapsingToolbar.title = getString(R.string.app_name)

        AndroidThreeTen.init(this);

        initVolumeGraph()

        this.liveVideo.holder.addCallback(this.player)

        this.images.adapter = this.model.imagePager
        disposables.add(this.model.movementUpdated
                .filter { it.intervalMillis != 0L }
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    if (motionReloadAnimator != null) {
                        motionReloadAnimator?.cancel()
                        motionReloadAnimator?.removeAllUpdateListeners()
                        this.motionReloadAnimator = null
                    }
                    Log.d(TAG, "movement updated, starting animation $it")

                    val ani = ValueAnimator.ofInt(0, 1000)
                    ani.duration = it.intervalMillis
                    ani.interpolator = LinearInterpolator()
                    ani.addUpdateListener {
                        this.prog_refresh_timeout.progress = it.animatedValue as Int
                    }
                    motionReloadAnimator = ani

                    ani.start()
                })

        ConnectionService.startService(this)
        this.bindService(Intent(this, ConnectionService::class.java), this, 0)
    }

    var popup: PopupWindow? = null

    override fun onClick(v: View?) {
        if (v == null) {
            return
        }
        when (v.id) {

            R.id.hide_menu -> {
                val abl = findViewById<View>(R.id.app_bar_layout) as AppBarLayout
                abl.setExpanded(false)
            }
            R.id.btn_live -> {
                var live = this.model.livePicture.value == true
                live = !live
                this.model.livePicture.postValue(live)
                if (live) {
                    this.player.start()
                } else {
                    this.player.stop()
                }
            }
            R.id.btn_visual_settings -> {

                val inflater = this.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
                val binding = DataBindingUtil.inflate<VisualOptionsBinding>(inflater, R.layout.visual_options, null, false)
                val popup = PopupWindow(binding.root,
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                binding.model = this.model
                binding.lifecycleOwner = this

                popup.isFocusable = true
                popup.isTouchable = true
                popup.showAsDropDown(this.btn_visual_settings)
            }
            R.id.btn_sound_settings -> {

                val inflater = this.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
                val binding = DataBindingUtil.inflate<SoundOptionsBinding>(inflater, R.layout.sound_options, null, false)
                val popup = PopupWindow(binding.root,
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                binding.model = this.model
                binding.lifecycleOwner = this


                popup.isFocusable = true
                popup.isTouchable = true
                popup.showAsDropDown(this.btn_sound_settings)
            }
        }
    }

    class TimedDrawable(val drawable: Drawable, val instant: Instant) {
        companion object {
            val INVALID = TimedDrawable(ColorDrawable(), Instant.now())
        }
    }

    private fun initVolumeGraph() {
        val graph = findViewById<GraphView>(R.id.graph_volume)

        graph.addSeries(this.model.thresholdSeries)
        graph.addSeries(this.model.volumeSeries)
        graph.addSeries(this.model.alarmSeries)

        this.model.alarmSeries.shape = PointsGraphSeries.Shape.POINT
        // TODO add color to style/resources
        this.model.alarmSeries.color = Color.argb(128, 255, 0, 0)
        this.model.alarmSeries.size = 8f

        graph.gridLabelRenderer.labelFormatter = DateAsXAxisLabelFormatter(this, SimpleDateFormat("HH:mm:ss"))
        this.model.volumeSeries.thickness = 4

        this.model.thresholdSeries.isDrawBackground = true

        // TODO add colors to style/resources
        this.model.thresholdSeries.backgroundColor = Color.argb(25, 0, 255, 0)
        this.model.thresholdSeries.color = Color.argb(30, 0, 255, 0)
        this.model.thresholdSeries.thickness = 2

        val vp = graph.viewport
        vp.isScrollable = false
        vp.isScalable = false
        vp.isXAxisBoundsManual = true
        vp.isYAxisBoundsManual = true
        vp.setMinY(0.0)
        vp.setMaxY(100.0)
        vp.setMinX(this.model.volumeSeries.lowestValueX)
        vp.setMaxX(this.model.volumeSeries.highestValueX)

        disposables.add(this.model.volumeUpdated.observeOn(AndroidSchedulers.mainThread()).subscribe { vol ->
            graph.viewport.setMinX(this.model.volumeSeries.lowestValueX)
            graph.viewport.setMaxX(this.model.volumeSeries.highestValueX)
        })

        disposables.add(this.model.imagePager.sizeUpdated
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    Log.d(TAG, "adding image to imagepager in thread ${Thread.currentThread().name}")
                    this.images.currentItem = it - 1
                })

        graph.gridLabelRenderer.numHorizontalLabels = 3
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        this.service = null
    }

    companion object {
        const val TAG = "babyphone"
        const val MAX_GRAPH_ELEMENTS = 300
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {

        if (service == null) {
            return
        }
        Log.i("babyphone", "service connected")
        val svc = (service as ConnectionService.ConnectionServiceBinder).service


        this.service = svc

        this.model.connectService(svc)
        this.deviceModel.connectService(svc)
        this.player.connectService(svc)

    }

    override fun onResume() {
        super.onResume()

        if (this.model.livePicture.value == true) {
            this.player.start()
        }
    }

    override fun onPause() {
        super.onPause()
        if (this.model.livePicture.value == true) {
            this.player.stop()
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        if (menu == null) {
            return super.onPrepareOptionsMenu(menu)
        }
        val mi = menu!!.findItem(R.id.action_edit) as MenuItem
        if (x) {
            mi.icon = ContextCompat.getDrawable(this, R.drawable.ic_headset_black_24dp)
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_babyphone, menu)
        return true
    }

    var x: Boolean = false

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        return when (item.itemId) {
            R.id.action_edit -> {
                x = !x
                this.invalidateOptionsMenu()
                val abl = findViewById<View>(R.id.app_bar_layout) as AppBarLayout
                abl.setExpanded(true)
                return true

            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        Log.i("babyphone", "onDestroy")
        this.unbindService(this)
        lifecycle.removeObserver(uiScope)

        disposables.clear()

        super.onDestroy()
    }

}

