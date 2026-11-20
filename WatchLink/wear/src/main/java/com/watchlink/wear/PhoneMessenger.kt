package com.watchlink.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.watchlink.shared.Capabilities
import com.watchlink.shared.Paths
import com.watchlink.shared.WireJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** Watch -> phone messaging over the Wearable MessageClient. */
object PhoneMessenger {
    private const val TAG = "PhoneMessenger"

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _phoneName = MutableStateFlow<String?>(null)
    val phoneName: StateFlow<String?> = _phoneName.asStateFlow()

    private var monitorContext: Context? = null
    private val capabilityListener = CapabilityClient.OnCapabilityChangedListener {
        val ctx = monitorContext
        if (ctx != null) AppScope.scope.launch { refreshConnection(ctx) }
    }

    @Synchronized
    fun startMonitoring(context: Context) {
        if (monitorContext != null) return
        val app = context.applicationContext
        monitorContext = app
        Wearable.getCapabilityClient(app).addListener(capabilityListener, Capabilities.PHONE)
    }

    @Synchronized
    fun stopMonitoring() {
        val app = monitorContext ?: return
        monitorContext = null
        Wearable.getCapabilityClient(app).removeListener(capabilityListener)
    }

    private fun Collection<Node>.pickBest(): Node? = firstOrNull { it.isNearby } ?: firstOrNull()

    private suspend fun findPhoneNode(context: Context): Node? {
        val app = context.applicationContext
        val viaCapability = try {
            Wearable.getCapabilityClient(app)
                .getCapability(Capabilities.PHONE, CapabilityClient.FILTER_REACHABLE)
                .await()
                .nodes
                .pickBest()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Capability lookup failed", e)
            null
        }
        if (viaCapability != null) return viaCapability
        return try {
            Wearable.getNodeClient(app).connectedNodes.await().pickBest()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Node lookup failed", e)
            null
        }
    }

    suspend fun refreshConnection(context: Context): Boolean {
        val node = findPhoneNode(context)
        publish(node)
        return node != null
    }

    private fun publish(node: Node?) {
        _connected.value = node != null
        _phoneName.value = node?.displayName
    }

    /** Sends raw bytes to the phone. Returns true if the message was handed to the Data Layer. */
    suspend fun send(context: Context, path: String, payload: ByteArray): Boolean {
        val app = context.applicationContext
        val node = findPhoneNode(app)
        publish(node)
        if (node == null) {
            Log.w(TAG, "No phone node, dropping $path")
            return false
        }
        return try {
            Wearable.getMessageClient(app).sendMessage(node.id, path, payload).await()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "sendMessage($path) failed", e)
            false
        }
    }

    suspend inline fun <reified T> sendJson(context: Context, path: String, value: T): Boolean =
        send(context, path, WireJson.encode(value))

    /** Fire-and-forget on the application scope. */
    fun postBytes(context: Context, path: String, payload: ByteArray) {
        val app = context.applicationContext
        AppScope.scope.launch { send(app, path, payload) }
    }

    inline fun <reified T> post(context: Context, path: String, value: T) =
        postBytes(context, path, WireJson.encode(value))

    fun requestSync(context: Context) = postBytes(context, Paths.SYNC_REQUEST, ByteArray(0))
}
