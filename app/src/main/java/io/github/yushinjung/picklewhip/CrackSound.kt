package io.github.yushinjung.picklewhip

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** '짝' 소리를 코드로 합성한다. 음원 파일 없음. */
class CrackSound {
    private val sampleRate = 44100
    private var track: AudioTrack? = null

    init {
        try {
            val n = (sampleRate * 0.22f).toInt()
            val buf = ShortArray(n)
            val rnd = Random(7)
            var prev = 0f
            for (i in 0 until n) {
                val t = i / sampleRate.toFloat()
                val white = rnd.nextFloat() * 2f - 1f
                val high = white - prev * 0.85f          // 간단한 고역 강조
                prev = white
                val attack = min(1f, i / 40f)
                val crack = high * 0.55f * exp(-t * 38f) * attack
                val thump = sin(2.0 * PI * 90.0 * t).toFloat() * exp(-t * 45f) * 0.5f
                val v = (crack + thump).coerceIn(-1f, 1f)
                buf[i] = (v * 32767f * 0.9f).toInt().toShort()
            }
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(n * 2)
                .build()
            t.write(buf, 0, n)
            track = t
        } catch (e: Exception) {
            track = null
        }
    }

    fun play(volume: Float) {
        val t = track ?: return
        try {
            if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop()
            t.reloadStaticData()
            t.setVolume(volume.coerceIn(0f, 1f))
            t.play()
        } catch (e: Exception) {
            // 소리는 없어도 동작에 지장 없음
        }
    }

    fun release() {
        try { track?.release() } catch (e: Exception) { }
        track = null
    }
}
