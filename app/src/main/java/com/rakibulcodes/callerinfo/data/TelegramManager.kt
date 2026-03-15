package com.rakibulcodes.callerinfo.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi

class TelegramManager private constructor(private val context: Context) {

    private var client: Client? = null
    private val _authState = MutableSharedFlow<TdApi.AuthorizationState>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val authState = _authState.asSharedFlow()

    private val _updates = MutableSharedFlow<TdApi.Object>(
        replay = 50,
        extraBufferCapacity = 50,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val updates = _updates.asSharedFlow()

    private val prefs = context.getSharedPreferences("TelegramSettings", Context.MODE_PRIVATE)

    init {
        try {
            // Try to load the native library. 
            // The library name might be "tdjni" or similar depending on the packaging.
            System.loadLibrary("tdjni")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("TelegramManager", "Native library tdjni not found: ${e.message}")
        }
        setupClient()
    }

    private fun setupClient() {
        try {
            client = Client.create(ResultHandler(), null, null)
        } catch (e: Exception) {
            Log.e("TelegramManager", "Failed to create TDLib client: ${e.message}")
        }
    }

    fun send(query: TdApi.Function<out TdApi.Object>, callback: (TdApi.Object) -> Unit) {
        if (client == null) {
            Log.e("TelegramManager", "Client is null, cannot send query")
            return
        }
        client?.send(query) { result ->
            callback(result)
        }
    }

    suspend fun <T : TdApi.Object> sendSuspend(query: TdApi.Function<T>): T {
        val deferred = CompletableDeferred<T>()
        if (client == null) {
            Log.e("TelegramManager", "Client is null, cannot sendSuspend query")
            // You might want to throw an exception or return a specific error object here
        }
        client?.send(query) { result ->
            @Suppress("UNCHECKED_CAST")
            deferred.complete(result as T)
        }
        return deferred.await()
    }

    inner class ResultHandler : Client.ResultHandler {
        override fun onResult(`object`: TdApi.Object) {
            _updates.tryEmit(`object`)
            
            val constructorId = try {
                val field = `object`.javaClass.getField("CONSTRUCTOR")
                field.getInt(null)
            } catch (e: Exception) {
                0
            }

            if (constructorId == TdApi.UpdateAuthorizationState.CONSTRUCTOR) {
                val update = `object` as TdApi.UpdateAuthorizationState
                handleAuthState(update.authorizationState)
            }
        }
    }

    private fun handleAuthState(state: TdApi.AuthorizationState) {
        Log.d("TelegramManager", "Auth State: ${state::class.java.simpleName}")
        _authState.tryEmit(state)

        val constructorId = try {
            val field = state.javaClass.getField("CONSTRUCTOR")
            field.getInt(null)
        } catch (e: Exception) {
            0
        }

        when (constructorId) {
            TdApi.AuthorizationStateWaitTdlibParameters.CONSTRUCTOR -> {
                val databaseDirectory = context.filesDir.absolutePath + "/tdlib/db"
                val filesDirectory = context.filesDir.absolutePath + "/tdlib/files"
                val apiId = prefs.getInt("api_id", 0)
                val apiHash = prefs.getString("api_hash", "") ?: ""
                
                send(TdApi.SetTdlibParameters(
                    false, // useTestDc
                    databaseDirectory,
                    filesDirectory,
                    ByteArray(0), // databaseEncryptionKey
                    true, // useFileDatabase
                    true, // useChatInfoDatabase
                    true, // useMessageDatabase
                    false, // useSecretChats
                    apiId,
                    apiHash,
                    "en", // systemLanguageCode
                    android.os.Build.MODEL,
                    android.os.Build.VERSION.RELEASE,
                    "1.0" // applicationVersion
                )) { }
            }
            TdApi.AuthorizationStateReady.CONSTRUCTOR -> {
                prefs.edit().putBoolean("is_logged_in", true).apply()
            }
            TdApi.AuthorizationStateLoggingOut.CONSTRUCTOR -> {
                prefs.edit().putBoolean("is_logged_in", false).apply()
            }
            TdApi.AuthorizationStateClosed.CONSTRUCTOR -> {
                setupClient()
            }
        }
    }

    fun isReady(): Boolean = prefs.getBoolean("is_logged_in", false)

    companion object {
        @Volatile
        private var INSTANCE: TelegramManager? = null

        fun getInstance(context: Context): TelegramManager {
            return INSTANCE ?: synchronized(this) {
                val instance = TelegramManager(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}
