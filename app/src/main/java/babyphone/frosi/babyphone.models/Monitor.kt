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
import io.reactivex.rxkotlin.addTo
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

    val audioPlaying = MutableLiveData<Boolean>()


    val alarmEnabled = MutableLiveData<Boolean>()
    val autoSoundEnabled = MutableLiveData<Boolean>()

    private val serviceDisposables = CompositeDisposable()
    private var connDisposables = CompositeDisposable()

    init {
        livePicture.value = false
        motionDetection.value = false
        audioPlaying.value = false
    }

    fun onSwitchNightMode(view: View, nightMode: Boolean) {
        service?.conn?.updateConfig(Configuration(nightMode = nightMode))
    }

    fun onSwitchAlarmEnabled(view: View, alarmEnabled: Boolean) {
        service?.setAlarmEnabled(alarmEnabled)
    }


    fun onSwitchMotionDetection(view: View, motionDetection: Boolean) {
        service?.conn?.updateConfig(Configuration(motionDetection = motionDetection))
    }

    fun onAutoSoundEnabled(view: View, autoSoundEnabled: Boolean) {
        service?.setAutoSoundEnabled(autoSoundEnabled)
    }

    fun onToggleAudio() {
        service?.toggleAudio()
    }

    fun connectService(service: ConnectionService) {
        this.service = service

        service.connections
                .subscribe { conn ->
                    this.updateConnection(conn)
                }
                .addTo(serviceDisposables)

        service.volumeThreshold
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    this.thresholdSeries.appendData(DataPoint(Date(), it.toDouble()), true, MAX_GRAPH_ELEMENTS, true)
                }
                .addTo(serviceDisposables)

        service.alarm
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    this.alarmSeries.appendData(DataPoint(it.time, it.volume.toDouble()), false, MAX_GRAPH_ELEMENTS, true)
                }
                .addTo(serviceDisposables)

        service.alarmEnabled
                .subscribe {
                    alarmEnabled.postValue(it)
                }
                .addTo(serviceDisposables)
        service.autoSoundEnabled
                .subscribe {
                    autoSoundEnabled.postValue(it)
                }
                .addTo(serviceDisposables)

        service.audioPlayStatus
                .subscribe {
                    audioPlaying.postValue(it)
                }
                .addTo(serviceDisposables)
    }

    private fun updateConnection(conn: DeviceConnection) {

        // clear subscribers of old connection, if any
        connDisposables.clear()
        connDisposables = CompositeDisposable()

        conn.volumes
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { vol ->
                    volumeSeries
                            .appendData(DataPoint(vol.time, vol.volume.toDouble()), true, MAX_GRAPH_ELEMENTS)
                }
                .addTo(connDisposables)


        // TODO replace debounce with sample
        conn.volumes
                .debounce(100, TimeUnit.MILLISECONDS)
                .subscribe { volumeUpdated.onNext(it) }
                .addTo(connDisposables)

        conn.config
                .subscribe { cfg ->
                    Log.d(TAG, "got new config$cfg")
                    if (cfg.motionDetection != null) {
                        motionDetection.postValue(cfg.motionDetection)
                    }
                    if (cfg.nightMode != null) {
                        nightMode.postValue(cfg.nightMode)
                    }
                }
                .addTo(connDisposables)

        conn.movement.subscribe { movementUpdated.onNext(it) }
                .addTo(connDisposables)


        val movementPictureLoadedDisp = conn.movement

                .startWith(Movement(0.0, false, 0))
                .map {
                    try {
                        val motionURL = "http://${conn.device.hostname}:8081/latest"
                        val url = URL(motionURL)
                        Log.d(TAG, "loading image from $motionURL in thread ${Thread.currentThread().name}")
                        val connection = url.openConnection()
                        val pictureTime = connection.getHeaderField("picture-time")
                        val inputStream = connection.content as InputStream
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
                .subscribeOn(Schedulers.io())
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

