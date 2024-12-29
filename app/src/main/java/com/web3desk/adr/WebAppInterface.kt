package com.web3desk.adr

import android.content.Context
import android.content.Intent
import android.util.Log
import android.webkit.JavascriptInterface
import android.widget.Toast
import com.hjq.permissions.XXPermissions
import org.json.JSONObject

class WebAppInterface(private val context: MainActivity) {
    @JavascriptInterface
    fun webview_is_ready(apiUrl:String, deviceId:String): Boolean {
        context.webviewIsReady = true
        MainService.setApiUrl(apiUrl)
        MainService.setDeviceId(deviceId)
        return true
    }
    @JavascriptInterface
    fun check_service(): String {
        val payload = JSONObject().apply {
            put("mediaIsStart",MainService.isStart)
            put("compressQuality",MainService.CompressQuality)
            put("delaySendImageDataMs",MainService.DelaySendImageDataMs)
            put("delayPullEventMs",MainService.DelayPullEventMs)
            put("isWsConnected",MainService.isWsConnected)
            put("isWsReady",MainService.isWsReady)
            put("inputIsOpen",InputService.isOpen)
            put("screen",JSONObject().apply {
                put("width",SCREEN_INFO.width)
                put("height",SCREEN_INFO.height)
                put("scale",SCREEN_INFO.scale)
                put("dpi",SCREEN_INFO.dpi)
            })
        }
        if(MainService.isWsReady){
            context.mainService?.sendWsMsg(JSONObject().apply {
                put("deviceInfo",payload)
            })
        }
        return payload.toString()
    }

    @JavascriptInterface
    fun init_service(apiUrl:String, password: String, passwordHash: String): Boolean {
        MainService.setPassword(password)
        MainService.setApiUrl(apiUrl)
        MainService.setPasswordHash(passwordHash)
        Intent(context, MainService::class.java).also {
            context.bindService(it, context.serviceConnection, Context.BIND_AUTO_CREATE)
        }
        if (MainService.isReady) {
            return false;
        }
        context.requestMediaProjection()
        return true
    }

    @JavascriptInterface
    fun start_scanner(): Boolean {
        context.startScanner()
        return true;
    }

    @JavascriptInterface
    fun start_capture(): Boolean {
        context.mainService?.let {
            return it.startCapture()
        } ?: let {
            return false
        }
    }

    @JavascriptInterface
    fun stop_service(): Boolean {
        Log.d("JavascriptInterface", "Stop service")
        context.mainService?.let {
            it.destroy()
            InputService.ctx?.disableSelf()
            InputService.ctx = null
            context.onStateChanged()
            return true;
        } ?: let {
            return false;
        }
    }

    @JavascriptInterface
    fun stop_input(): Boolean {
        InputService.ctx?.disableSelf()
        InputService.ctx = null
        context.onStateChanged()
        return true
    }

    @JavascriptInterface
    fun check_permission(permission:String): Boolean {
        val isGranted = XXPermissions.isGranted(context, permission as String)
        Toast.makeText(context, if (isGranted) "true" else "false", Toast.LENGTH_SHORT).show()
        return isGranted
    }

    @JavascriptInterface
    fun cancel_notification(id:Int): Boolean {
        context.mainService?.cancelNotification(id)
        return true
    }

    @JavascriptInterface
    fun request_permission(permission:String): Boolean {
        requestPermission(context, permission)
        return true
    }
    @JavascriptInterface
    fun start_action(action:String): Boolean {
        startAction(context, action)
        return true
    }
    @JavascriptInterface
    fun show_toast(msg:String): Boolean {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        return true
    }

}