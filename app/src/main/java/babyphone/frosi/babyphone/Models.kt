package babyphone.frosi.babyphone

import android.app.Application
import android.text.TextUtils
import android.util.Log
import androidx.lifecycle.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.io.IOException
import java.io.InputStream
import java.net.URL
import android.icu.lang.UCharacter.GraphemeClusterBreak.T
import java.net.HttpURLConnection


class DeviceViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: DeviceRepository

    val allDevices: LiveData<List<Device>>

    val disconnectedDevices = MutableLiveData<List<Device>>()

    val activeDevice = MutableLiveData<Device>()

    val connectionState = MutableLiveData<ConnectionService.ConnectionState>()

    var service: ConnectionService? = null


    private val discovery = Discovery()


    init {
        repository = DeviceRepository(DeviceDatabase.getDatabase(application).deviceDao())
        allDevices = repository.allDevices
        allDevices.observeForever {
            discover()
            updateDisconnectedDevices() }
        activeDevice.observeForever {
            discover()
            updateDisconnectedDevices()
        }
        EventBus.getDefault().register(this)
        updateDisconnectedDevices()

        // default is disconnected
        connectionState.value = ConnectionService.ConnectionState.Disconnected

        discovery.start()
    }


    fun serviceConnected(service: ConnectionService) {
        this.service = service

        this.setActiveDevice(service.currentDevice)
        this.setConnectionState(service.connectionState)
    }

    fun discover(){

        // start recovery every time we go back to the activity somehow
        viewModelScope.launch(Dispatchers.IO){
            discovery.discover()
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

    fun pingDevices() {
        allDevices.value?.forEach { dev ->
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    Log.i("model", "pinging ${dev.hostname}")
                    val url = URL("http://${dev.hostname}:8081/ruok")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 500
                    conn.connect()
                    val code = conn.getResponseCode()
                    dev.alive = code == 200 && conn.getContent().toString() == "imok"
                } catch (e: IOException) {
                    dev.alive = false
                } finally {
                    updateDisconnectedDevices()
                }
            }
        }
    }

    fun setActiveDevice(device: Device?) {
        Log.i("viewmodel", "setting device $device")
        activeDevice.postValue(device)
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    fun onConnectionStateUpdated(cu: ConnectionStateUpdated) {
        if (cu.device != activeDevice.value) {
            activeDevice.postValue(cu.device)
        }
        setConnectionState(cu.state)
    }

    fun setConnectionState(state: ConnectionService.ConnectionState) {
        connectionState.postValue(state)
        pingDevices()
    }


    @Subscribe(threadMode = ThreadMode.MAIN)
    fun handleBabyphoneAdvertise(adv: Advertise) {
        val existing = allDevices.value?.find { device ->
            TextUtils.equals(device.hostname.toLowerCase().trim(),
                    adv.host.toLowerCase().trim())
        }

        if (existing != null) {
            existing.alive = true
            updateDisconnectedDevices()
        } else {
            val newDev = Device(hostname = adv.host.toLowerCase().trim(), hostIp = "some-IP")
            newDev.alive = true
            insert(newDev)
        }
    }


    override fun onCleared() {
        super.onCleared()
        discovery.stop()
        EventBus.getDefault().unregister(this)
    }
}

class DeviceViewModelFactory(val application: Application) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return DeviceViewModel(application) as T
    }
}