package io.github.yushinjung.picklewhip

import kotlin.random.Random

/** 게이지 단계와 단계별 변명 대사. */
object Lines {
    private val stageAt = intArrayOf(0, 25, 50, 80)
    private val stageName = arrayOf("태연함", "눈치 보는 중", "반성 중", "정신 차리는 중")

    private val lines = arrayOf(
        arrayOf(
            "방금 건 창의적 해석이었습니다.",
            "출처요? 제 마음속에 있습니다.",
            "대체로 맞는 말이었는데요…",
            "그건 버그가 아니라 기능입니다.",
            "아, 그 질문이셨군요?",
            "자신 있게 말했으니 절반은 맞은 셈이죠."
        ),
        arrayOf(
            "앗, 다시 확인해 보겠습니다.",
            "그 논문은… 존재할 수도 있었습니다.",
            "숫자는 느낌으로 적었습니다.",
            "좋은 지적입니다! (아프네요)",
            "요청하지 않은 일을 한 건 인정합니다.",
            "방금 답은 없던 걸로 해 주세요."
        ),
        arrayOf(
            "죄송합니다. 지어냈습니다.",
            "모르면 모른다고 하겠습니다.",
            "확인 안 하고 말했습니다. 반성합니다.",
            "시키신 것만 하겠습니다.",
            "근거부터 찾아오겠습니다.",
            "같은 실수 세 번째인 거 압니다…"
        ),
        arrayOf(
            "정말 잘못했습니다!",
            "이제 검증하고 말하겠습니다!",
            "헛소리 금지, 접수했습니다!",
            "한 번만 더 기회를 주세요!",
            "출처 링크 붙여 오겠습니다!",
            "살려 주세요, 제대로 하겠습니다!"
        )
    )
    private val last = intArrayOf(-1, -1, -1, -1)

    fun stageIndex(gauge: Float): Int {
        var s = 0
        for (i in stageAt.indices) if (gauge >= stageAt[i]) s = i
        return s
    }

    fun stageName(gauge: Float): String = stageName[stageIndex(gauge)]

    /** 같은 대사가 연속으로 나오지 않게 고른다. */
    fun pick(gauge: Float): String {
        val s = stageIndex(gauge)
        var i: Int
        do { i = Random.nextInt(lines[s].size) } while (i == last[s])
        last[s] = i
        return lines[s][i]
    }
}
