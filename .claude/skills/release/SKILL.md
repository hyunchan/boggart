---
name: release
description: Boggart를 Maven Central에 배포한다. 버전 결정 → CI 확인 → 태그 push(자동 공개) → 공개 확인 → README 의존성 버전·개발 버전 올림 → GitHub 릴리스 노트까지. 사용자가 "배포", "릴리스", "태그 달고 버전 올려" 등을 요청할 때 쓴다.
---

# Boggart 배포 절차

태그(`v*`)를 push하면 `.github/workflows/release.yml`이 `check` 후 `publishAndReleaseToMavenCentral`로 **바로 공개**한다. 공개된 버전은 지울 수 없으므로 태그 push 전에 아래 확인을 끝낸다.

## 버전이 들어가는 곳

| 위치 | 내용 |
|---|---|
| `gradle.properties` `VERSION_NAME` | 개발 버전(`X.Y.Z-SNAPSHOT`). 릴리스 워크플로는 태그 버전을 `-PVERSION_NAME`으로 덮어쓴다 |
| `Boggart.VERSION` | core 빌드가 `VERSION_NAME`에서 생성(`generateVersion`). 손으로 고치지 않는다 |
| `README.md`·`README.ko.md` 설치 예시 | 공개된 최신 버전. 공개 확인 후 올린다 |
| `sample` `versionName` | 배포하지 않으므로 건드리지 않는다 |

## 1. 버전 결정 (사용자 확인)

- 마지막 태그 이후 커밋을 본다: `git log --oneline $(git describe --tags --abbrev=0)..HEAD`
- 새 기능이나 공개 API 추가가 있으면 마이너(`0.2.0`), 수정만 있으면 패치(`0.1.2`)를 권한다. 개발 버전의 숫자를 그대로 따르지 않는다.
- 태그 push는 되돌릴 수 없으므로 버전과 진행 여부를 사용자에게 확인받는다. 확인받은 뒤에는 끝까지 진행한다.

## 2. 사전 점검

- 작업 트리가 깨끗하고 `main`이 `origin/main`과 같은지 확인한다(`git fetch` 후 `git status`, `git log main..origin/main`).
- 로컬 검증: `./gradlew build :sample:assembleDebug :sample:assembleRelease`
- 태그를 달 커밋의 CI(`build` 워크플로)가 성공했는지 확인한다: `gh run list --limit 3`, 진행 중이면 `gh run watch <id> --exit-status`.
- 공개될 버전 문자열이 맞는지 확인하려면: `./gradlew :boggart-core:compileKotlin -PVERSION_NAME=<버전>` 후 `boggart-core/build/generated/boggartVersion/.../BoggartVersion.kt`

## 3. 태그 push (공개 시작)

이전 태그와 같은 형식의 주석 태그를 쓴다(메시지는 태그 이름과 같다).

```bash
git tag -a v<버전> -m "v<버전>"
git push origin v<버전>
gh run list --workflow release.yml --limit 1
gh run watch <id> --exit-status
```

실패하면 원인을 사용자에게 보고하고 멈춘다. 같은 태그를 다시 쓰려면 원격 태그 삭제가 필요하므로 사용자 확인 없이 하지 않는다.

## 4. 공개 확인

Central 반영은 워크플로 성공 뒤 수십 분 걸릴 수 있다. 아래가 200이면 공개된 것이다.

```bash
curl -s -o /dev/null -w '%{http_code}\n' https://repo1.maven.org/maven2/io/github/hyunchan/boggart/boggart-core/<버전>/boggart-core-<버전>.pom
```

## 5. 배포 후 버전 올림 (커밋 두 개)

두 커밋은 서로 영향이 없어 순서는 상관없다.

1. README 의존성 버전: 두 README의 설치 예시(`boggart-android`·`boggart-okhttp`·`boggart-ktor`·`boggart-noop`)를 새 버전으로 바꾼다.
   - 커밋: `chore : README 의존성 버전을 <버전>으로 올린다`
2. 개발 버전: `gradle.properties`의 `VERSION_NAME`을 다음 패치 SNAPSHOT(`0.2.0` → `0.2.1-SNAPSHOT`)으로 바꾼다.
   - 커밋: `chore : 개발 버전을 <다음 버전>-SNAPSHOT으로 올린다`

본문은 bullet 목록, 끝에 대화의 attribution 줄을 붙인다. 두 커밋을 push한다.

## 6. GitHub 릴리스 노트

Maven Central에는 변경 내역을 실을 곳이 없으므로 태그마다 GitHub Release를 게시한다.

- 초안은 README 기본 언어(영국식 영어)로 쓰고, 문안 전문을 사용자에게 승인받은 뒤 게시한다.
- 담을 것: 새 기능(README 섹션 링크), **업그레이드하면 동작이 바뀌는 점**(기본으로 켜지는 기능과 끄는 방법), 사용자가 겪던 버그 수정, 의존성 한 줄. 같은 릴리스 안에서 개발 중에 고친 것(새 기능의 미공개 결함, 린트·빌드 정리)은 사용자가 겪을 일이 없으므로 뺀다.
- 링크는 절대 주소(`https://github.com/hyunchan/boggart#섹션`)로 쓴다. 릴리스 노트의 상대 링크는 릴리스 페이지 기준으로 해석돼 깨진다.

```bash
gh release create v<버전> --title "v<버전>" --notes-file <노트 파일> --verify-tag
gh release view v<버전> --json name,tagName,isDraft,url
```

## 7. 기록

`docs-local/`이 있으면 `DESIGN.md` 구현 이력에 배포 버전과 날짜를 남긴다. 사용자에게 공개 버전, 태그, 워크플로 결과, 올린 버전, 릴리스 노트 주소를 보고한다.
