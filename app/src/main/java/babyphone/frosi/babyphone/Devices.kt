package babyphone.frosi.babyphone

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.text.TextUtils
import android.util.Log
import android.view.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.ActivityOptionsCompat
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import androidx.recyclerview.widget.RecyclerView
import babyphone.frosi.babyphone.databinding.ActivityDevicesBinding
import babyphone.frosi.babyphone.databinding.DevicesItemBinding
import babyphone.frosi.babyphone.models.DeviceViewModel
import babyphone.frosi.babyphone.models.ViewUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.android.synthetic.main.activity_devices.*
import kotlinx.android.synthetic.main.activity_edit_connection.*
import kotlinx.android.synthetic.main.devices_current.*


class DeviceListAdapter internal constructor(
        val context: Devices,
        private val model: DeviceViewModel
) : RecyclerView.Adapter<DeviceListAdapter.DeviceViewHolder>() {
    private val inflater: LayoutInflater = LayoutInflater.from(context)
    private var devices = emptyList<Device>() // Cached copy of devices

    inner class DeviceViewHolder(val binding: DevicesItemBinding) : RecyclerView.ViewHolder(binding.root), View.OnClickListener {

        init {
            itemView.findViewById<View>(R.id.btn_connect).setOnClickListener(this)
            itemView.findViewById<View>(R.id.btn_delete).setOnClickListener(this)
            itemView.findViewById<View>(R.id.btn_edit).setOnClickListener(this)

        }

        override fun onClick(v: View?) {
            val device = devices.get(this.getAdapterPosition())
            when (v?.id) {
                R.id.btn_connect -> {
                    context.connectToDevice(device)
                }
                R.id.btn_delete -> {
                    model.delete(device)
                }
                R.id.btn_edit -> {
                    val intent = Intent(context, EditConnection::class.java)
                    intent.putExtra(EditConnection.EXTRA_EDIT_ID, device.id)
                    intent.putExtra(EditConnection.EXTRA_NAME, device.name)
                    intent.putExtra(EditConnection.EXTRA_HOSTNAME, device.hostname)
                    context.startActivityForResult(intent,
                            Devices.newConnectionActivityRequestCode)
                }

            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val binding = DataBindingUtil.inflate<DevicesItemBinding>(inflater, R.layout.devices_item, parent, false)
        return DeviceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val current = devices[position]
        holder.binding.device = current
    }

    internal fun setDevices(devices: List<Device>) {
        this.devices = devices
        notifyDataSetChanged()
    }

    override fun getItemCount() = devices.size
}

class Devices : AppCompatActivity(), ServiceConnection, View.OnClickListener {

    companion object {
        const val newConnectionActivityRequestCode = 1
        const val TAG = "activity_devices"
    }

    private val handler = Handler()

    private lateinit var devicesViewModel: DeviceViewModel

    private var service: ConnectionService? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.i(TAG, "oncreate")

        devicesViewModel = ViewModelProviders
                .of(this, DeviceViewModel.Factory(this.application))
                .get(DeviceViewModel::class.java)

        val binding = DataBindingUtil.setContentView<ActivityDevicesBinding>(this, R.layout.activity_devices)
        binding.deviceViewModel = devicesViewModel
        binding.lifecycleOwner = this
        binding.utils = ViewUtils(this)
        setSupportActionBar(toolbar)

        this.btn_disconnect.setOnClickListener(this)
        this.btn_monitor.setOnClickListener(this)
        this.btn_menu.setOnClickListener(this)
        this.fab.setOnClickListener(this)

        val adapter = DeviceListAdapter(this, devicesViewModel)
        val recyclerView = findViewById<RecyclerView>(R.id.devices_list)
        recyclerView.adapter = adapter

        devicesViewModel.disconnectedDevices.observe(this, Observer { devices ->
            devices?.let { adapter.setDevices(it) }
        })

        ConnectionService.startService(this)
        this.bindService(Intent(this, ConnectionService::class.java), this, 0)

    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        if (menu is MenuBuilder) {
            menu.setOptionalIconsVisible(true)
        }
        getMenuInflater().inflate(R.menu.menu_devices, menu)
        return true;
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.action_exit -> {
                this.exit()
            }
            R.id.action_discover -> {
                this.devicesViewModel.discover()
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "onresume")
        devicesViewModel.discover()
    }

    override fun onPause() {
        super.onPause()
    }

    private fun startMonitorActivity() {
        val intent = Intent(this, Babyphone::class.java)
        this.startActivity(intent,
                ActivityOptionsCompat.makeSceneTransitionAnimation(this).toBundle())
    }

    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.btn_disconnect -> {
                service!!.disconnect()
            }
            R.id.btn_monitor -> {
                this.startMonitorActivity()
            }
            R.id.btn_menu -> {
                val popup = PopupMenu(this, v)
                val inflater = popup.menuInflater
                inflater.inflate(R.menu.popup_active_conn, popup.menu)
                popup.show()
                popup.setOnMenuItemClickListener { v ->
                    when (v?.itemId) {
                        R.id.action_shutdown -> {
                            service?.conn?.shutdown()
                            true
                        }
                        R.id.action_restart -> {
                            Toast.makeText(this, "Not implemented", Toast.LENGTH_SHORT)
                            service?.conn?.restart()
                            true
                        }
                    }
                    false
                }
            }
            R.id.fab -> {
                this.startActivityForResult(Intent(this, EditConnection::class.java),
                        newConnectionActivityRequestCode)
            }
        }
    }

    fun connectToDevice(device: Device) {

        val connect = {
            val conn = devicesViewModel.connectDevice(device)
            handler.postDelayed(Runnable { startMonitorActivity() }, 200)
        }

        if (devicesViewModel.activeDevice.value != null) {

            MaterialAlertDialogBuilder(this)
                    .setTitle("Active Connection")
                    .setMessage("Another connection is already active. This will be terminated. Continue?")
                    .setPositiveButton("Yes") { _, _ ->
                        connect()
                    }
                    .setNegativeButton("No", null)
                    .show();
        } else {
            connect()
        }

    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        Log.i(TAG, "service connected")
        val svc = (service as ConnectionService.ConnectionServiceBinder).service

        devicesViewModel.connectService(svc)
        this.service = svc
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        this.service = null
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == newConnectionActivityRequestCode && resultCode == Activity.RESULT_OK) {
            data?.let {
                val device = Device(hostname = it.getStringExtra(EditConnection.EXTRA_HOSTNAME),
                        name = it.getStringExtra(EditConnection.EXTRA_NAME))
                val existingId = it.getIntExtra(EditConnection.EXTRA_EDIT_ID, -1)
                if (existingId != -1) {
                    device.id = existingId
                    Log.i(TAG, "updating device")
                    devicesViewModel.update(device)
                } else {
                    Log.i(TAG, "updating device")
                    devicesViewModel.insert(device)
                }
            }
        } else {
            Toast.makeText(
                    applicationContext,
                    "not saved",
                    Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        this.unbindService(this)
        super.onDestroy()
    }

    private fun exit() {
        this.service?.disconnect()
        this.stopService(Intent(this, ConnectionService::class.java))
        this.finishAffinity()
    }
}


class EditConnection : AppCompatActivity(), View.OnClickListener {


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "onCreate")

        setContentView(R.layout.activity_edit_connection)


        if (intent.getIntExtra(EXTRA_EDIT_ID, -1) != -1) {
            this.input_name.setText(intent.getStringExtra(EXTRA_NAME))
            this.input_hostname.setText(intent.getStringExtra(EXTRA_HOSTNAME))
            this.txt_header.text = resources.getString(R.string.edit_connection_header_edit)
            this.btn_create.text = resources.getString(R.string.btn_label_save)
        }
        this.btn_create.setOnClickListener(this)
    }


    override fun onClick(v: View?) {

        when (v?.id) {
            R.id.btn_create -> {
                val replyIntent = Intent()
                var error = false
                if (TextUtils.isEmpty(this.input_hostname.text)) {
                    this.input_hostname.error = resources.getString(R.string.edit_connection_error_hostname)
                    error = true
                } else {
                    this.input_hostname.error = null
                }

                if (TextUtils.isEmpty(this.input_name.text)) {
                    this.input_name.error = resources.getString(R.string.edit_connection_error_name)
                    error = true
                } else {
                    this.input_name.error = null
                }
                if (!error) {
                    replyIntent.putExtra(EXTRA_NAME, this.input_name.text.toString())
                    replyIntent.putExtra(EXTRA_HOSTNAME, this.input_hostname.text.toString())
                    replyIntent.putExtra(EXTRA_EDIT_ID, intent.getIntExtra(EXTRA_EDIT_ID, -1))
                    setResult(Activity.RESULT_OK, replyIntent)
                    finish()
                }
            }
        }
    }

    companion object {
        const val EXTRA_NAME = "babyphone.frosi.babyphone.connection.name"
        const val EXTRA_HOSTNAME = "babyphone.frosi.babyphone.connection.label_status"
        const val EXTRA_EDIT_ID = "babyphone.frosi..babypohne.connection.is_edit"
        const val TAG = "activity_edit_device"
    }

}
