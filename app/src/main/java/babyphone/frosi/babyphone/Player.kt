package babyphone.frosi.babyphone

import android.util.Log
import android.view.SurfaceHolder
import org.freedesktop.gstreamer.GStreamer

class Player(private val ctx: Babyphone) : SurfaceHolder.Callback {
    private external fun nativeInit()      // Initialize native code, build pipeline, etc
    private external fun nativePlay()      // Set pipeline to PLAYING
    private external fun nativePause()     // Set pipeline to PAUSED
    private external fun nativeFinalize()  // Destroy pipeline and shutdown native code
    private external fun nativeSetUri(uri: String)  // Set the URI of the media to play
    private external fun nativeSurfaceInit(surface: Any)  // A new surface is available
    private external fun nativeSurfaceFinalize()  // Surface about to be destroyed
    private var native_custom_data: Long = 0      // Native code will use this to keep private data

    init {
        // Initialize GStreamer and warn if it fails
        GStreamer.init(ctx)
    }

    fun initialize() {
        nativeInit()
    }

    fun destroy() {
        nativeFinalize()
    }

    fun play(uri: String) {
        this.nativeSetUri(uri)
        this.nativePlay()
    }

    fun pause() {
        this.nativePause()
    }


    private fun onMediaSizeChanged(width: Int, height: Int) {
        this.ctx.onMediaSizeChanged(width, height)
    }


    // Called from native code. This sets the content of the TextView from the UI thread.
    private fun setMessage(message: String) {
        Log.i("Gstreamer message", message)
//        this.ctx.setMessage(message)
    }

    private fun onGStreamerInitialized() {
        this.ctx.onGStreamerInitialized()
    }


    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int,
                                height: Int) {
        Log.d("GStreamer", "Surface changed to format " + format + " width "
                + width + " height " + height)
        nativeSurfaceInit(holder.surface)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d("GStreamer", "Surface created: " + holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d("GStreamer", "Surface destroyed")
        nativeSurfaceFinalize()
    }


    companion object {
        @JvmStatic
        private external fun nativeClassInit(): Boolean  // Initialize native class: cache Method IDs for callbacks

        init {
            System.loadLibrary("gstreamer_android");
            System.loadLibrary("player");
            nativeClassInit()
        }
    }


}