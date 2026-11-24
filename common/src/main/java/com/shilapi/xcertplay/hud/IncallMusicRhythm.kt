package com.shilapi.xcertplay.hud

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import com.shilapi.xcertplay.AirPlayPersistence
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * Drives the head unit's ambient-light music rhythm from CarPlay's music.
 *
 * Transactions used, and what each one buys (ids verified against the service's Stub):
 *
 *   musicRhythmSwitchOn(boolean)  transaction 3 - declares this app the rhythm source. The
 *                                 service keeps that in a runtime flag, and its spectrum loop
 *                                 refuses to build a Visualizer while the flag is not "2" (it logs
 *                                 "Visualizer不需要初始化：rhythmSwitchOn=1"). Without it a session
 *                                 id is stored and then ignored, which is why the lamps only ever
 *                                 followed the car's own player and needed its Bluetooth media
 *                                 audio switched off.
 *   setAudioSessionId(int)        transaction 2 - the session of the music AudioTrack.
 *   setMusicPlayStatus(int)       transaction 1 - 2 while playing, 1 when not.
 *   setMusicLightPlayStatus(bool) transaction 9 - the boolean form newer firmware reads.
 *   musicAlbumPicture(String)     transaction 4 - absolute path of a cover image. The service
 *                                 runs BitmapFactory.decodeFile + Palette over it to pick the
 *                                 lamp colour; without it the colour never changes.
 *
 * The flag is memory only, so it is re-sent for every binding. The car's own player never calls
 * musicRhythmSwitchOn, so taking it over here does not fight anything - but it is not undone on
 * stop either, matching what the reference app does.
 *
 * It never attaches a Visualizer of its own. One session can carry a single Visualizer, so a
 * probe here would knock the manager's listener off the session it was just given (field
 * 2026-11-01 13:30: lamps went dark on connect, lit once playback started).
 */
class IncallMusicRhythm private constructor(private val context: Context) {
    companion object {
        private const val DESCRIPTOR =
            "com.incall.apps.commoninterface.vehiclesetting.IVehicleSettingService"
        private const val SERVICE_ACTION = "com.incall.apps.start.VehicleSettingService"
        private const val SERVICE_PACKAGE = "com.incall.apps.commonservice"
        private const val TX_PLAY_STATUS = 1
        private const val TX_AUDIO_SESSION = 2
        /**
         * musicRhythmSwitchOn(boolean). The service keeps a runtime flag that only this call moves
         * to "2"; its spectrum loop refuses to build a Visualizer unless the flag is "2" (it logs
         * "Visualizer不需要初始化：rhythmSwitchOn=1"). Without it a session id is stored but never
         * analysed, which is why the lamps only followed the car's own player.
         */
        private const val TX_RHYTHM_SWITCH = 3
        /** setMusicLightPlayStatus(boolean); newer firmware wants this instead of the int status. */
        private const val TX_LIGHT_STATUS = 9
        /** getMusicRhythmSwitchState(): true while the service still considers us the source. */
        private const val TX_SWITCH_STATE = 10
        /** musicAlbumPicture(String): a file path the service decodes and runs Palette over. */
        private const val TX_ALBUM_PICTURE = 4
        /** Square size of the redrawn cover; the service only samples it for a colour. */
        private const val COVER_SIZE = 512
        private const val COVER_QUALITY = 92
        /** The car quantises whatever it is given to 24 hues, so we rebuild the picture in one. */
        private const val HUE_BUCKETS = 24
        /** 2 = playing; only this value starts the manager's spectrum loop. */
        private const val PLAYING = 2
        /** 1 = not playing; anything but 2 stops the loop. */
        private const val NOT_PLAYING = 1
        /** The sink prebuffers for ~1 s before the first PCM write; the Visualizer needs audio. */
        private const val HANDOFF_DELAY_MS = 1_500L
        /**
         * How often to check whether we are still the service's rhythm source.
         *
         * The car's own media UI takes the flag back the moment it comes up (field 2026-11-02:
         * opening the Bluetooth music screen killed the rhythm and nothing brought it back). The
         * flag is memory only and the service reports it through transaction 10, so a light poll
         * can put it back without waiting for the next track.
         */
        private const val REASSERT_INTERVAL_MS = 3_000L
        /** A track change and a play transition can land together; one rebuild covers both. */
        private const val PLAYBACK_PUSH_GAP_MS = 1_000L
        /** Give the phone a moment to reopen stream 102 itself before asking it to play again. */
        private const val MISSING_MEDIA_GRACE_MS = 2_000L
/** A wheel pause's 102 teardown follows within ~2.5 s; five seconds is the classification window. */
private const val WHEEL_PAUSE_WINDOW_MS = 5_000L
        /** Space repeated PLAY nudges; they are no-ops on an already-playing phone. */
        private const val MISSING_MEDIA_REPEAT_MS = 2_000L
        /** Stop nudging once this many have been sent with stream 102 still absent. */
        private const val MISSING_MEDIA_MAX_ATTEMPTS = 3

        /** Recovery: an explicit play. Already playing makes it a no-op for the state. */
        const val MEDIA_RECOVERY_PLAY = 1

        /**
         * One instance for the whole process, the way the reference app does it.
         *
         * Its binding and the service's source flag both outlive an activity: rebuilding the
         * manager on every activity restart meant a fresh bind handshake and a flag the service
         * had already dropped, which is why reconnects used to come back silent. Held on the
         * application context so nothing here can leak an activity.
         */
        @Volatile private var instance: IncallMusicRhythm? = null

        /** Returns the process-wide manager and points its log at the caller's activity. */
        fun get(context: Context, log: (String) -> Unit): IncallMusicRhythm {
            val existing = instance
            if (existing != null) {
                existing.logger = log
                return existing
            }
            return synchronized(this) {
                val created = instance ?: IncallMusicRhythm(context.applicationContext).also {
                    instance = it
                }
                created.logger = log
                created
            }
        }

        /** Called from settings: stop at once, or take over the session already playing. */
        fun refreshActive() {
            instance?.refreshFromSettings()
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    /** Replaced on every activity create and cleared when it goes away; never holds it for long. */
    @Volatile private var logger: ((String) -> Unit)? = null

    private fun log(message: String) {
        logger?.invoke(message)
    }
    private var service: IBinder? = null
    private var binding = false
    private var bound = false
    @Volatile private var lastSessionId = 0
    /** Last wheel play/pause key that sent PAUSE; the 102 teardown follows it for a normal pause. */
    @Volatile private var wheelPauseAt = 0L
    private var handedSession = 0
    /** The source flag lives in the service's memory only, so it is re-sent per binding. */
    private var switchedOn = false
    /** Set when the service turns out to lack the state query; polling would only churn. */
    private var reassertDisabled = false
    /** Tracks the call/voice edge so the poll can rebuild the spectrum once it is over. */
    private var wasInCall = false
    /** A track change and a play transition can arrive together; one rebuild is enough. */
    private var lastPlaybackPushAt = 0L
    /** The iPhone's own play state; the media stream can be absent while this stays true. */
    private var phonePlaying = false
    /** Current iPhone playback state, read by the wheel play/pause target selection. */
    val isPhonePlaying: Boolean get() = phonePlaying

    /** 0 means the call/no-session state; >0 is a normal pause with a kept car-side session. */
    val currentSessionId: Int get() = lastSessionId

    /** A manual media key takes control; drop the automatic missing-stream PLAY retries. */
    fun cancelMediaRecovery() {
        handler.removeCallbacks(requestMediaStream)
        mediaRecoveryAttempts = 0
    }

    /** The wheel play/pause key chose PAUSE; mark the normal-pause origin. */
    fun noteWheelPause() {
        wheelPauseAt = SystemClock.elapsedRealtime()
    }
    /** How many explicit PLAY nudges have been sent for the current missing-stream wait. */
    private var mediaRecoveryAttempts = 0
    /** Timing of the current media stream, to diagnose a stream that Bluetooth preempts at once. */
    @Volatile private var mediaStreamOpenedAt = 0L
    @Volatile private var mediaStreamOpenedSession = 0
    /** Main-thread hook installed by the host; asks CarPlay to start the missing media stream. */
    @Volatile var mediaStreamRecovery: ((Int) -> Unit)? = null
    /** Absolute path of the cover last written for the service, and the bitmap it came from. */
    @Volatile private var coverPath: String? = null
    private var coverIdentity = 0
    private val coverIo = Executors.newSingleThreadExecutor { task ->
        Thread(task, "diplay-ambient-cover").apply { isDaemon = true }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder
            binding = false
            log("Ambient: vehicle setting service connected=${binder != null}")
            if (binder == null) return
            // A fresh binding knows nothing about us, and the handoff that asked for it may have
            // run while it was still connecting. Replay the whole state instead of waiting for the
            // next track; without this a slow bind leaves the lamps dark until something changes.
            switchedOn = false
            handedSession = 0
            if (lastSessionId > 0 && AirPlayPersistence.loadAmbientRhythmEnabled(context)) {
                handler.removeCallbacks(handoff)
                handler.postDelayed(handoff, HANDOFF_DELAY_MS)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            // The binding is gone with the service process; allow a fresh bind, or bind() would
            // short-circuit on the stale flag and the rhythm could never come back.
            bound = false
            binding = false
            log("Ambient: vehicle setting service disconnected")
        }
    }

    /** The media AudioTrack started (id > 0) or went away (0). Called from the media threads. */
    fun onMediaTrack(sessionId: Int) {
        val wheelPaused = sessionId <= 0 &&
            wheelPauseAt > 0 &&
            SystemClock.elapsedRealtime() - wheelPauseAt <= WHEEL_PAUSE_WINDOW_MS
        if (sessionId > 0) {
            wheelPauseAt = 0L
            mediaStreamOpenedAt = SystemClock.elapsedRealtime()
            mediaStreamOpenedSession = sessionId
            lastSessionId = sessionId
        } else {
            if (mediaStreamOpenedSession > 0) {
                val livedMs = SystemClock.elapsedRealtime() - mediaStreamOpenedAt
                if (livedMs in 1..2000) {
                    log(
                        "Ambient: media stream lived ${livedMs}ms - preempted by the head unit's " +
                            "Bluetooth media audio; turn that setting off for the rhythm",
                    )
                }
                mediaStreamOpenedSession = 0
                mediaStreamOpenedAt = 0L
            }
        }
        handler.removeCallbacks(handoff)
        // A fresh stream is the recovery the delayed PLAY request was waiting for.
        if (sessionId > 0) {
            handler.removeCallbacks(requestMediaStream)
            mediaRecoveryAttempts = 0
        }
        handler.post {
            if (!AirPlayPersistence.loadAmbientRhythmEnabled(context)) {
                stop("switch off")
                return@post
            }
            if (sessionId > 0) {
                if (service == null) bind()
                handler.postDelayed(handoff, HANDOFF_DELAY_MS)
                return@post
            }
            if (wheelPaused) {
                // Wheel pause: keep the reference app's no-stop/no-clear behaviour.
                log(
                    "Ambient: wheel pause teardown, keeping car-side session " +
                        "$lastSessionId (no teardown)",
                )
                return@post
            }
            // Non-wheel pause: a call interrupted the media. Restore the 8cf55b09 path.
            lastSessionId = 0
            playStatus(false, "media stopped")
            lightStatus(false)
            log("Ambient: non-wheel pause teardown, old stop/clear (call path)")
        }
    }

    /**
     * The sink says a telephony call has started. Kept for the host's diagnostic, but the call
     * path now uses the wheel-pause origin at the 102 teardown, so this does not stop anything.
     */
    fun onCallStarted() = Unit

    private val handoff = Runnable {
        val session = lastSessionId
        if (session <= 0) return@Runnable
        if (!AirPlayPersistence.loadAmbientRhythmEnabled(context)) {
            stop("switch off")
            return@Runnable
        }
        if (service != null) {
            // Every start re-declares the source, the same way the reference app does. The car's
            // media UI clears the flag whenever it wakes, and a paused-then-resumed track reuses
            // its session id, so waiting for a session change leaves the lamps dark.
            // Same order as the reference app: status, then the session it must analyse, then the
            // source declaration that lets that session through, then the light state.
            playStatus(true, "session $session playing")
            handSession(session)
            switchOn(force = true)
            lightStatus(true)
            // After the play status: the service only decodes the picture once its status is > 0.
            pushCover()
            startReassert()
        } else {
            bind()
        }
    }

    /**
     * CarPlay's artwork for the current item changed. Called on the main thread, null included.
     *
     * A null (the gap between two items) keeps whatever colour is already set; clearing it would
     * blink the lamps back to the default every track change.
     */
    fun onArtwork(bitmap: Bitmap?) {
        if (bitmap == null) return
        handler.post {
            if (coverIo.isShutdown) return@post
            if (!AirPlayPersistence.loadAmbientRhythmEnabled(context)) return@post
            val identity = System.identityHashCode(bitmap)
            if (identity == coverIdentity) return@post
            coverIdentity = identity
            coverIo.execute { writeCover(bitmap) }
        }
    }

    /** Writes the cover where the service can decode it; its own uid reads the path directly. */
    private fun writeCover(artwork: Bitmap) {
        val dir = context.getExternalFilesDir("rhythm") ?: File(context.cacheDir, "rhythm")
        if (!dir.exists() && !dir.mkdirs()) {
            log("Ambient: cover dir unavailable ${dir.absolutePath}")
            return
        }
        val hue = dominantHue(artwork)
        val cover = redraw(hue)
        val file = File(dir, "cover_${System.currentTimeMillis()}.jpg")
        try {
            FileOutputStream(file).use {
                cover.compress(Bitmap.CompressFormat.JPEG, COVER_QUALITY, it)
            }
            file.setReadable(true, false)
            dir.listFiles { candidate -> candidate.name.startsWith("cover_") && candidate != file }
                ?.forEach { it.delete() }
            coverPath = file.absolutePath
            log("Ambient: cover hue=${hue.toInt()} ${file.length()} bytes -> ${file.absolutePath}")
            handler.post { pushCover() }
        } catch (error: Exception) {
            log("Ambient: cover write failed ${error.javaClass.simpleName}: ${error.message}")
        } finally {
            cover.recycle()
        }
    }

    /**
     * The hue the car would settle on, found the way its own picker does: drop grey, black and
     * blown-out pixels, weight the rest by S²V, then take the heaviest 15-degree bucket. Handing it
     * the raw artwork instead often lands further than 100 away from its 64-colour palette, which
     * makes the service fall back to a fixed default colour.
     */
    private fun dominantHue(source: Bitmap): Float {
        val sample = Bitmap.createScaledBitmap(source, 64, 64, true)
        val weight = FloatArray(HUE_BUCKETS)
        val sumR = FloatArray(HUE_BUCKETS)
        val sumG = FloatArray(HUE_BUCKETS)
        val sumB = FloatArray(HUE_BUCKETS)
        val hsv = FloatArray(3)
        var totalR = 0L
        var totalG = 0L
        var totalB = 0L
        var pixels = 0
        for (y in 0 until 64) {
            for (x in 0 until 64) {
                val pixel = sample.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                totalR += r
                totalG += g
                totalB += b
                pixels++
                Color.RGBToHSV(r, g, b, hsv)
                if (hsv[1] < 0.25f || hsv[2] < 0.12f || hsv[2] > 0.95f) continue
                val gain = hsv[1] * hsv[1] * hsv[2]
                val bucket = (hsv[0] / 15f).toInt().coerceIn(0, HUE_BUCKETS - 1)
                weight[bucket] += gain
                sumR[bucket] += r * gain
                sumG[bucket] += g * gain
                sumB[bucket] += b * gain
            }
        }
        if (sample !== source) sample.recycle()
        var best = -1
        var bestWeight = 0f
        for (i in 0 until HUE_BUCKETS) {
            if (weight[i] > bestWeight) {
                bestWeight = weight[i]
                best = i
            }
        }
        val rgb = if (best >= 0 && bestWeight > 0f) {
            Color.rgb(
                (sumR[best] / bestWeight).toInt().coerceIn(0, 255),
                (sumG[best] / bestWeight).toInt().coerceIn(0, 255),
                (sumB[best] / bestWeight).toInt().coerceIn(0, 255),
            )
        } else {
            Color.rgb(
                (totalR / pixels).toInt().coerceIn(0, 255),
                (totalG / pixels).toInt().coerceIn(0, 255),
                (totalB / pixels).toInt().coerceIn(0, 255),
            )
        }
        Color.colorToHSV(rgb, hsv)
        return hsv[0]
    }

    /** Repaints that one hue as a bright diagonal gradient so the palette match is a sure thing. */
    private fun redraw(hue: Float): Bitmap {
        val cover = Bitmap.createBitmap(COVER_SIZE, COVER_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(cover)
        val from = floatArrayOf(hue, 0.80f, 0.85f)
        val to = floatArrayOf(hue, 0.95f, 0.98f)
        val paint = Paint().apply {
            shader = LinearGradient(
                0f,
                0f,
                COVER_SIZE.toFloat(),
                COVER_SIZE.toFloat(),
                Color.HSVToColor(from),
                Color.HSVToColor(to),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, COVER_SIZE.toFloat(), COVER_SIZE.toFloat(), paint)
        return cover
    }

    private fun pushCover() {
        val path = coverPath ?: return
        if (service == null) return
        val sent = call(TX_ALBUM_PICTURE) { it.writeString(path) }
        log("Ambient: album picture -> $sent")
    }

    /**
     * Puts the source flag back if the car's own media UI took it. Runs only while a track is
     * loaded and the service is bound; a cleared flag means the session we handed over is being
     * stored and ignored, so both calls are repeated.
     */
    private val reassert = object : Runnable {
        override fun run() {
            if (reassertDisabled) return
            if (!AirPlayPersistence.loadAmbientRhythmEnabled(context)) return
            val session = lastSessionId
            val inCall = inCallMode()
            if (session > 0 && service != null) {
                if (wasInCall && !inCall) {
                    // The call took the audio route and the unit tore the Visualizer down without
                    // rebuilding it afterwards; the media AudioTrack is the same one, so nothing
                    // else here would fire. Both this app and the reference lose the rhythm after
                    // a call (field 2026-11-02 19:38), which is why the rebuild is explicit.
                    switchOn(force = true)
                    call(TX_AUDIO_SESSION) { it.writeInt(session) }
                    log("Ambient: call ended, re-handed session $session")
                } else if (!inCall && !call(TX_SWITCH_STATE) { }) {
                    switchOn(force = true)
                    call(TX_AUDIO_SESSION) { it.writeInt(session) }
                    // Read it back: on firmware without transaction 10 every read looks "cleared",
                    // and re-declaring every 3 s would rebuild the Visualizer and chop the rhythm.
                    val confirmed = call(TX_SWITCH_STATE) { }
                    if (!confirmed) {
                        reassertDisabled = true
                        log("Ambient: switch state unreadable, stopped polling")
                        return
                    }
                    log("Ambient: source flag was cleared, re-asserted session $session")
                }
            }
            wasInCall = inCall
            handler.postDelayed(this, REASSERT_INTERVAL_MS)
        }
    }

    /** Call, Siri and voice messages all take the audio route without touching our AudioTrack. */
    private fun inCallMode(): Boolean {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return audio.mode == AudioManager.MODE_IN_CALL ||
            audio.mode == AudioManager.MODE_IN_COMMUNICATION
    }

    private fun startReassert() {
        if (reassertDisabled) return
        handler.removeCallbacks(reassert)
        handler.postDelayed(reassert, REASSERT_INTERVAL_MS)
    }

    /** The settings switch changed. */
    fun refreshFromSettings() {
        handler.post {
            if (!AirPlayPersistence.loadAmbientRhythmEnabled(context)) {
                stop("switch off")
                return@post
            }
            val session = lastSessionId
            if (session <= 0) {
                log("Ambient: switched on, waiting for a music track")
                return@post
            }
            if (service != null) {
                playStatus(true, "session $session playing")
                handSession(session)
                switchOn(force = true)
                lightStatus(true)
                pushCover()
                startReassert()
            } else {
                bind()
            }
        }
    }

    /**
     * The phone says it is playing but CarPlay's media stream is still absent.
     *
     * One explicit PLAY (not the wheel's PLAY_PAUSE toggle) is sent a few times, gated by a grace
     * period so the phone gets a chance to reopen stream 102 itself first. An already-playing phone
     * treats the command as a no-op for its state, so it can never toggle playback or loop - it can
     * only help when the iPhone genuinely stalled. Field 2026-11-02 22:08 showed that a PAUSE+PLAY
     * escalation toggled the iPhone every few seconds without ever reopening stream 102, which is
     * the "repeated auto pause/play" the user saw; this bounded PLAY-only nudge avoids that.
     */
    private val requestMediaStream: Runnable = Runnable {
        if (!AirPlayPersistence.loadAmbientRhythmEnabled(context)) return@Runnable
        if (!phonePlaying || lastSessionId > 0) return@Runnable
        if (mediaRecoveryAttempts >= MISSING_MEDIA_MAX_ATTEMPTS) return@Runnable
        mediaRecoveryAttempts++
        mediaStreamRecovery?.invoke(MEDIA_RECOVERY_PLAY)
        log(
            "Ambient: media stream missing, explicit CarPlay PLAY sent " +
                "(attempt $mediaRecoveryAttempts/$MISSING_MEDIA_MAX_ATTEMPTS)",
        )
        if (mediaRecoveryAttempts < MISSING_MEDIA_MAX_ATTEMPTS) {
            handler.postDelayed(requestMediaStream, MISSING_MEDIA_REPEAT_MS)
        }
    }

    /**
     * Our music is coming out of the speakers again after a call, Siri or a voice message took
     * the audio route. The track was never closed, so nothing else here fires; the service's
     * spectrum was left on a session that had gone quiet and has to be rebuilt.
     */
    fun onMediaHeard() {
        handler.post {
            val session = lastSessionId
            if (session <= 0 || service == null) return@post
            if (!AirPlayPersistence.loadAmbientRhythmEnabled(context)) return@post
            switchOn(force = true)
            call(TX_AUDIO_SESSION) { it.writeInt(session) }
            log("Ambient: media audible again, re-handed session $session")
        }
    }

    /**
     * The wheel key wants an explicit CarPlay PLAY assist.
     *
     * Only in the one state where the car's Bluetooth is about to take the resume over AVRCP and
     * CarPlay never re-opens stream 102: the phone is paused, no media AudioTrack is alive
     * ([lastSessionId] was cleared by [onMediaTrack]), and no call owns the audio. Returns the
     * state for the log; null means "no assist".
     */
    fun wheelAssistDetail(): String? {
        if (phonePlaying || lastSessionId > 0 || inCallMode()) return null
        val mode = (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.mode ?: -1
        return "playing=$phonePlaying session=$lastSessionId mode=$mode"
    }

    /**
     * The phone itself started or stopped playing.
     *
     * This is the only signal that covers a call, Siri or a voice message: they pause the phone
     * while our AudioTrack stays open, so no create/close event fires and the playback head keeps
     * moving. The reference app drives its pushes from the same state.
     */
    fun onPlaybackChanged(playing: Boolean) {
        handler.post {
            if (!AirPlayPersistence.loadAmbientRhythmEnabled(context)) return@post
            phonePlaying = playing
            handler.removeCallbacks(requestMediaStream)
            val session = lastSessionId
            if (playing) {
                if (session <= 0) {
                    // Field 2026-11-02 21:07-21:09: a call tears down stream 102 and the phone can
                    // report playing=true without opening it again. The old session id is gone, so
                    // pushing it to the vehicle service only moves the lamps against dead audio.
                    log("Ambient: iPhone playback=true but the media stream is missing")
                    mediaRecoveryAttempts = 0
                    handler.postDelayed(requestMediaStream, MISSING_MEDIA_GRACE_MS)
                    return@post
                }
                if (service == null) {
                    bind()
                    return@post
                }
                val now = System.currentTimeMillis()
                if (now - lastPlaybackPushAt < PLAYBACK_PUSH_GAP_MS) return@post
                lastPlaybackPushAt = now
                playStatus(true, "phone playing")
                handSession(session)
                switchOn(force = true)
                lightStatus(true)
                pushCover()
                startReassert()
            } else {
                // Pause edge: mirror the reference app - never tell the vehicle "stopped". Sending
                // tx1(1)/tx9(false) here marks the session inactive so iOS will not re-open stream 102
                // for that session on resume (field 2026-11-02 22:33 / 22:08). Keep phonePlaying and
                // the cached session id intact; the resume edge re-asserts the full sequence and the
                // lamps come back. The car side dims the lamps on its own once audio is gone.
                log("Ambient: iPhone playback=false (pause): keeping car-side session alive, no teardown")
            }
            log("Ambient: iPhone playback=$playing")
        }
    }

    /**
     * The host activity is going away. The binding and the source flag stay with the process-wide
     * instance, so the next activity pushes immediately instead of racing a fresh connect; only
     * this activity's logger is dropped.
     */
    fun release() {
        handler.post {
            stop("released")
            lastSessionId = 0
            mediaStreamRecovery = null
            // The logger is not cleared here: a rebuilt activity may already have attached its
            // own, and clearing it would silence a live one. It is replaced on the next attach.
        }
    }

    private fun stop(reason: String) {
        if (service != null) {
            playStatus(false, "loop stopped ($reason)")
            lightStatus(false)
        }
        handler.removeCallbacks(reassert)
        handler.removeCallbacks(requestMediaStream)
        phonePlaying = false
        mediaRecoveryAttempts = 0
        handedSession = 0
        switchedOn = false
        // The binding stays up on purpose. The service holds the source flag in memory and a
        // rebind costs a full handshake, so reconnecting is exactly what used to lose the rhythm;
        // the reference app binds once and never unbinds.
    }

    private fun bind() {
        if (bound || binding) return
        binding = true
        val intent = Intent(SERVICE_ACTION).setPackage(SERVICE_PACKAGE)
        val ok = runCatching {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.onFailure {
            log("Ambient: bind failed ${it.javaClass.simpleName}: ${it.message}")
        }.getOrDefault(false)
        if (!ok) {
            binding = false
            log("Ambient: bindService returned false (service missing or not exported)")
        } else {
            bound = true
            log("Ambient: bound $SERVICE_PACKAGE/$SERVICE_ACTION")
        }
    }

    /**
     * Hands over the session every time it is called, even when the id has not changed.
     *
     * Attaching a Visualizer to a session can fail while the track is still filling its buffer, and
     * the service only retries on the next setAudioSessionId. Skipping repeats meant one failed
     * attach stayed failed for the whole track - the lamps blinked once and went dark (field
     * 2026-11-02 18:23). The reference app re-sends it on every push for the same reason.
     */
    private fun handSession(sessionId: Int) {
        val handed = call(TX_AUDIO_SESSION) { it.writeInt(sessionId) }
        handedSession = sessionId
        log("Ambient: audio session $sessionId -> $handed")
    }

    /** Tells the service that the rhythm source is us. The flag is memory only, so it can be taken back. */
    private fun switchOn(force: Boolean = false) {
        if (switchedOn && !force) return
        val ok = call(TX_RHYTHM_SWITCH) { it.writeInt(1) }
        switchedOn = ok
        log("Ambient: music rhythm source -> $ok${if (force) " (re-assert)" else ""}")
    }

    private fun playStatus(playing: Boolean, what: String) {
        val status = if (playing) PLAYING else NOT_PLAYING
        val ok = call(TX_PLAY_STATUS) { it.writeInt(status) }
        log("Ambient: $what -> play status $status accepted=$ok")
    }

    /** Kept separate so the push order can match the reference app: status, session, source, light. */
    private fun lightStatus(playing: Boolean) {
        call(TX_LIGHT_STATUS, readResult = false) { it.writeInt(if (playing) 1 else 0) }
    }

    private fun call(transaction: Int, readResult: Boolean = true, write: (Parcel) -> Unit): Boolean {
        val binder = service ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            write(data)
            if (!binder.transact(transaction, data, reply, 0)) return false
            reply.readException()
            if (!readResult) true else reply.readInt() != 0
        } catch (error: Exception) {
            log(
                "Ambient: transact $transaction failed " +
                    "${error.javaClass.simpleName}: ${error.message}",
            )
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
