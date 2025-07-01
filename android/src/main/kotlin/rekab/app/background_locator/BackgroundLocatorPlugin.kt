package rekab.app.background_locator

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.util.Log
import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.*
import rekab.app.background_locator.pluggables.DisposePluggable
import rekab.app.background_locator.pluggables.InitPluggable

class BackgroundLocatorPlugin : MethodCallHandler, FlutterPlugin, PluginRegistry.NewIntentListener, ActivityAware {

    private lateinit var context: Context
    private var activity: Activity? = null
    private lateinit var channel: MethodChannel

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, Keys.CHANNEL_ID)
        channel.setMethodCallHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            Keys.METHOD_PLUGIN_INITIALIZE_SERVICE -> {
                val args = call.arguments<Map<Any, Any>>()
                PreferencesManager.saveCallbackDispatcher(context, args)
                initializeService(args)
                result.success(true)
            }
            Keys.METHOD_PLUGIN_REGISTER_LOCATION_UPDATE -> {
                val args = call.arguments<Map<Any, Any>>()
                PreferencesManager.saveSettings(context, args)
                registerLocator(args, result)
            }
            Keys.METHOD_PLUGIN_UN_REGISTER_LOCATION_UPDATE -> unRegisterPlugin(result)
            Keys.METHOD_PLUGIN_IS_REGISTER_LOCATION_UPDATE,
            Keys.METHOD_PLUGIN_IS_SERVICE_RUNNING -> result.success(IsolateHolderService.isServiceRunning)
            Keys.METHOD_PLUGIN_UPDATE_NOTIFICATION -> updateNotificationText(call.arguments())
            else -> result.notImplemented()
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerLocator(args: Map<Any, Any>, result: MethodChannel.Result?) {
        if (IsolateHolderService.isServiceRunning) {
            result?.success(true)
            return
        }

        val callbackHandle = args[Keys.ARG_CALLBACK] as Long
        PreferencesManager.setCallbackHandle(context, Keys.CALLBACK_HANDLE_KEY, callbackHandle)

        args[Keys.ARG_NOTIFICATION_CALLBACK]?.let {
            PreferencesManager.setCallbackHandle(context, Keys.NOTIFICATION_CALLBACK_HANDLE_KEY, it as Long)
        }

        (args[Keys.ARG_INIT_CALLBACK] as? Long)?.let { initCallbackHandle ->
            InitPluggable().apply {
                setCallback(context, initCallbackHandle)
                (args[Keys.ARG_INIT_DATA_CALLBACK] as? Map<*, *>)?.let { initData ->
                    setInitData(context, initData)
                }
            }
        }

        (args[Keys.ARG_DISPOSE_CALLBACK] as? Long)?.let {
            DisposePluggable().setCallback(context, it)
        }

        val settings = args[Keys.ARG_SETTINGS] as Map<*, *>

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_DENIED) {
            result?.error("PermissionError", "ACCESS_FINE_LOCATION permission required.", null)
            return
        }

        startIsolateService(settings)
        Handler(context.mainLooper).postDelayed({ result?.success(true) }, 1000)
    }

    private fun startIsolateService(settings: Map<*, *>) {
        Intent(context, IsolateHolderService::class.java).apply {
            action = IsolateHolderService.ACTION_START
            putExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_CHANNEL_NAME, settings[Keys.SETTINGS_ANDROID_NOTIFICATION_CHANNEL_NAME] as String)
            putExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_TITLE, settings[Keys.SETTINGS_ANDROID_NOTIFICATION_TITLE] as String)
            putExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_MSG, settings[Keys.SETTINGS_ANDROID_NOTIFICATION_MSG] as String)
            putExtra(Keys.SETTINGS_INTERVAL, settings[Keys.SETTINGS_INTERVAL] as Int)
            putExtra(Keys.SETTINGS_ACCURACY, settings[Keys.SETTINGS_ACCURACY] as Int)
            putExtra(Keys.SETTINGS_DISTANCE_FILTER, settings[Keys.SETTINGS_DISTANCE_FILTER] as Double)

            settings[Keys.SETTINGS_ANDROID_WAKE_LOCK_TIME]?.let {
                putExtra(Keys.SETTINGS_ANDROID_WAKE_LOCK_TIME, it as Int)
            }

            if (PreferencesManager.hasInitCallback(context)) {
                putExtra(Keys.SETTINGS_INIT_PLUGGABLE, true)
            }
            if (PreferencesManager.hasDisposeCallback(context)) {
                putExtra(Keys.SETTINGS_DISPOSABLE_PLUGGABLE, true)
            }

            ContextCompat.startForegroundService(context, this)
        }
    }

    private fun unRegisterPlugin(result: MethodChannel.Result?) {
        if (!IsolateHolderService.isServiceRunning) {
            result?.success(true)
            return
        }
        stopIsolateService()
        Handler(context.mainLooper).postDelayed({ result?.success(true) }, 1000)
    }

    private fun stopIsolateService() {
        Intent(context, IsolateHolderService::class.java).apply {
            action = IsolateHolderService.ACTION_SHUTDOWN
            ContextCompat.startForegroundService(context, this)
        }
    }

    private fun initializeService(args: Map<Any, Any>) {
        setCallbackDispatcherHandle(args[Keys.ARG_CALLBACK_DISPATCHER] as Long)
    }

    private fun updateNotificationText(args: Map<Any, Any>) {
        if (!IsolateHolderService.isServiceRunning) return
        Intent(context, IsolateHolderService::class.java).apply {
            action = IsolateHolderService.ACTION_UPDATE_NOTIFICATION
            args[Keys.SETTINGS_ANDROID_NOTIFICATION_TITLE]?.let { putExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_TITLE, it as String) }
            args[Keys.SETTINGS_ANDROID_NOTIFICATION_MSG]?.let { putExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_MSG, it as String) }
            ContextCompat.startForegroundService(context, this)
        }
    }

    private fun setCallbackDispatcherHandle(handle: Long) {
        context.getSharedPreferences(Keys.SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE).edit()
            .putLong(Keys.CALLBACK_DISPATCHER_HANDLE_KEY, handle).apply()
    }

    override fun onNewIntent(intent: Intent?): Boolean {
        if (intent?.action != Keys.NOTIFICATION_ACTION) return false
        PreferencesManager.getNotificationCallback(activity)?.let { callback ->
            IsolateHolderService.backgroundEngine?.let { engine ->
                MethodChannel(engine.dartExecutor.binaryMessenger, Keys.BACKGROUND_CHANNEL_ID).invokeMethod(Keys.BCM_NOTIFICATION_CLICK, callback)
            }
        }
        return true
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {}
    override fun onDetachedFromActivity() {}
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {}
    override fun onAttachedToActivity(binding: ActivityPluginBinding) { activity = binding.activity }
    override fun onDetachedFromActivityForConfigChanges() {}
}
