package babyphone.frosi.babyphone.models

import android.app.Application
import android.content.BroadcastReceiver
import android.graphics.drawable.Drawable
import android.graphics.drawable.PictureDrawable
import android.util.Log
import android.view.View
import android.widget.CompoundButton
import android.widget.SeekBar
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
import kotlinx.coroutines.*
import okhttp3.internal.waitMillis
import org.threeten.bp.Instant
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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

    val movementUpdated = PublishSubject.create<Movement>()

    val livePicture = MutableLiveData<Boolean>()
    val livePictureImageTimestamp = MutableLiveData<Long>()
    val imagePager = ImagePager(application)
    val downloadingImage = MutableLiveData<Boolean>()

    val connectionState = MutableLiveData<DeviceConnection.ConnectionState>()

    val nightMode = MutableLiveData<Boolean>()

    val motionDetection = MutableLiveData<Boolean>()

    val audioPlaying = MutableLiveData<Boolean>()


    val alarmEnabled = MutableLiveData<Boolean>()
    val alarmSnoozing = MutableLiveData<Int>()
    val autoSoundEnabled = MutableLiveData<Boolean>()
    val noiseLevel = MutableLiveData<Int>()

    private val serviceDisposables = CompositeDisposable()
    private var connDisposables = CompositeDisposable()

    init {
        livePicture.value = false
        motionDetection.value = false
        audioPlaying.value = false
        downloadingImage.value = false
        alarmEnabled.value = true
        alarmSnoozing.value = 0
        autoSoundEnabled.value = true
        noiseLevel.value = 2
    }

    fun onSwitchNightMode(view: View, nightMode: Boolean) {
        service?.conn?.updateConfig(Configuration(nightMode = nightMode))
    }

    fun onSwitchAlarmEnabled(view: CompoundButton?, alarmEnabled: Boolean) {
        if (alarmEnabled) {
            service?.enableAlarm()
        } else if (alarmSnoozing.value == 0) {
            service?.disableAlarm()
        }
    }

    fun snoozeAlarm() {
        service?.snoozeAlarm()
    }

    fun isSnoozing(): Boolean {
        return with(alarmSnoozing.value) {
            this != null && this > 0
        }
    }

    fun onSwitchMotionDetection(view: View, motionDetection: Boolean) {
        service?.conn?.updateConfig(Configuration(motionDetection = motionDetection))
    }

    fun onAutoSoundEnabled(view: CompoundButton?, autoSoundEnabled: Boolean) {
        service?.setAutoSoundEnabled(autoSoundEnabled)
    }

    fun createNoiseLevelListener(): SeekBar.OnSeekBarChangeListener {
        return object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                this@MonitorViewModel.service?.setAlarmNoiseLevel(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
    }

    fun onToggleAudio(view: View?) {
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
                    try {
                        this.thresholdSeries.appendData(DataPoint(Date(), it.toDouble()), true, MAX_GRAPH_ELEMENTS, true)
                    } catch (e: IllegalArgumentException) {
                        Log.i(TAG, "got out of order element. ignoring...", e)
                    }

                }
                .addTo(serviceDisposables)

        service.alarm
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    try {
                        this.alarmSeries.appendData(DataPoint(it.time, it.volume.toDouble()), false, MAX_GRAPH_ELEMENTS, true)
                    } catch (e: IllegalArgumentException) {
                        Log.i(TAG, "got out of order element. ignoring...", e)
                    }

                }
                .addTo(serviceDisposables)

        service.alarmTrigger
                .subscribe {
                    alarmEnabled.postValue(it == 0)
                    alarmSnoozing.postValue(it)
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

        service.alarmNoiseLevel
                .subscribe{
                    noiseLevel.postValue(it)
                }
                .addTo(serviceDisposables)
    }

    private fun updateConnection(conn: DeviceConnection) {

        // clear subscribers of old connection, if any
        connDisposables.clear()
        connDisposables = CompositeDisposable()


        if (conn == NullConnection.INSTANCE) {
            return
        }

        conn.volumes
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { vol ->
                    try {
                        volumeSeries
                                .appendData(DataPoint(vol.time, vol.volume.toDouble()), true, MAX_GRAPH_ELEMENTS)
                    } catch (e: IllegalArgumentException) {
                        Log.i(TAG, "got out of order element. ignoring...", e)
                    }
                }
                .addTo(connDisposables)


        // TODO replace debounce with sample
        conn.volumes
                .debounce(100, TimeUnit.MILLISECONDS)
                .subscribe { volumeUpdated.onNext(it) }
                .addTo(connDisposables)

        conn.frames
                .observeOn(Schedulers.computation())
                .throttleLatest(500, TimeUnit.MILLISECONDS)
                .subscribe {
                    livePictureImageTimestamp.postValue(it.now)
                }
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


        conn.movement
                .startWith(Movement(0.0, false, 0))
                .map {
                    this.downloadMotionImage(conn)
                }
                .filter { it != Babyphone.TimedDrawable.INVALID }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    Log.d(TAG, "adding image to imagepager in thread ${Thread.currentThread().name}")

                    this.imagePager.addImage(it)
                }
                .addTo(connDisposables)

    }

    private fun downloadMotionImage(conn: DeviceConnection, refresh: Boolean = false): Babyphone.TimedDrawable {
        try {
            this.downloadingImage.postValue(true)

            val motionURL = ConnectionService.getMotionUrlForHost(conn.device.hostname, refresh)
            val url = URL(motionURL)
            Log.d(TAG, "loading image from $motionURL in thread ${Thread.currentThread().name}")
            val connection = url.openConnection()
            val pictureTime = connection.getHeaderField("picture-time")
            val inputStream = connection.content as InputStream
            val time = Instant.ofEpochSecond(pictureTime.toLong())
            val baos = ByteArrayOutputStream()
            inputStream.copyTo(baos)

            return Babyphone.TimedDrawable(baos.toByteArray(), time)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading image $e")
            return Babyphone.TimedDrawable.INVALID
        } finally {

            this.downloadingImage.postValue(false)
        }
    }

    fun refreshImage(_donotuse: View? = null) {
        val conn = this.service?.conn ?: return
        GlobalScope.launch {
            val image = withContext(Dispatchers.IO) {
                downloadMotionImage(conn, true)
            }
            if (image != Babyphone.TimedDrawable.INVALID) {
                Log.d(TAG, "got image: $image")
                withContext(Dispatchers.Main) {
                    this@MonitorViewModel.imagePager.addImage(image)
                }
            }
        }
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

