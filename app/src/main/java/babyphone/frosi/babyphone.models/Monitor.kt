package babyphone.frosi.babyphone.models

import android.app.Application
import android.content.BroadcastReceiver
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.View
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import babyphone.frosi.babyphone.*
import com.jjoe64.graphview.series.DataPoint
import com.jjoe64.graphview.series.LineGraphSeries
import com.jjoe64.graphview.series.PointsGraphSeries
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import okhttp3.internal.waitMillis
import org.threeten.bp.Instant
import java.io.InputStream
import java.net.URL
import java.util.*
import java.util.concurrent.TimeUnit


class MonitorViewModel(application: Application) : AndroidViewModel(application) {

    var service: ConnectionService? = null

    val volumeSeries = LineGraphSeries<DataPoint>()
    val volumeUpdated = PublishSubject.create<Volume>()
    val thresholdSeries = LineGraphSeries<DataPoint>()
    val alarmSeries = PointsGraphSeries<DataPoint>()
    val useLights: Boolean = false

    val movementUpdated = PublishSubject.create<Movement>()
    val movementImagesSize = PublishSubject.create<Int>()

    val livePicture = MutableLiveData<Boolean>()
    val imagePager = ImagePager(application)

    val connectionState = MutableLiveData<DeviceConnection.ConnectionState>()

    val nightMode = MutableLiveData<Boolean>()

    val motionDetection = MutableLiveData<Boolean>()


    private var serviceBroadcastReceiver: BroadcastReceiver? = null

    private val loaderJob = Job()
    private val loaderScope = CoroutineScope(Dispatchers.IO + loaderJob)

    private val uiScope = UiLifecycleScope()

    private val serviceDisposables = CompositeDisposable()
    private var connDisposables = CompositeDisposable()

    init {
        livePicture.value = false
        motionDetection.value = false
    }

    fun onSwitchNightMode(view: View, nightMode: Boolean) {
        Log.i(TAG, "switching night mode to $nightMode")
        service?.conn?.updateConfig(Configuration(nightMode = nightMode))
    }

    fun onSwitchMotionDetection(view: View, motionDetection: Boolean) {
        service?.conn?.updateConfig(Configuration(motionDetection = motionDetection))
    }

    fun connectService(service: ConnectionService) {
        this.service = service

        serviceDisposables.add(service.connections.subscribe { conn ->
            this.updateConnection(conn)
        })

        serviceDisposables.add(service.volumeThreshold
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    this.thresholdSeries.appendData(DataPoint(Date(), it.toDouble()), true, MAX_GRAPH_ELEMENTS, true)
                })
    }

    private fun updateConnection(conn: DeviceConnection) {

        // clear subscribers of old connection, if any
        connDisposables.clear()
        connDisposables = CompositeDisposable()

        connDisposables.add(conn.volumes.observeOn(AndroidSchedulers.mainThread()).subscribe { vol ->
            volumeSeries
                    .appendData(DataPoint(vol.time, vol.volume.toDouble()), true, MAX_GRAPH_ELEMENTS)
        })

        connDisposables.add(
                conn.volumes
                        .debounce(100, TimeUnit.MILLISECONDS)
                        .subscribe { volumeUpdated.onNext(it) }
        )

        connDisposables.add(
                conn.config.subscribe { cfg ->
                    Log.d(TAG, "got new config$cfg")
                    if (cfg.motionDetection != null) {
                        motionDetection.postValue(cfg.motionDetection)
                    }
                    if (cfg.nightMode != null) {
                        nightMode.postValue(cfg.nightMode)
                    }
                }
        )

        connDisposables.add(conn.movement.subscribe { movementUpdated.onNext(it) })


        val movementPictureLoadedDisp = conn.movement
                .observeOn(Schedulers.io())
                .startWith(Movement(0.0, false, 0))
                .map {
                    try {
                        val motionURL = "http://${conn.device.hostname}:8081/latest"
                        val url = URL(motionURL)
                        Log.d(TAG, "loading image from $motionURL in thread ${Thread.currentThread().name}")
                        val connection = url.openConnection()
                        val pictureTime = connection.getHeaderField("picture-time")
                        val inputStream = connection.content as InputStream
                        Log.d(TAG, "...success!")
                        val time = Instant.ofEpochSecond(pictureTime.toLong())

                        Babyphone.TimedDrawable(
                                Drawable.createFromStream(inputStream, time.toEpochMilli().toString()),
                                time
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Error loading image $e")
                        Babyphone.TimedDrawable.INVALID
                    }
                }
                .filter { it != Babyphone.TimedDrawable.INVALID }
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    Log.d(TAG, "adding image to imagepager in thread ${Thread.currentThread().name}")

                    this.imagePager.addImage(it)
                }

        connDisposables.add(movementPictureLoadedDisp)

    }

    override fun onCleared() {
        super.onCleared()
        connDisposables.clear()
        serviceDisposables.clear()
    }


    companion object {
        val MAX_GRAPH_ELEMENTS = 120
        const val TAG = "monitor-mv"
    }

    class Factory(val application: Application) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MonitorViewModel(application) as T
        }
    }
}

