package io.github.casimirkwon.smsforwarder.core

/**
 * 저장된 SIM 이 기기에 없을 때의 처리.
 *
 * 필터 편집 화면과 듀얼 SIM 설정 화면에서 저장된 슬롯이 현재 활성 SIM 목록에 없으면 선택 항목을 기본값(-1)으로 바꿔 보여 주고
 * "SIM을 찾을 수 없음" 안내창을 띄운다. 저장은 사용자가 그 화면을 저장·확인할 때 일어난다.
 * 전화 상태 권한이 있어 SIM 목록을 읽을 수 있을 때만 점검한다(권한이 없으면 SIM 선택 영역 자체를 숨긴다).
 */
object SimMissing {
    const val DEFAULT_SLOT = -1

    /** 저장된 슬롯이 지정돼 있는데(-1 이 아님) 활성 슬롯 목록에 없는가. */
    fun isMissing(saved: Int, activeSlots: Collection<Int>): Boolean = saved != DEFAULT_SLOT && saved !in activeSlots

    /** 화면에 선택해 둘 값. 없는 SIM 이면 기본값(-1). */
    fun effective(saved: Int, activeSlots: Collection<Int>): Int = if (isMissing(saved, activeSlots)) DEFAULT_SLOT else saved

    /** 안내 문구에 들어갈 목록: 사라진 항목 이름을 ", " 로 잇는다. 비면 안내창을 띄우지 않으므로 null. */
    fun joinedNames(names: List<String>): String? = if (names.isEmpty()) null else names.joinToString(", ")
}
