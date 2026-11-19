package io.github.yushinjung.picklewhip

import android.content.Context
import android.content.SharedPreferences

/** 설정 저장소. 서비스와 설정 화면이 같이 읽는다. */
object Prefs {
    private const val FILE = "whip"
    private const val K_PKG = "target_pkg"
    private const val K_LABEL = "target_label"
    private const val K_MSG = "message"
    private const val K_AUTO = "auto_send"
    private const val K_SENS = "sens"
    private const val K_HITS = "hits"
    private const val K_IDLE = "idle_sec"

    private fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun targetPkg(c: Context): String = sp(c).getString(K_PKG, "") ?: ""
    fun targetLabel(c: Context): String = sp(c).getString(K_LABEL, "") ?: ""
    fun setTarget(c: Context, pkg: String, label: String) =
        sp(c).edit().putString(K_PKG, pkg).putString(K_LABEL, label).apply()

    fun message(c: Context): String =
        sp(c).getString(K_MSG, null) ?: c.getString(R.string.default_message)
    fun setMessage(c: Context, v: String) = sp(c).edit().putString(K_MSG, v).apply()

    fun autoSend(c: Context): Boolean = sp(c).getBoolean(K_AUTO, true)
    fun setAutoSend(c: Context, v: Boolean) = sp(c).edit().putBoolean(K_AUTO, v).apply()

    /** 몇 번 휘두르면 게이지가 다 차는지. (1 / 2 / 3 / 5) */
    fun hits(c: Context): Int = sp(c).getInt(K_HITS, 3).coerceIn(1, 10)
    fun setHits(c: Context, v: Int) = sp(c).edit().putInt(K_HITS, v).apply()

    /** 휘두르지 않으면 몇 초 뒤에 채찍을 접을지. (3 / 5 / 10 / 30) */
    fun idleSec(c: Context): Int = sp(c).getInt(K_IDLE, 5).coerceIn(1, 600)
    fun setIdleSec(c: Context, v: Int) = sp(c).edit().putInt(K_IDLE, v).apply()

    /** 흔들기 임계값 배율. 클수록 둔감. (둔감 1.4 / 보통 1.0 / 민감 0.7) */
    fun sens(c: Context): Float = sp(c).getFloat(K_SENS, 1f)
    fun setSens(c: Context, v: Float) = sp(c).edit().putFloat(K_SENS, v).apply()
}
