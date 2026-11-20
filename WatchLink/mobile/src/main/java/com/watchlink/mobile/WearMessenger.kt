package com.watchlink.mobile

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.watchlink.shared.Capabilities
import com.watchlink.shared.Paths
import com.watchlink.shared.WireJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.tasks.await

/** Reusable Data Layer sending helpers. */
object WearMessenger {
    private const val TAG = "WearMessenger"

    private suspend inline fun <T> attempt(what: String, block: () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$what failed", e)
            null
        }

    /** Nodes that currently have the WatchLink watch app (capability). */
    suspend fun capabilityNodes(context: Context): List<Node> {
        val app = context.applicationContext
        return attempt("getCapability") {
            Wearable.getCapabilityClient(app)
                .getCapability(Capabilities.WATCH, CapabilityClient.FILTER_REACHABLE)
                .await()
                .nodes
                .toList()
        } ?: emptyList()
    }

    suspend fun connectedNodes(context: Context): List<Node> {
        val app = context.applicationContext
        return attempt("connectedNodes") {
            Wearable.getNodeClient(app).connectedNodes.await().toList()
        } ?: emptyList()
    }

    /** Capability nodes, falling back to every connected node. */
    suspend fun watchNodes(context: Context): List<Node> {
        val viaCapability = capabilityNodes(context)
        if (viaCapability.isNotEmpty()) return viaCapability
        return connectedNodes(context)
    }

    suspend fun send(context: Context, nodeId: String, path: String, bytes: ByteArray = ByteArray(0)): Boolean {
        val app = context.applicationContext
        return attempt("sendMessage $path") {
            Wearable.getMessageClient(app).sendMessage(nodeId, path, bytes).await()
            true
        } ?: false
    }

    suspend fun sendToNodes(context: Context, nodes: List<Node>, path: String, bytes: ByteArray = ByteArray(0)): Int {
        var ok = 0
        for (node in nodes) {
            if (send(context, node.id, path, bytes)) ok++
        }
        return ok
    }

    /** Sends to all watches. Returns the number of nodes it was delivered to. */
    suspend fun sendToWatches(context: Context, path: String, bytes: ByteArray = ByteArray(0)): Int =
        sendToNodes(context, watchNodes(context), path, bytes)

    suspend fun sendSettings(context: Context): Int =
        sendToWatches(context, Paths.SETTINGS, WireJson.encode(WatchLinkPrefs.watchSettings(context)))
}

/** In-process events coming from the Data Layer listener, consumed by the UI. */
object LinkEvents {
    /** Emits SystemClock.elapsedRealtime() each time a PONG arrives. */
    val pongs = MutableSharedFlow<Long>(extraBufferCapacity = 4)
}
