package com.web3desk.adr
/**
 * Capture screen,get video and audio,send to rust.
 * Dispatch notifications
 *
 * Inspired by [droidVNC-NG] https://github.com/bk138/droidVNC-NG
 */

import android.annotation.SuppressLint
import android.app.*
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.max
import kotlin.math.min

const val DEFAULT_NOTIFY_TITLE = "Web3 Desk"
const val DEFAULT_NOTIFY_TEXT = "Service is running"
const val DEFAULT_NOTIFY_ID = 1
const val NOTIFY_ID_OFFSET = 100

// video const
const val MAX_SCREEN_SIZE = 1200

class MainService : Service() {
    private val messageQueue = ConcurrentLinkedQueue<ByteString>()

    private val handler = Handler(Looper.getMainLooper())

    private val client = OkHttpClient()
    private var serviceLooper: Looper? = null
    private var serviceHandler: Handler? = null
    private val powerManager: PowerManager by lazy { applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager }
    private val wakeLock: PowerManager.WakeLock by lazy { powerManager.newWakeLock(PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "rustdesk:wakelock")}

    private var startPushingImage: Boolean = false

    companion object {
        private var _apiUrl:String? = null
        private var _deviceId:String? = null
        private var _password:String? = null
        private var _passwordHash:String? = null
        private var _isWsReady = false
        private var _isWsConnected = false
        private var _isReady = false // media permission ready status
        private var _isStart = false // screen capture start status
        private var _isAudioStart = false // audio capture start status
        private var compressQuality:Int = 20
        private var delaySendImageDataMs:Long = 1400
        private var delayPullEventMs:Long = 400

        fun setPassword(v: String) {
            _password = v
        }

        val password: String?
            get() = _password

        fun setPasswordHash(v: String) {
            _passwordHash = v
        }

        val passwordHash: String?
            get() = _passwordHash

        fun setDeviceId(id: String) {
            _deviceId = id
        }

        val deviceId: String?
            get() = _deviceId

        fun setApiUrl(url: String) {
            _apiUrl = url
        }

        val apiUrl: String?
            get() = _apiUrl

        val isReady: Boolean
            get() = _isReady
        val isWsConnected: Boolean
            get() = _isWsConnected
        val isWsReady: Boolean
            get() = _isWsReady
        val isStart: Boolean
            get() = _isStart
        val CompressQuality: Int
            get() = compressQuality
        val DelaySendImageDataMs: Long
            get() = delaySendImageDataMs
        val DelayPullEventMs: Long
            get() = delayPullEventMs
    }

    private val logTag = "LOG_SERVICE"
    private val binder = LocalBinder()

    private var reuseVirtualDisplay = Build.VERSION.SDK_INT > 33
    private var isHalfScale: Boolean? = null;

    private var lastBitmap: Bitmap? = null // To store the last bitmap

    // video
    private var mediaProjection: MediaProjection? = null
    private var surface: Surface? = null
    private var videoEncoder: MediaCodec? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    // notification
    private lateinit var notificationManager: NotificationManager
    private lateinit var notificationChannel: String
    private lateinit var notificationBuilder: NotificationCompat.Builder


    // WebSocket
    private lateinit var webSocketClient: WebSocket
    private val reconnectDelay = 1000L

    private var lastEventTs:Long = 0

    override fun onCreate() {
        super.onCreate()
        val sharedPreferences = getSharedPreferences("mainService", MODE_PRIVATE)
        lastEventTs = sharedPreferences.getLong("lastEventTs", 0)
        compressQuality = sharedPreferences.getInt("compressQuality", 20)
        delayPullEventMs = sharedPreferences.getLong("delayPullEventMs", 400)
        delaySendImageDataMs = sharedPreferences.getLong("delaySendImageDataMs", 1400)

        Log.d(logTag,"MainService onCreate, sdk int:${Build.VERSION.SDK_INT} reuseVirtualDisplay:$reuseVirtualDisplay")
        HandlerThread("Service", Process.THREAD_PRIORITY_BACKGROUND).apply {
            start()
            serviceLooper = looper
            serviceHandler = Handler(looper)
        }
        updateScreenInfo(resources.configuration.orientation)
        initNotification()
        createForegroundNotification()
    }


    private fun useWs(): Boolean? {
        return apiUrl?.startsWith("ws")
    }

    private fun handleMessageFromServer(jsonObject: JSONObject,ts: Long){
        val sharedPreferences = getSharedPreferences("mainService", MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        editor.putLong("lastEventTs", ts)
        editor.apply()
        lastEventTs = ts
        val eventType = jsonObject["eventType"] as String
        val x = jsonObject.optInt("x",0)
        val y = jsonObject.optInt("y",0)
        val value = jsonObject.optInt("value",0)
        val delta = jsonObject.optInt("delta",0)
        val text = jsonObject.optString("text","")
        Log.d(logTag,"handleMessageFromServer eventType:$eventType, text:$text value: $value, x: $x, y: $y")
        when (eventType) {
            "stopPushingImage" -> {
                startPushingImage = false
                sendMessageToActivity(JSONObject().apply{
                    put("action","on_state_changed")
                    put("payload",JSONObject().apply {
                        put("startPushingImage",startPushingImage)
                    })
                }.toString())
            }
            "deviceInfo" -> {
                startPushingImage = true
                sendMessageToActivity(JSONObject().apply{
                    put("action","on_state_changed")
                    put("payload",JSONObject().apply {
                        put("startPushingImage",startPushingImage)
                    })
                }.toString())
            }
            "compressQuality" -> {
                compressQuality = value
                if(compressQuality > 100){
                    compressQuality = 100
                }
                if(compressQuality < 1){
                    compressQuality = 1
                }
                val editor = getSharedPreferences("mainService", MODE_PRIVATE).edit()
                editor.putInt("compressQuality", compressQuality)
                editor.apply()
            }
            "delayPullEventMs" -> {
                delayPullEventMs = value.toLong()
                val editor = getSharedPreferences("mainService", MODE_PRIVATE).edit()
                editor.putLong("delayPullEventMs", delayPullEventMs)
                editor.apply()
            }
            "delaySendImageDataMs" -> {
                delaySendImageDataMs = value.toLong()
                val editor = getSharedPreferences("mainService", MODE_PRIVATE).edit()
                editor.putLong("delaySendImageDataMs", delaySendImageDataMs)
                editor.apply()
            }
            "action" -> {
                InputService.ctx?.onAction(value)
            }
            "swiper" -> {
                InputService.ctx?.swiper(value, x, y, delta);
            }
            "dragStart" -> {
                InputService.ctx?.onMouseInput(LIFT_DOWN, x, y)
            }
            "dragMove" -> {
                InputService.ctx?.onMouseInput(LIFT_MOVE, x, y)
            }
            "dragEnd" -> {
                InputService.ctx?.onMouseInput(LIFT_UP, x, y)
            }
            "click" -> {
                apiPointerInput(0, TOUCH_PAN_START, x, y)
                Handler(Looper.getMainLooper()).postDelayed({
                    apiPointerInput(0, TOUCH_PAN_END, x, y)
                }, 10)
            }
            else -> {
                // Handle unexpected eventType if needed
                Log.e("EventHandler", "Unknown eventType: $eventType")
            }
        }
    }
    private fun initWebSocket() {
        if(password == null || apiUrl == null){
            return
        }
        val request = Request.Builder()
            .url(apiUrl!!)
            .build()

        _isWsReady = false
        _isWsConnected = false
        sendMessageToActivity(JSONObject().apply{
            put("action","on_state_changed")
        }.toString())
        webSocketClient = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(logTag, "WebSocket opened")
                _isWsConnected = true
                webSocketClient.send(JSONObject().apply {
                    put("action","registerDevice")
                    put("payload",JSONObject().apply {
                        put("deviceId",deviceId)
                        put("password",passwordHash)
                        put("platform","ADR")
                    })
                }.toString())
                sendMessageToActivity(JSONObject().apply{
                    put("action","on_state_changed")
                }.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val jsonObject = JSONObject(text)
                val action = jsonObject.getString("action")
                if(action == "logged"){
                    _isWsReady = true
                    sendMessageToActivity(JSONObject().apply{
                        put("action","on_state_changed")
                    }.toString())
                }
                if(action == "clientMsg"){
                    handleMessageFromServer(jsonObject.getJSONObject("payload"),0)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(logTag, "WebSocket failure: ${t.message}")
                Log.d(logTag, "WebSocket Attempting to reconnect in ${reconnectDelay / 1000} seconds...")
                Handler(Looper.getMainLooper()).postDelayed({
                    initWebSocket() // Retry connection
                }, reconnectDelay)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(logTag, "WebSocket closed with code: $code, reason: $reason")
                if(code < WS_CLOSE_STOP_RECONNECT){
                    Log.d(logTag, "WebSocket Attempting to reconnect in ${reconnectDelay / 1000} seconds...")
                    Handler(Looper.getMainLooper()).postDelayed({
                        initWebSocket() // Retry connection
                    }, reconnectDelay)
                }
            }
        })
    }

    fun pushMessage(newMessage: ByteString) {
        messageQueue.clear()
        messageQueue.offer(newMessage)
    }

    private fun sendPullEventRequest(onComplete: () -> Unit) {
        if (apiUrl == null || password == null) {
            onComplete()
            return
        }
        Log.d(logTag,"startPullEventFromServer: ${apiUrl}")
        val url = "${apiUrl}/desk/pull/event"
        val jsonBody = JSONObject().apply {
            put("ts",lastEventTs)
            put("password",passwordHash)
            put("deviceId",deviceId)
        }.toString()

        val body: RequestBody = jsonBody.toRequestBody("application/json".toMediaTypeOrNull())
        val request = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("accept", "application/json")
            .addHeader("Content-Type", "application/json")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(logTag, "Failed to pulling event: ${e.message}")
                onComplete()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { // This ensures the response body is properly closed
                    if (response.isSuccessful) {
                        // You can process the response body here if needed
                        val responseBody = response.body?.string() // Make sure to consume the body
                        Log.d(logTag, "responseBody pulling: $responseBody delayPullEventMs:$delayPullEventMs lastPull: $lastEventTs")
                        try {
                            val jsonObject = JSONObject(responseBody!!)
                            val success = jsonObject.getBoolean("success")
                            if(success){
                                val result = jsonObject.getJSONObject("result")
                                val event = result.optJSONObject("event")
                                if(event !== null){
                                    val message = event.getJSONObject("message")
                                    val ts = event.getLong("ts")
                                    handleMessageFromServer(message,ts)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(logTag, "Failed to parse JSON: ${e.message}")
                        }
                    } else {
                        Log.e(logTag, "Failed to send message: ${response.code}")
                    }
                    onComplete()
                }
            }
        })
    }


    fun sendWsMsg(message:JSONObject){
        if(_isWsConnected){
            webSocketClient.send(
                JSONObject().apply {
                    put("action","deviceMsg")
                    put("payload",message)
                }.toString()
            )
        }

    }
    private fun sendImageData(byteString: ByteString,onComplete: () -> Unit) {
        if(apiUrl == null || !isWsReady || password == null){
            onComplete()
            return
        }

        val byteArray = byteString.toByteArray()
        val encodedString = Base64.encodeToString(byteArray, Base64.DEFAULT).trim()
        val desCrypto = DESCrypto(stringToHex(padKeyTo8Bytes(password!!)))
        val encryptData = desCrypto.encrypt(encodedString)
        val dataUri = "data:jpeg;base64_${passwordHash!!.substring(0, 4)},$encryptData"
        val ts = System.currentTimeMillis()
        Log.d(logTag,"sendImageData $ts")
        webSocketClient.send(
            JSONObject().apply {
                put("action","deviceMsg")
                put("payload",JSONObject().apply {
                    put("screenImage",JSONObject().apply {
                        put("data",dataUri)
                        put("ts",ts)
                    })
                })
            }.toString()
        )
        onComplete()
    }
    private fun sendImageDataRequest(byteString: ByteString,onComplete: () -> Unit) {
        if(apiUrl == null || password == null){
            onComplete()
            return
        }
        val byteArray = byteString.toByteArray()
        val encodedString = Base64.encodeToString(byteArray, Base64.DEFAULT).trim()
        val desCrypto = DESCrypto(stringToHex(padKeyTo8Bytes(password!!)))
        val encryptData = desCrypto.encrypt(encodedString)
        val dataUri = "data:jpeg;base64_${passwordHash!!.substring(0, 4)},$encryptData"

        val url = "${apiUrl!!}/desk/put/screen"
        val ts = System.currentTimeMillis()
        val jsonBody = JSONObject().apply {
            put("screenImageData",dataUri)
            put("ts",ts)
            put("password",passwordHash)
            put("deviceId",deviceId)
        }.toString()

        val body: RequestBody = jsonBody.toRequestBody("application/json".toMediaTypeOrNull())
        val request = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("accept", "application/json")
            .addHeader("Content-Type", "application/json")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(logTag, "Failed to sendImage: ${e.message}")
                onComplete()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        val responseBody = response.body?.string()
                        val ts1 = System.currentTimeMillis()
                        Log.d(logTag, "responseBody sendImage: $responseBody delaySendImageDataMs: $delaySendImageDataMs compressQuality: $compressQuality d: ${ts1 - ts}")
                    } else {
                        Log.e(logTag, "error to sendImage: ${response.code}")
                    }
                    onComplete()
                }
            }
        })
    }

    private fun startPullEventFromServer() {
        handler.post(object : Runnable {
            override fun run() {
                sendPullEventRequest() {
                    if(_isStart){
                        handler.postDelayed(this, delayPullEventMs)
                    }
                }
            }
        })
    }

    private fun startSendingScreenData() {
        if(!isStart){
            return
        }
        handler.post(object : Runnable {
            override fun run() {
                if (messageQueue.isNotEmpty()) {
                    val message = messageQueue.poll()
                    if (message != null) {
                        if(!useWs()!!){
                            sendImageDataRequest(message) {
                                handler.postDelayed(this, delaySendImageDataMs)
                            }
                        }else{
                            sendImageData(message) {
                                handler.postDelayed(this, delaySendImageDataMs)
                            }
                        }

                    } else {
                        handler.postDelayed(this, 1000)
                    }
                } else {
                    handler.postDelayed(this, 1000)
                }
            }
        })
    }
    @SuppressLint("WrongConstant")
    private fun createSurface(): Surface? {
        Log.d(logTag, "ImageReader.newInstance:INFO:$SCREEN_INFO")
        imageReader =
            ImageReader.newInstance(
                SCREEN_INFO.width,
                SCREEN_INFO.height,
                PixelFormat.RGBA_8888,
                4
            ).apply {
                var lastCaptureTime = System.currentTimeMillis()

                setOnImageAvailableListener({ imageReader: ImageReader ->
                    var bitmap: Bitmap? = null
                    try {
                        imageReader.acquireLatestImage().use { image ->
                            if (
                                image == null
                                || !startPushingImage
                                || !isStart
                                || (useWs() == true && !isWsReady)
                                || (useWs() != true && password == null)
                            ) return@setOnImageAvailableListener
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastCaptureTime < 10) {
                                return@setOnImageAvailableListener
                            }
                            lastCaptureTime = currentTime

                            val planes = image.planes
                            val buffer = planes[0].buffer
                            val pixelStride = planes[0].pixelStride
                            val rowStride = planes[0].rowStride
                            val rowPadding: Int = rowStride - pixelStride * SCREEN_INFO.width
                            val rightPadding: Int = rowPadding / pixelStride
                            val originalBitmap = Bitmap.createBitmap(
                                SCREEN_INFO.width + rightPadding,
                                SCREEN_INFO.height,
                                Bitmap.Config.ARGB_8888
                            )
                            originalBitmap?.copyPixelsFromBuffer(buffer)

                            bitmap = Bitmap.createBitmap(
                                originalBitmap,
                                0,
                                0,
                                SCREEN_INFO.width,
                                SCREEN_INFO.height
                            )

                            val scaledBitmap = Bitmap.createScaledBitmap(
                                bitmap!!,
                                SCREEN_INFO.width / 2,
                                SCREEN_INFO.height / 2,
                                true
                            )

                            if (lastBitmap == null || !lastBitmap!!.sameAs(scaledBitmap)) {
                                val byteArray = bitmapToByteArray(scaledBitmap,Bitmap.CompressFormat.JPEG, compressQuality)
                                val byteString = ByteString.of(*byteArray)
                                Log.d(logTag, "pushMessage $currentTime")
                                pushMessage(byteString)
                                lastBitmap = scaledBitmap
                            } else {
                                Log.d(logTag, "Bitmap is the same as the last one, skipping pushMessage.")
                            }

                        }
                    } catch (e: java.lang.Exception) {
                        Log.e(logTag, "acquireLatestImage",e)
                    } finally {
                        bitmap?.recycle()
                    }

                }, serviceHandler)
            }
        Log.d(logTag, "ImageReader.setOnImageAvailableListener done")
        return imageReader?.surface
    }

    override fun onDestroy() {
        checkMediaPermission()
        if(_isWsConnected){
            webSocketClient.close(WS_CLOSE_STOP_RECONNECT, "Service destroyed")
        }
        super.onDestroy()
    }

    private fun updateScreenInfo(orientation: Int) {
        var w: Int
        var h: Int
        var dpi: Int
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val m = windowManager.maximumWindowMetrics
            w = m.bounds.width()
            h = m.bounds.height()
            dpi = resources.configuration.densityDpi
        } else {
            val dm = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(dm)
            w = dm.widthPixels
            h = dm.heightPixels
            dpi = dm.densityDpi
        }

        val max = max(w,h)
        val min = min(w,h)
        if (orientation == ORIENTATION_LANDSCAPE) {
            w = max
            h = min
        } else {
            w = min
            h = max
        }
        Log.d(logTag,"updateScreenInfo:w:$w,h:$h")
        var scale = 1
        if (w != 0 && h != 0) {
            if (isHalfScale == true && (w > MAX_SCREEN_SIZE || h > MAX_SCREEN_SIZE)) {
                scale = 2
                w /= scale
                h /= scale
                dpi /= scale
            }
            if (SCREEN_INFO.width != w) {
                SCREEN_INFO.width = w
                SCREEN_INFO.height = h
                SCREEN_INFO.scale = scale
                SCREEN_INFO.dpi = dpi

                if (isStart) {
                    stopCapture()
                    startCapture()
                }
            }

        }
        sendMessageToActivity(JSONObject().apply{
            put("action","on_state_changed")
        }.toString())
    }

    override fun onBind(intent: Intent): IBinder {
        Log.d(logTag, "service onBind")
        return binder
    }

    inner class LocalBinder : Binder() {
        init {
            Log.d(logTag, "LocalBinder init")
        }

        fun getService(): MainService = this@MainService
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("whichService", "this service: ${Thread.currentThread()}")
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACT_INIT_MEDIA_PROJECTION_AND_SERVICE) {
            createForegroundNotification()

            Log.d(logTag, "service starting: ${startId}:${Thread.currentThread()}")
            val mediaProjectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

            intent.getParcelableExtra<Intent>(EXT_MEDIA_PROJECTION_RES_INTENT)?.let {
                mediaProjection =
                    mediaProjectionManager.getMediaProjection(Activity.RESULT_OK, it)
                checkMediaPermission()
                startCapture()
                _isReady = true
            } ?: let {
                Log.d(logTag, "getParcelableExtra intent null, invoke requestMediaProjection")
                requestMediaProjection()
            }
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScreenInfo(newConfig.orientation)
    }

    private fun requestMediaProjection() {
        val intent = Intent(this, PermissionRequestTransparentActivity::class.java).apply {
            action = ACT_REQUEST_MEDIA_PROJECTION
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    fun startCapture(): Boolean {
        if (isStart) {
            return true
        }
        if (mediaProjection == null) {
            Log.w(logTag, "startCapture fail,mediaProjection is null")
            return false
        }
        //onClientAuthorizedNotification()
        updateScreenInfo(resources.configuration.orientation)
        Log.d(logTag, "Start Capture")
        surface = createSurface()
        startRawVideoRecorder(mediaProjection!!)
        checkMediaPermission()
        _isStart = true
        if(!useWs()!!){
            if(_isWsConnected){
                webSocketClient.close(WS_CLOSE_STOP_RECONNECT, "destroyed")
                _isWsConnected = false
                _isReady = false
            }
            startPullEventFromServer()
        }else{
            initWebSocket()
        }
        startSendingScreenData()
        return true
    }

    @Synchronized
    fun stopCapture() {
        Log.d(logTag, "Stop Capture")
        _isStart = false
        messageQueue.clear()
        // release video
        if (reuseVirtualDisplay) {
            // The virtual display video projection can be paused by calling `setSurface(null)`.
            // https://developer.android.com/reference/android/hardware/display/VirtualDisplay.Callback
            // https://learn.microsoft.com/en-us/dotnet/api/android.hardware.display.virtualdisplay.callback.onpaused?view=net-android-34.0
            virtualDisplay?.setSurface(null)
        } else {
            virtualDisplay?.release()
        }
        // suface needs to be release after `imageReader.close()` to imageReader access released surface
        // https://github.com/rustdesk/rustdesk/issues/4118#issuecomment-1515666629
        imageReader?.close()
        imageReader = null
        videoEncoder?.let {
            it.signalEndOfInputStream()
            it.stop()
            it.release()
        }
        if (!reuseVirtualDisplay) {
            virtualDisplay = null
        }
        videoEncoder = null
        // suface needs to be release after `imageReader.close()` to imageReader access released surface
        // https://github.com/rustdesk/rustdesk/issues/4118#issuecomment-1515666629
        surface?.release()

        // release audio
        _isAudioStart = false
    }

    fun destroy() {
        Log.d(logTag, "destroy service")
        _isReady = false
        _isAudioStart = false
        if(_isWsConnected){
            webSocketClient.close(WS_CLOSE_STOP_RECONNECT, "destroyed")
        }

        stopCapture()

        if (reuseVirtualDisplay) {
            virtualDisplay?.release()
            virtualDisplay = null
        }

        mediaProjection = null
        checkMediaPermission()
        stopForeground(true)
        stopSelf()
    }

    fun checkMediaPermission(): Boolean {
        Handler(Looper.getMainLooper()).post {
            sendMessageToActivity(JSONObject().apply{
                put("action","on_state_changed")
            }.toString())
        }
        return isReady
    }

    private fun startRawVideoRecorder(mp: MediaProjection) {
        Log.d(logTag, "startRawVideoRecorder,screen info:$SCREEN_INFO")
        if (surface == null) {
            Log.d(logTag, "startRawVideoRecorder failed,surface is null")
            return
        }
        createOrSetVirtualDisplay(mp, surface!!)
    }

    private fun getVirtualDisplayFlags(): Int {
        return DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
    }

    // https://github.com/bk138/droidVNC-NG/blob/b79af62db5a1c08ed94e6a91464859ffed6f4e97/app/src/main/java/net/christianbeier/droidvnc_ng/MediaProjectionService.java#L250
    // Reuse virtualDisplay if it exists, to avoid media projection confirmation dialog every connection.
    private fun createOrSetVirtualDisplay(mp: MediaProjection, s: Surface) {
        try {
            virtualDisplay?.let {
                it.resize(SCREEN_INFO.width, SCREEN_INFO.height, SCREEN_INFO.dpi)
                it.setSurface(s)
            } ?: let {
                virtualDisplay = mp.createVirtualDisplay(
                    "Web3-Desk-VD",
                    SCREEN_INFO.width, SCREEN_INFO.height, SCREEN_INFO.dpi, getVirtualDisplayFlags(),
                    s, null, null
                )
            }
        } catch (e: SecurityException) {
            Log.w(logTag, "createOrSetVirtualDisplay: got SecurityException, re-requesting confirmation");
            requestMediaProjection()
        }
    }

    private fun initNotification() {
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationChannel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "Web3Desk"
            val channelName = "Web3Desk Service"
            val channel = NotificationChannel(
                channelId,
                channelName, NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Web3Desk Service Channel"
            }
            channel.lightColor = Color.BLUE
            channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            notificationManager.createNotificationChannel(channel)
            channelId
        } else {
            ""
        }
        notificationBuilder = NotificationCompat.Builder(this, notificationChannel)
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    private fun createForegroundNotification() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            putExtra("type", type)
        }
        val pendingIntent =
            PendingIntent.getActivity(this, 0, intent, FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE)
        val notification = notificationBuilder
            .setOngoing(true)
            .setSmallIcon(R.mipmap.ic_stat_logo)
            .setDefaults(Notification.DEFAULT_ALL)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentTitle(DEFAULT_NOTIFY_TITLE)
            .setContentText(translate(DEFAULT_NOTIFY_TEXT))
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setColor(ContextCompat.getColor(this, R.color.primary))
            .setWhen(System.currentTimeMillis())
            .build()
        startForeground(DEFAULT_NOTIFY_ID, notification)
    }

    private fun loginRequestNotification(
        clientID: Int,
        type: String,
        username: String,
        peerId: String
    ) {
        val notification = notificationBuilder
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentTitle(translate("Do you accept?"))
            .setContentText("$type:$username-$peerId")
            .build()
        notificationManager.notify(getClientNotifyID(clientID), notification)
    }

    private fun onClientAuthorizedNotification(
        clientID: Int,
        type: String,
        username: String,
        peerId: String
    ) {
        cancelNotification(clientID)
        val notification = notificationBuilder
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentTitle("$type ${translate("Established")}")
            .setContentText("$username - $peerId")
            .build()
        notificationManager.notify(getClientNotifyID(clientID), notification)
    }

    private fun getClientNotifyID(clientID: Int): Int {
        return clientID + NOTIFY_ID_OFFSET
    }

    fun cancelNotification(clientID: Int) {
        notificationManager.cancel(getClientNotifyID(clientID))
    }

    @Keep
    @RequiresApi(Build.VERSION_CODES.N)
    fun apiPointerInput(kind: Int, mask: Int, x: Int, y: Int) {
        Log.d(logTag,"apiPointerInput kind:$kind,x:$x,y:$y,mask:$mask")
        // turn on screen with LIFT_DOWN when screen off
        if (!powerManager.isInteractive && (kind == 0 || mask == LIFT_DOWN)) {
            if (wakeLock.isHeld) {
                Log.d(logTag, "Turn on Screen, WakeLock release")
                wakeLock.release()
            }
            Log.d(logTag,"Turn on Screen")
            wakeLock.acquire(5000)
        } else {
            when (kind) {
                0 -> { // touch
                    InputService.ctx?.onTouchInput(mask, x, y)
                }
                1 -> { // mouse
                    InputService.ctx?.onMouseInput(mask, x, y)
                }
                else -> {
                }
            }
        }
    }

    @Keep
    fun apiGetByName(name: String): String {
        return when (name) {
            "screen_size" -> {
                JSONObject().apply {
                    put("eventType","screen_size")
                    put("width",SCREEN_INFO.width)
                    put("height",SCREEN_INFO.height)
                    put("scale",SCREEN_INFO.scale)
                }.toString()
            }
            "is_start" -> {
                JSONObject().apply {
                    put("eventType","is_start")
                    put("isStart",isStart.toString())
                }.toString()
            }
            else -> ""
        }
    }

    @Keep
    fun apiSetByName(name: String, arg1: String, arg2: String) {
        when (name) {
            "stop_capture" -> {
                Log.d(logTag, "from rust:stop_capture")
                stopCapture()
            }
            "half_scale" -> {
                val halfScale = arg1.toBoolean()
                if (isHalfScale != halfScale) {
                    isHalfScale = halfScale
                    updateScreenInfo(resources.configuration.orientation)
                }
            }
            else -> {
            }
        }
    }
    private fun sendMessageToActivity(message: String) {
        val intent = Intent("WebViewMessage")
        intent.putExtra("message", message)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun sendByteStringToActivity(byteString: ByteString) {
        val byteArray = byteString.toByteArray()
        val encodedString = Base64.encodeToString(byteArray, Base64.DEFAULT).trim()
        val dataUri = "data:jpeg;base64,$encodedString"
        val intent = Intent("WebViewMessage").apply {
            putExtra("byte_message", dataUri)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }
}
