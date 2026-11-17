package io.github.hyunchan.boggart

/** 엔진 활성화 모드. */
public enum class Mode {
    /** 규칙이 있는 요청만 조작하고 나머지는 통과시킨다. 모든 교환을 캡처한다. 기본값. */
    Active,

    /** 규칙을 지우지 않고 잠시 멈춘다. 조작 없이 캡처만 한다. */
    Paused,

    /**
     * 네트워크가 없는 상황을 흉내 낸다. 규칙이 있는 요청만 조작하고, 규칙이 없는 요청은 실제 호출 없이
     * [FailKind.UnknownHost]로 실패시켜 앱의 오프라인 처리가 동작하게 한다.
     */
    Offline,

    /** 조작·캡처 모두 하지 않는다. 화면에서는 고를 수 없고 `Config.mode` 등 코드로만 정한다. */
    Off,
}

/** 패치 결과가 [BodyValidator] 검증에 실패했을 때의 정책. */
public enum class OnInvalidPatch {
    /** 패치를 버리고 원래 응답을 전달한다. */
    Ignore,

    /** 검증에 실패한 바디를 그대로 전달해 파싱 오류를 재현한다. */
    PassThrough,
}

/** 교환의 출처 상태. */
public enum class Provenance {
    Original,
    Replaced,
    Patched,

    /** 패치 결과가 검증에 실패했지만 [OnInvalidPatch.PassThrough]로 그대로 전달됨. */
    PatchedInvalid,
    Failed,

    /** 규칙을 적용하지 못해 원래 응답을 전달함(패치 검증 실패, JSON 아닌 바디, 해석하지 못한 bodyFile). */
    InvalidIgnored,
}
