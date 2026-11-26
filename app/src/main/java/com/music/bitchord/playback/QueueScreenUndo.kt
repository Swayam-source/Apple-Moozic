package com.music.zen.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

const val ACTION_QUEUE_REMOVE_WITH_UNDO = "com.music.zen.action.QUEUE_REMOVE_WITH_UNDO"
const val ACTION_QUEUE_REMOVE_ENTRIES_WITH_UNDO = "com.music.zen.action.QUEUE_REMOVE_ENTRIES_WITH_UNDO"
const val ACTION_QUEUE_CLEAR_WITH_UNDO = "com.music.zen.action.QUEUE_CLEAR_WITH_UNDO"
const val ACTION_QUEUE_UNDO = "com.music.zen.action.QUEUE_UNDO"
const val EXTRA_QUEUE_UNDO_TOKEN = "zen.queueUndo.token"
const val EXTRA_QUEUE_BULK_MEDIA_IDS = "zen.queueBulk.mediaIds"
const val EXTRA_QUEUE_BULK_ENTRY_IDS = "zen.queueBulk.entryIds"
const val EXTRA_QUEUE_BULK_ENTRY_ID_PRESENT = "zen.queueBulk.entryIdPresent"
const val EXTRA_QUEUE_BULK_OCCURRENCES = "zen.queueBulk.occurrences"

/** Process-local, latest-only UI feedback; never persisted or sent to a party. */
data class QueueUndoNotice(val token: String, val message: String)

object QueueScreenUndo {
    private val mutableNotice = MutableStateFlow<QueueUndoNotice?>(null)
    val notice: StateFlow<QueueUndoNotice?> = mutableNotice.asStateFlow()

    internal fun publish(notice: QueueUndoNotice) {
        mutableNotice.value = notice
    }

    internal fun clear() {
        mutableNotice.value = null
    }
}
