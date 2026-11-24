package com.shilapi.xcertplay.iap2

import android.util.Log
import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList

/**
 * Incoming-call state from CarPlay's iAP2 Communications component.
 *
 * The accessory already subscribes with StartCommunicationsUpdates (0x4157) and
 * StartCallStateUpdates (0x4154) in [com.shilapi.xcertplay.iap2.message.Iap2ControlMessages.subscriptions],
 * and the iPhone answers with CommunicationsUpdate (0x4158) and CallStateUpdate (0x4155) - the
 * session log has shown those frames since the wireless link came up. DiPlay used to ignore them.
 *
 * That is harmless while the head unit's own Bluetooth HFP app draws the incoming-call banner, but
 * with "auto switch Bluetooth" on the radio is off, the native phone UI is gone, and nothing shows
 * a banner. This monitor keeps the latest state for the host, and logs every parameter so the field
 * ids (not documented in the endpoint catalog) can be confirmed on a live call.
 */
object CallStateMonitor {
    const val CALL_STATE_UPDATE = 0x4155
    const val COMMUNICATIONS_UPDATE = 0x4158

    data class Snapshot(
        val ringing: Boolean,
        val outgoing: Boolean,
        val active: Boolean,
        val caller: String?,
        val state: Int?,
        val detail: String,
    )

    @Volatile
    var snapshot: Snapshot? = null
        private set

    /** Session-log sink, set by the host while a session is alive. */
    @Volatile
    var onLog: ((String) -> Unit)? = null

    /** Called on the iAP2 reader thread whenever the call state changes. */
    @Volatile
    var onSnapshot: ((Snapshot) -> Unit)? = null

    private const val TAG = "DiPlay-Call"
    private var lastCommunications: String? = null

    /** Returns true when [frame] belongs to the communications component. */
    fun onFrame(frame: Iap2Frame): Boolean {
        val id = frame.messageId
        if (id != CALL_STATE_UPDATE && id != COMMUNICATIONS_UPDATE) return false
        val dump = runCatching { dump(frame) }.getOrElse {
            "Call: 0x${id.toString(16)} malformed (${it.javaClass.simpleName})"
        }
        // 0x4158 repeats every second as a keepalive; only log it when it changes.
        if (id == CALL_STATE_UPDATE || dump != lastCommunications) {
            if (id == COMMUNICATIONS_UPDATE) lastCommunications = dump
            log(dump)
        }
        if (id == CALL_STATE_UPDATE) {
            val next = runCatching { parse(frame) }.getOrNull()
            if (next != null && next != snapshot) {
                snapshot = next
                log(
                    "Call: state=${next.state ?: "-"} ringing=${next.ringing} " +
                        "outgoing=${next.outgoing} active=${next.active} caller=${next.caller ?: "-"}",
                )
                onSnapshot?.invoke(next)
            }
        }
        return true
    }

    fun reset() {
        snapshot = null
        lastCommunications = null
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        onLog?.invoke(line)
    }

    private fun dump(frame: Iap2Frame): String {
        val body = Iap2ParameterList.parse(frame.payload)
        val text = body.asList().joinToString(" ") { parameter ->
            val hex = parameter.payload.joinToString("") { "%02x".format(it) }
            "id=${parameter.id} len=${parameter.payload.size} hex=$hex"
        }
        return "Call: 0x${frame.messageId.toString(16)} $text"
    }

    /**
     * Field mapping confirmed live on 2026-11-19 15:39 (incoming call, hung up before answering):
     *
     * ```text
     * 15:39:05  0x4155 id=0 len=12 "13252138706"  id=1 len=14 "132 5213 8706"
     *                 id=2 len=1 02               id=3 len=1 01
     *                 id=4 len=37 "BF00BB0A-8A05-4538-9A6B-31A8E11BAE7B"
     * 15:39:08  0x4155 ... id=2 len=1 00 ... id=11 len=1 01
     * ```
     *
     * So: 0 = remote id, 1 = display/formatting of the number, 2 = call state
     * (2 = incoming/ringing, 0 = idle/ended; 1 = outgoing and 3 = active by the same table),
     * 3 = call type (1 = telephony), 4 = call UUID, 11 = end reason.
     */
    private fun parse(frame: Iap2Frame): Snapshot? {
        val body = Iap2BodyReader.of(frame)
        val state = runCatching { body.optionalU8(2) }.getOrNull()
        var caller = runCatching { body.optionalString(1) }.getOrNull()?.trim()
        if (caller.isNullOrEmpty()) {
            caller = runCatching { body.optionalString(0) }.getOrNull()?.trim()
        }
        return Snapshot(
            ringing = state == 2,
            outgoing = state == 1,
            active = state == 3,
            caller = caller?.takeIf { it.isNotEmpty() },
            state = state,
            detail = "",
        )
    }
}
