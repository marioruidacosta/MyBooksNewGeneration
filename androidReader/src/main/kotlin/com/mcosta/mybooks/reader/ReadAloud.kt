package com.mcosta.mybooks.reader

import android.app.Application
import com.mcosta.mybooks.domain.EbookFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.readium.navigator.media.tts.TtsNavigator
import org.readium.navigator.media.tts.TtsNavigatorFactory
import org.readium.navigator.media.tts.android.AndroidTtsEngine
import org.readium.navigator.media.tts.android.AndroidTtsPreferences
import org.readium.navigator.media.tts.android.AndroidTtsSettings
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

private val SPEEDS = listOf(0.75, 1.0, 1.25, 1.5, 2.0)

/** The next reading-aloud speed: 0.75×, 1×, 1.25×, 1.5×, 2×, then 0.75× again. */
fun nextSpeed(current: Double): Double = SPEEDS.getOrNull(SPEEDS.indexOf(current) + 1) ?: SPEEDS.first()

fun speedLabel(speed: Double): String = if (speed % 1.0 == 0.0) "${speed.toInt()}×" else "$speed×"

/** Reading aloud needs the book's text: EPUB only (Readium has no text for PDFs). */
fun canReadAloud(format: EbookFormat) = format == EbookFormat.EPUB

/** The read-aloud bar: shown while [active], playing or paused, at [speed]. */
data class ReadAloudUi(val active: Boolean = false, val playing: Boolean = false, val speed: Double = 1.0)

/**
 * Reads [publication] aloud with Android's text-to-speech, through Readium, from a place. Each sentence read is
 * reported to [onUtterance] (to mark it and follow it); [onEnded] when the book or a stop request ends it.
 */
@OptIn(ExperimentalReadiumApi::class)
class ReadiumReadAloud(
    private val application: Application,
    private val publication: Publication,
    private val scope: CoroutineScope,
    private val onUtterance: (Locator) -> Unit,
    private val onEnded: () -> Unit,
) {
    private var tts: TtsNavigator<AndroidTtsSettings, AndroidTtsPreferences, AndroidTtsEngine.Error, AndroidTtsEngine.Voice>? = null
    private val jobs = mutableListOf<Job>()

    /** Starts reading at [from] (or the start); false when the phone or the book can't be read aloud. */
    suspend fun start(from: Locator?, speed: Double): Boolean {
        stop()
        val factory = TtsNavigatorFactory(application, publication) ?: return false
        val listener = object : TtsNavigator.Listener {
            override fun onStopRequested() {
                stop()
                onEnded()
            }
        }
        val nav = factory.createNavigator(listener, from, AndroidTtsPreferences(speed = speed)).getOrNull() ?: return false
        tts = nav
        jobs += scope.launch { nav.location.collect { onUtterance(it.utteranceLocator) } }
        jobs += scope.launch {
            nav.playback.collect { if (it.state is TtsNavigator.State.Ended) { stop(); onEnded() } }
        }
        nav.play()
        return true
    }

    fun play() = tts?.play()

    fun pause() = tts?.pause()

    fun setSpeed(speed: Double) = tts?.submitPreferences(AndroidTtsPreferences(speed = speed))

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        tts?.close()
        tts = null
    }
}
