# GSC-STT-TEST
Google Cloud STT v2 기반 회의 청크 업로드/대본/자막 동기화 테스트 프로젝트입니다.

## 핵심 기능
- `webm-opus` 청크 다중 업로드
- 업로드 종료 후 STT 비동기 완료 폴링
- 화자 포함 대본 생성
- 단어 타임오프셋 기반 자막(cue) 생성
- 청크 음원 서버 병합(ffmpeg) 후 단일 재생

## 기술 스택
- Java 21
- Spring Boot
- JPA + H2 (in-memory)
- Google Cloud Speech-to-Text v2
- Google Cloud Storage
- ffmpeg

## 환경 변수
루트의 `.env` 파일을 사용합니다. (`.env`는 Git에 올라가지 않습니다)

필수 값:
```env
GOOGLE_STT_API_KEY_PATH=/absolute/path/to/stt-service-account.json
OPENAI_API_KEY=
```

샘플 파일:
- `.env.example`

## 실행
```bash
./gradlew bootRun
```

브라우저:
- `http://localhost:8080`

## 기본 사용 흐름
1. 음성 파일(`.webm`) 여러 개 업로드
2. `종료` 버튼으로 폴링 시작
3. 완료 후 병합 오디오 재생 + 대본 하이라이트 동기화 확인

## 주요 API
- `POST /api/stt/chunks/batch`
  - 신규 미팅 자동 생성 + 다중 청크 업로드
- `POST /api/stt/meetings/{meetingId}/chunks/batch`
  - 기존 미팅에 청크 추가 업로드
- `GET /api/stt/meetings/{meetingId}/snapshot?poll=true`
  - 상태/대본/자막 통합 조회
- `GET /api/stt/meetings/{meetingId}/audio/merged`
  - 병합 오디오 다운로드/재생

## 개발 메모
- 입력 포맷은 `webm-opus` 고정 전제로 동작합니다.
- 청크 오프셋은 `durationMs`(파일 길이, 무음 포함) 기준으로 누적합니다.
