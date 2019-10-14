package babyphone.frosi.babyphone

import android.content.Context
import androidx.annotation.WorkerThread
import androidx.lifecycle.LiveData
import androidx.room.*
import org.threeten.bp.Instant


@Database(entities = arrayOf(Device::class), version = 1)
abstract class DeviceDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao

    companion object {
        @Volatile
        private var INSTANCE: DeviceDatabase? = null

        fun getDatabase(context: Context): DeviceDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                        context.applicationContext,
                        DeviceDatabase::class.java,
                        "device_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices")
    fun getAll(): LiveData<List<Device>>

    @Insert
    suspend fun insert(device: Device)

    @Delete
    suspend fun delete(device: Device)


    @Update
    suspend fun update(device: Device)

}


@Entity(tableName = "devices")
data class Device(
        @PrimaryKey(autoGenerate = true)
        var id: Int = 0,

        val name: String,

        val hostname: String
) {
    @Ignore
    var alive = false

    fun isAlive(): String {
        // TODO: put this into the viewutils and use Resource strings
        return when (alive) {
            true -> "ALIVE"
            false -> "NOT ALIVE"
        }
    }

    class Comparator : kotlin.Comparator<Device> {
        override fun compare(o1: Device?, o2: Device?): Int {
            return -1
            TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
        }

    }
}


class DeviceRepository(private val deviceDao: DeviceDao) {

    val allDevices: LiveData<List<Device>> = deviceDao.getAll()

    @WorkerThread
    suspend fun insert(device: Device) {
        deviceDao.insert(device)
    }

    @WorkerThread
    suspend fun delete(device: Device) {
        deviceDao.delete(device)
    }

    @WorkerThread
    suspend fun update(device:Device){
        deviceDao.update(device)
    }
}