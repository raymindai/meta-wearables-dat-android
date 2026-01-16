# Firebase Cloud Functions - Server-Side Translation

이 폴더는 Majlis 앱의 서버 측 번역을 처리하는 Firebase Cloud Functions를 포함합니다.

## 개요

**이전 구조 (클라이언트 측 번역):**
- A 발화 → STT → Firebase 전송 → B 수신 → 번역 → TTS
- B에서 번역 딜레이 발생

**현재 구조 (서버 측 번역):**
- A 발화 → STT → Firebase 전송
- **Firebase Functions**: 방의 모든 사용자 언어로 즉시 번역
- B 수신 → 이미 번역된 텍스트 → TTS만 재생
- **딜레이 제거!**

## 설정

### 1. Firebase 프로젝트 설정

```bash
# Firebase CLI 설치 (아직 안 했다면)
npm install -g firebase-tools

# Firebase 로그인
firebase login

# 프로젝트 초기화 (이미 되어있다면 스킵)
firebase init functions
```

### 2. OpenAI API 키 설정

```bash
# Firebase Functions에 OpenAI API 키 설정
firebase functions:config:set openai.key="YOUR_OPENAI_API_KEY"

# 또는 환경 변수로 설정 (권장)
# .env 파일 생성 또는 Firebase Console에서 설정
```

### 3. 배포

```bash
cd firebase-functions
npm install
firebase deploy --only functions
```

## 함수 설명

### `onMessageCreated`

**트리거:** `/rooms/{roomId}/messages/{messageId}`에 새 메시지가 추가될 때

**동작:**
1. 방의 모든 사용자 언어 수집
2. OpenAI GPT-3.5-turbo로 각 언어로 병렬 번역
3. `translatedTexts` 필드에 번역 결과 저장
   ```json
   {
     "ko": "번역된 텍스트",
     "en": "Translated text",
     "ar": "النص المترجم"
   }
   ```

**성능:**
- 병렬 번역으로 빠른 처리
- GPT-3.5-turbo 사용 (가장 빠른 모델)
- 평균 번역 시간: 200-500ms

## 비용

- Firebase Functions: 무료 티어 (월 200만 호출)
- OpenAI API: GPT-3.5-turbo는 매우 저렴 ($0.0015/1K tokens)

## 문제 해결

### 번역이 안 되는 경우

1. OpenAI API 키 확인:
   ```bash
   firebase functions:config:get
   ```

2. Functions 로그 확인:
   ```bash
   firebase functions:log
   ```

3. Firebase Console에서 Functions 실행 상태 확인

### 번역이 느린 경우

- GPT-4 대신 GPT-3.5-turbo 사용 (현재 설정됨)
- `max_tokens`를 100으로 제한 (현재 설정됨)
- `temperature`를 0.1로 설정 (현재 설정됨)

## 로컬 테스트

```bash
# Functions 에뮬레이터 실행
firebase emulators:start --only functions

# 로컬에서 테스트
# Firebase Console에서 수동으로 메시지 추가하여 테스트
```
