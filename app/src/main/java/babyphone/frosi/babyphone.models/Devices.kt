package babyphone.frosi.babyphone.models

import android.app.Application
import android.content.Context
import android.text.TextUtils
import android.util.Log
import androidx.lifecycle.*
import babyphone.frosi.babyphone.*
import babyphone.frosi.babyphone.R
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.threeten.bp.Instant
import org.threeten.bp.LocalDateTime
import org.threeten.bp.ZoneId
import org.threeten.bp.format.DateTimeFormatter
import java.util.*


class DeviceViewModel(application: Application) : AndroidViewModel(application) {
    private val allDevices: LiveData<List<Device>>

    val disconnectedDevices = MutableLiveData<List<Device>>()

    val activeDevice = MutableLiveData<Device>()

    val connectionState = MutableLiveData<DeviceConnection.ConnectionState>()

    private var service: ConnectionService? = null

    private val repository: DeviceRepository = DeviceRepository(DeviceDatabase.getDatabase(application).deviceDao())

    private val svcDisposables = CompositeDisposable()
    private var connDisposables = CompositeDisposable()

    init {
        allDevices = repository.allDevices
        allDevices.observeForever {
            discover()
            updateDisconnectedDevices()
        }
        activeDevice.observeForever {
            discover()
            updateDisconnectedDevices()
        }
        updateDisconnectedDevices()

        // default is disconnected
        connectionState.value = DeviceConnection.ConnectionState.Disconnected
    }

    fun connectDevice(device: Device): DeviceConnection {
        val conn = service!!.connect(device)
        this.activeDevice.postValue(device)
        return conn
    }

    fun connectService(service: ConnectionService) {
        Log.i(TAG, "connecting service")
        this.service = service

        this.discover()

        service.connections
                .subscribe { conn ->
                    Log.i("deviceModel", "getting new connection $conn")
                    this.updateConnection(conn)
                }
                .addTo(svcDisposables)
        service.discovery.advertisements
                .observeOn(Schedulers.computation())
                .subscribe {
                    val existing = allDevices.value?.find { device ->
                        TextUtils.equals(device.hostname.toLowerCase(Locale.getDefault()).trim(),
                                it.host.toLowerCase(Locale.getDefault()).trim())
                    }

                    if (existing != null) {
                        existing.alive = true
                    } else {
                        val hostname = it.host.toLowerCase(Locale.getDefault()).trim()
                        val newDev = Device(hostname = hostname, name = hostname)
                        newDev.alive = true
                        insert(newDev)
                    }
                    updateDisconnectedDevices()
                }
                .addTo(svcDisposables)
    }

    private fun updateConnection(conn: DeviceConnection) {
        // clear subscribers of old connection, if any
        connDisposables.clear()
        connDisposables = CompositeDisposable()


        if (conn == NullConnection.INSTANCE) {
            this.activeDevice.postValue(null)
            return
        }

        // wire to the new connection
        conn.connectionState
                .subscribe { n ->
                    this.connectionState.postValue(n)
                    this.pingDevices()
                    if (n == DeviceConnection.ConnectionState.Disconnected) {
                        this.activeDevice.postValue(null)
                    }
                }
                .addTo(connDisposables)
        conn.connectionState
                .subscribe { n -> pingDevices() }
                .addTo(connDisposables)

        this.activeDevice.postValue(conn.device)
    }


    fun discover() {
        Log.i(TAG, "starting discovery")
        val service = this.service ?: return
        // start recovery every time we go back to the activity somehow
        viewModelScope.launch(Dispatchers.IO) {
            service.discovery.discover()
        }

        this.pingDevices()
    }

    private fun updateDisconnectedDevices() {
        val dis = allDevices.value?.filter { x -> x != activeDevice.value }
//        val sorted = dis?.sortedWith(Device.Comparator())
        disconnectedDevices.postValue(dis)
    }

    fun insert(device: Device) = viewModelScope.launch(Dispatchers.IO) {
        repository.insert(device)
    }

    fun delete(device: Device) = viewModelScope.launch(Dispatchers.IO) {
        repository.delete(device)
    }

    fun update(device: Device) = viewModelScope.launch(Dispatchers.IO) {
        repository.update(device)
    }

    private fun pingDevices() {
        Log.i(TAG, "pinging devices")
        val service = this.service ?: return

        viewModelScope.launch(Dispatchers.Default) {
            allDevices.value?.forEach { dev ->
                withContext(Dispatchers.IO) {
                    dev.alive = service.discovery.checkHostIsAlive(dev.hostname)
                }
            }
            updateDisconnectedDevices()
        }
    }

    override fun onCleared() {
        super.onCleared()
        this.connDisposables.clear()
        this.svcDisposables.dispose()
    }

    companion object {
        const val TAG = "devices_vm"
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {

        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DeviceViewModel(application) as T
        }
    }

}


class ViewUtils(private val ctx: Context) {
    fun connectionState(cs: DeviceConnection.ConnectionState): String {
        return when (cs) {
            DeviceConnection.ConnectionState.Connecting -> ctx.getString(R.string.text_connecting)
            DeviceConnection.ConnectionState.Connected -> ctx.getString(R.string.text_connected)
            else -> cs.toString()
        }
    }

    fun localDateTime(ms: Long): String {
        val localTime = LocalDateTime.ofInstant(Instant.ofEpochSecond(ms / 1000), ZoneId.systemDefault())
        return DateTimeFormatter.ISO_LOCAL_TIME.format(localTime)
    }
}