# Boggart

안드로이드 앱의 API 응답을 런타임에 조작해 테스트·QA에 쓰는 Kotlin SDK.

## 문서

- 사용법은 `README.md`.
- 설계 문서(`docs-local/DESIGN.md` 구현된 설계, `docs-local/PLAN.md` 계획·미결 사항)는 로컬 전용이다. `docs-local/`은 gitignore되어 레포지토리에 없을 수 있다. 있으면 작업 전에 읽고, 구현이 커밋되면 그 설계를 PLAN에서 DESIGN으로 옮긴다.

## 규칙

- 패키지는 `io.github.hyunchan.boggart`. 되돌리기 API 이름은 `riddikulus`.
- core 모듈은 순수 JVM을 유지한다. 안드로이드·OkHttp·Ktor 의존을 core에 넣지 않는다.
- 공개 API를 바꾸면 `boggart-noop`도 같은 시그니처로 맞춘다(release 빌드는 noop으로 컴파일된다).
- 화면은 화면별 MVI ViewModel(State·Intent·UiEffect), 라이브러리 없는 수동 DI(`LocalBoggartStore`), Navigation 3로 만든다.
- 화면 용어: 규칙 출처는 임시·저장됨·프리셋, 조작은 빠른 편집·직접 편집·서버 대신 응답. 매일 보는 라벨은 비유 대신 "규칙"으로 쓴다.
- 문자열은 `values`(영국식 영어)와 `values-ko`를 함께 고친다.

## 검증

- `./gradlew check` (테스트, ktlint, Compose 규칙, 샘플 release APK에 noop 외의 Boggart 클래스가 없는지)
- `./gradlew :sample:assembleDebug :sample:assembleRelease` (noop 호환 컴파일 확인)
- 화면 동작은 `sample` 앱에서 프리셋을 켜 확인한다.

## 커밋

- 제목은 `type : 설명` 형식, 서술형("~한다")으로 끝낸다. type은 `feature`·`fix`·`refactor`·`chore`·`build`.
- 본문은 bullet 목록으로 쓴다.
- 원격에 올리기 전 로컬 커밋의 후속 수정은 새 커밋 대신 그 커밋에 합친다. 올린 뒤에는 새 커밋을 쌓는다.
