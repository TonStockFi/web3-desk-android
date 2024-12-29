package com.web3desk.adr

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.zxing.integration.android.IntentIntegrator
import com.google.zxing.integration.android.IntentResult
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private val logTag = "mMainActivity"
    var mainService: MainService? = null
    var webviewIsReady: Boolean = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        val intent = Intent(this, MainService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE) // Binds the service
        webView = findViewById<WebView>(R.id.webview)
        webView.settings.javaScriptEnabled = true
        webView.settings.allowFileAccess = true // Allow file access
        webView.settings.allowContentAccess = true // Allow access to content URLs
        webView.settings.allowUniversalAccessFromFileURLs = true // Allow CORS for file URLs
        webView.settings.allowFileAccessFromFileURLs = true // Allow access to other file URLs from file
        webView.settings.domStorageEnabled = true

        webView.webViewClient = WebViewClient()

        webView.webChromeClient = WebChromeClient()
//        webView.loadUrl(HOME_URL_DEV)
        webView.loadUrl(HOME_URL)
        webView.addJavascriptInterface(WebAppInterface(this), "__AndroidAPI")
        LocalBroadcastManager.getInstance(this).registerReceiver(messageReceiver, IntentFilter("WebViewMessage"))
    }
    fun startScanner() {
        val integrator = IntentIntegrator(this)
        integrator.setDesiredBarcodeFormats(IntentIntegrator.ALL_CODE_TYPES) // Set the types of codes you want to scan
        integrator.setPrompt("扫码二维码") // Customize prompt
        integrator.setCameraId(0) // Use a specific camera (0 for back camera)
        integrator.setBeepEnabled(true) // Enable beep sound after scan
        integrator.setBarcodeImageEnabled(true) // Save scanned barcode image

        integrator.initiateScan() // Start scanning
    }

    override fun onResume() {
        super.onResume()
        val inputPer = InputService.isOpen
        Log.d(logTag, "onResume")
        Log.d(logTag, inputPer.toString())
        onStateChanged()
    }

    override fun onDestroy() {
        Log.e(logTag, "onDestroy")
        mainService?.let {
            unbindService(serviceConnection)
        }
        LocalBroadcastManager.getInstance(this).unregisterReceiver(messageReceiver)
        super.onDestroy()
    }

    val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(logTag, "onServiceConnected")
            val binder = service as MainService.LocalBinder
            mainService = binder.getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(logTag, "onServiceDisconnected")
            mainService = null
        }
    }

    override fun onStop() {
        super.onStop()
        Log.d(logTag, "onStop")
    }

    override fun onStart() {
        super.onStart()
        Log.d(logTag, "onStart")
    }

    fun sendMessageToWebView(message: String) {
        Log.d(logTag, "sendMessageToWebView:${message},webviewIsReady:${webviewIsReady.toString()}")
        if(webviewIsReady){
            webView.post {
                webView.evaluateJavascript("javascript:AppCallback(${JSONObject.quote(message)})", null)
            }
        }
    }

    private val messageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val encodedMessage = intent.getStringExtra("byte_message")
            val message = intent.getStringExtra("message") ?: ""

            val jsonMessage = if (encodedMessage.isNullOrEmpty()) {
                // Use plain message if encodedMessage is null or empty
                message
            } else {
                // Construct JSON object if encodedMessage is not null or empty
                JSONObject().apply {
                    put("action", "on_image")
                    put("payload", encodedMessage)
                }.toString()
            }

            sendMessageToWebView(jsonMessage)  // Send the message to the WebView
        }
    }

    fun requestMediaProjection() {
        val intent = Intent(this, PermissionRequestTransparentActivity::class.java).apply {
            action = ACT_REQUEST_MEDIA_PROJECTION
        }
        startActivityForResult(intent, REQ_INVOKE_PERMISSION_ACTIVITY_MEDIA_PROJECTION)
    }
    fun onStateChanged(){
        val message = JSONObject().apply {
            put("action","on_state_changed")
        }.toString()
        sendMessageToWebView(message)
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_INVOKE_PERMISSION_ACTIVITY_MEDIA_PROJECTION && resultCode == RES_FAILED) {
            sendMessageToWebView(JSONObject().apply {
                put("action","on_media_projection_canceled")
            }.toString())
        }
        if(requestCode != REQ_INVOKE_PERMISSION_ACTIVITY_MEDIA_PROJECTION ){
            val result: IntentResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
            if (result.contents != null) {
                sendMessageToWebView(JSONObject().apply {
                    put("action","on_scan_result")
                    put("payload",result.contents)
                }.toString())
            }
        }


    }
}
