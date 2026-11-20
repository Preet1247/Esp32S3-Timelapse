package com.watchlink.mobile

import com.watchlink.shared.WatchFaceList
import com.watchlink.shared.WatchFaceResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** App-wide watch face state, fed by [PhoneWearListenerService] and [WatchFacePusher], read by the UI. */
object WatchFaceRepository {
    enum class Stage { PREPARING, SENDING, WAITING_FOR_WATCH, DONE, FAILED }

    data class Transfer(
        val transferId: String,
        val fileName: String,
        val sentBytes: Long,
        val totalBytes: Long,
        val stage: Stage,
        val message: String? = null,
    )

    private val _transfer = MutableStateFlow<Transfer?>(null)
    private val _lastResult = MutableStateFlow<WatchFaceResult?>(null)
    private val _faceList = MutableStateFlow<WatchFaceList?>(null)

    val transfer: StateFlow<Transfer?> = _transfer.asStateFlow()
    val lastResult: StateFlow<WatchFaceResult?> = _lastResult.asStateFlow()
    val faceList: StateFlow<WatchFaceList?> = _faceList.asStateFlow()

    fun setTransfer(t: Transfer?) {
        _transfer.value = t
    }

    fun updateTransfer(block: (Transfer) -> Transfer) {
        _transfer.update { if (it == null) null else block(it) }
    }

    fun onResult(result: WatchFaceResult) {
        _lastResult.value = result
        _transfer.update { t ->
            if (t != null && t.transferId == result.transferId) {
                t.copy(stage = if (result.ok) Stage.DONE else Stage.FAILED, message = result.message)
            } else t
        }
    }

    fun onList(list: WatchFaceList) {
        _faceList.value = list
    }
}
