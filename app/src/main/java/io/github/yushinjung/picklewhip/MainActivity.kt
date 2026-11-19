package io.github.yushinjung.picklewhip

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** 설정 화면. 권한 상태, 혼낼 앱, 보낼 문구, 감도를 정한다. */
class MainActivity : Activity() {

    private lateinit var txtStatus: TextView
    private lateinit var txtGuide: TextView
    private lateinit var btnAppInfo: Button
    private lateinit var btnTarget: Button
    private lateinit var edtMessage: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtStatus = findViewById(R.id.txtStatus)
        txtGuide = findViewById(R.id.txtGuide)
        btnAppInfo = findViewById(R.id.btnAppInfo)
        btnTarget = findViewById(R.id.btnTarget)
        edtMessage = findViewById(R.id.edtMessage)

        findViewById<Button>(R.id.btnAccess).setOnClickListener { openAccessibility() }
        btnAppInfo.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }
        btnTarget.setOnClickListener { pickTarget() }

        edtMessage.setText(Prefs.message(this))

        val sw = findViewById<Switch>(R.id.swAuto)
        sw.isChecked = Prefs.autoSend(this)
        sw.setOnCheckedChangeListener { _, on -> Prefs.setAutoSend(this, on) }

        val rgHits = findViewById<RadioGroup>(R.id.rgHits)
        rgHits.check(
            when (Prefs.hits(this)) { 1 -> R.id.rbHit1; 2 -> R.id.rbHit2; 5 -> R.id.rbHit5; else -> R.id.rbHit3 }
        )
        rgHits.setOnCheckedChangeListener { _, id ->
            Prefs.setHits(this, when (id) { R.id.rbHit1 -> 1; R.id.rbHit2 -> 2; R.id.rbHit5 -> 5; else -> 3 })
        }

        val rgIdle = findViewById<RadioGroup>(R.id.rgIdle)
        rgIdle.check(
            when (Prefs.idleSec(this)) { 3 -> R.id.rbIdle3; 10 -> R.id.rbIdle10; 30 -> R.id.rbIdle30; else -> R.id.rbIdle5 }
        )
        rgIdle.setOnCheckedChangeListener { _, id ->
            Prefs.setIdleSec(this, when (id) { R.id.rbIdle3 -> 3; R.id.rbIdle10 -> 10; R.id.rbIdle30 -> 30; else -> 5 })
        }

        val rg = findViewById<RadioGroup>(R.id.rgSens)
        val s = Prefs.sens(this)
        rg.check(if (s > 1.2f) R.id.rbLow else if (s < 0.85f) R.id.rbHigh else R.id.rbMid)
        rg.setOnCheckedChangeListener { _, id ->
            Prefs.setSens(this, when (id) { R.id.rbLow -> 1.4f; R.id.rbHigh -> 0.7f; else -> 1f })
        }

        findViewById<Button>(R.id.btnTest).setOnClickListener {
            val svc = WhipService.instance
            if (svc == null) Toast.makeText(this, "먼저 접근성 서비스를 켜 주세요", Toast.LENGTH_SHORT).show()
            else svc.testCrack()
        }
    }

    override fun onResume() {
        super.onResume()
        val on = serviceEnabled()
        txtStatus.text = if (on) "켜짐 — 준비 완료" else "꺼짐 — 아래 순서대로 켜 주세요"
        // 켜진 뒤에는 안내를 접는다.
        txtGuide.visibility = if (on) View.GONE else View.VISIBLE
        btnAppInfo.visibility = if (on) View.GONE else View.VISIBLE
        val label = Prefs.targetLabel(this)
        btnTarget.text = if (label.isEmpty()) "앱 선택" else "$label (바꾸기)"
    }

    override fun onPause() {
        super.onPause()
        Prefs.setMessage(this, edtMessage.text.toString())
    }

    /** 접근성 설정을 연다. 일부 기기에서는 목록의 'AI 채찍' 항목을 강조해 보여 준다. */
    private fun openAccessibility() {
        val me = ComponentName(this, WhipService::class.java).flattenToString()
        val args = Bundle().apply { putString(":settings:fragment_args_key", me) }
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(":settings:fragment_args_key", me)
            .putExtra(":settings:show_fragment_args", args)
        startActivity(intent)
    }

    private fun serviceEnabled(): Boolean {
        val me = ComponentName(this, WhipService::class.java).flattenToString()
        val on = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return on.split(':').any { it.equals(me, ignoreCase = true) }
    }

    /** 설치된 앱 목록에서 혼낼 앱을 고른다. 이름에 pickle/피클이 들어간 앱을 맨 위에 둔다. */
    private fun pickTarget() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedWith(
                compareBy<Pair<String, String>> {
                    val l = it.second.lowercase()
                    if (l.contains("pickle") || l.contains("피클")) 0 else 1
                }.thenBy { it.second.lowercase() }
            )
        if (apps.isEmpty()) {
            Toast.makeText(this, "앱 목록을 가져오지 못했습니다", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("혼낼 앱")
            .setItems(apps.map { it.second }.toTypedArray()) { _, i ->
                Prefs.setTarget(this, apps[i].first, apps[i].second)
                btnTarget.text = "${apps[i].second} (바꾸기)"
            }
            .show()
    }
}
