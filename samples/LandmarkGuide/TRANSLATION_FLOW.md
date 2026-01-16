# 번역 플로우 상세 설명

## 🔄 전체 플로우 (서버 측 번역)

### 사용자 A (발화자) 측

```
1. 🎤 사용자 A가 말함
   ↓
2. 🔊 STT (Speech-to-Text)
   - Deepgram / Google STT / OpenAI Realtime
   - 한국어로 말함 → "안녕하세요"
   ↓
3. 📤 Firebase에 메시지 전송
   - FirebaseRoomService.sendMessage()
   - 전송 데이터:
     {
       "senderId": "A의 ID",
       "senderName": "A의 이름",
       "senderLanguage": "ko",
       "originalText": "안녕하세요",
       "timestamp": 1234567890
     }
   - 경로: /rooms/{roomId}/messages/{messageId}
   ↓
4. 🔊 A의 TTS 재생 (병렬 처리)
   - 자신의 말을 자신의 언어로 재생 (선택사항)
   - 번역과 독립적으로 실행
```

### 🔥 Firebase Functions (서버 측)

```
5. ⚡ Firebase Functions 트리거
   - onMessageCreated 함수 자동 실행
   - 트리거: /rooms/{roomId}/messages/{messageId} onCreate
   ↓
6. 📋 방의 모든 사용자 언어 수집
   - /rooms/{roomId}/users 경로에서 모든 사용자 조회
   - 예: [ko, en, ar, es]
   ↓
7. 🔄 병렬 번역 실행
   - OpenAI GPT-3.5-turbo 사용
   - 각 언어로 동시에 번역:
     * ko → en: "Hello"
     * ko → ar: "مرحبا"
     * ko → es: "Hola"
   - 평균 소요 시간: 200-500ms
   ↓
8. ✅ translatedTexts 필드 업데이트
   - 메시지에 추가:
     {
       "translatedTexts": {
         "ko": "안녕하세요",
         "en": "Hello",
         "ar": "مرحبا",
         "es": "Hola"
       }
     }
   - Firebase Realtime Database에 자동 저장
```

### 사용자 B (수신자) 측

```
9. 📨 Firebase 메시지 수신 (첫 번째)
   - onChildAdded 이벤트
   - originalText만 있는 상태
   - 코드: MajlisScreen.kt의 LaunchedEffect
   ↓
10. ⏳ 번역 대기 또는 즉시 처리
    - translatedTexts가 이미 있으면 → 즉시 사용
    - translatedTexts가 없으면 → 원본 텍스트로 표시 (임시)
    ↓
11. 📨 Firebase 메시지 업데이트 수신 (두 번째)
    - onChildChanged 이벤트
    - translatedTexts 필드가 추가된 상태
    - 코드: FirebaseRoomService.kt의 onChildChanged
    ↓
12. ✅ 번역된 텍스트 추출
    - myListeningLanguage = "en" (B의 설정)
    - translatedTexts["en"] = "Hello" 사용
    ↓
13. 📝 Chat History 업데이트
    - 원본: "안녕하세요"
    - 번역: "Hello"
    - 화면에 즉시 표시
    ↓
14. 🔊 TTS 재생
    - OpenAI TTS 또는 Google TTS
    - "Hello"를 영어로 재생
    - Bluetooth 스피커로 출력
```

## 📊 타임라인

```
시간 →
A: 🎤 말함
   ↓ (1-2초)
A: 📤 Firebase 전송 (originalText만)
   ↓ (즉시)
🔥 Functions: 번역 시작 (200-500ms)
   ↓
🔥 Functions: translatedTexts 업데이트
   ↓ (즉시)
B: 📨 메시지 수신 (originalText)
   ↓ (200-500ms)
B: 📨 업데이트 수신 (translatedTexts)
   ↓ (즉시)
B: 🔊 TTS 재생
```

## 🔑 핵심 포인트

### 서버 측 번역의 장점

1. **딜레이 제거**
   - 이전: B가 번역을 기다려야 함 (1-2초 추가 딜레이)
   - 현재: 서버에서 미리 번역되어 전달 (200-500ms)

2. **병렬 처리**
   - 모든 언어로 동시에 번역
   - 여러 사용자가 있어도 한 번만 번역

3. **클라이언트 부하 감소**
   - B의 디바이스에서 번역 작업 불필요
   - TTS만 재생하면 됨

### 코드 위치

- **A의 발화 처리**: `MajlisScreen.kt` → `handleTranscript()`
- **Firebase 전송**: `FirebaseRoomService.kt` → `sendMessage()`
- **서버 번역**: `firebase-functions/index.js` → `onMessageCreated()`
- **B의 수신 처리**: `MajlisScreen.kt` → `LaunchedEffect(firebaseMessages.size)`
- **메시지 업데이트 감지**: `FirebaseRoomService.kt` → `onChildChanged()`

## 🐛 문제 해결

### 번역이 안 나오면

1. **Functions 로그 확인**
   - Google Cloud Console → Functions → onMessageCreated → Logs
   - "🔄 Processing new message" 메시지 확인
   - "✅ Translations complete" 메시지 확인

2. **Firebase Database 확인**
   - `/rooms/{roomId}/messages/{messageId}` 경로 확인
   - `translatedTexts` 필드가 있는지 확인

3. **환경 변수 확인**
   - Google Cloud Console → Functions → Variables
   - `OPENAI_API_KEY`가 설정되어 있는지 확인

### 딜레이가 여전히 있으면

1. **Functions 실행 시간 확인**
   - 로그에서 번역 시간 확인
   - 200-500ms가 정상

2. **네트워크 확인**
   - Firebase 연결 상태 확인
   - Realtime Database 연결 확인

## 📈 성능 개선

- **현재**: 서버에서 병렬 번역 (200-500ms)
- **이전**: 클라이언트에서 순차 번역 (1-2초)
- **개선**: 약 70-80% 딜레이 감소
