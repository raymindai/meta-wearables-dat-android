# 전체 아키텍처 개요

## 전체 구조 다이어그램

```
┌─────────────────────────────────────────────────────────────────┐
│                        사용자 A (말하는 쪽)                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  🎤 음성 입력                                                    │
│    ↓                                                            │
│  [Bluetooth SCO Audio Capture]                                  │
│    ↓                                                            │
│  [STT 서비스]                                                   │
│    - Deepgram / Google STT / Vosk / OpenAI Whisper             │
│    - OpenAI Realtime (STT 포함)                                │
│    ↓                                                            │
│  📝 Transcript (원본 텍스트)                                     │
│    ↓                                                            │
│  [Firebase Realtime Database]                                  │
│    - originalText 전송                                          │
│    - senderLanguage 전송                                        │
│    - timestamp 전송                                             │
│    ↓                                                            │
│  🔄 번역 (선택적)                                                │
│    - 같은 언어면 번역 안 함                                      │
│    - 다른 언어면 번역 후 TTS                                     │
│    ↓                                                            │
│  🔊 TTS 재생 (자신의 번역된 음성)                                │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
                              │
                              │ Firebase Realtime Database
                              │ (originalText만 전송)
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│                        사용자 B (듣는 쪽)                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  📨 Firebase 메시지 수신                                         │
│    - originalText (원본 텍스트)                                  │
│    - senderLanguage (발신자 언어)                                │
│    - timestamp                                                  │
│    ↓                                                            │
│  🔍 언어 확인                                                    │
│    - senderLanguage == myListeningLanguage?                    │
│    ↓                                                            │
│  ┌─────────────────────────────────────────┐                  │
│  │ 같은 언어인 경우                          │                  │
│  │   - 번역 없이 originalText 그대로 표시    │                  │
│  │   - TTS 재생 (originalText)               │                  │
│  └─────────────────────────────────────────┘                  │
│                                                                 │
│  ┌─────────────────────────────────────────┐                  │
│  │ 다른 언어인 경우                          │                  │
│  │   ↓                                      │                  │
│  │  [OpenAI Realtime TTS]                   │                  │
│  │    - WebSocket 연결                      │                  │
│  │    - originalText 전송                   │                  │
│  │    ↓                                      │                  │
│  │  📝 스트리밍 번역                         │                  │
│  │    - response.audio_transcript.delta     │                  │
│  │    - UI에 실시간 업데이트                │                  │
│  │    ↓                                      │                  │
│  │  🔊 스트리밍 TTS                          │                  │
│  │    - response.audio.delta                │                  │
│  │    - Bluetooth로 즉시 재생               │                  │
│  │    ↓                                      │                  │
│  │  ✅ 번역 완료                             │                  │
│  │    - response.audio_transcript.done      │                  │
│  │    - isComplete = true                   │                  │
│  └─────────────────────────────────────────┘                  │
│                                                                 │
│  💬 UI 업데이트                                                  │
│    - chatHistory에 메시지 추가                                  │
│    - 원본 텍스트 표시                                           │
│    - 번역 텍스트 표시 (스트리밍 중)                              │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## 상세 플로우

### 1. 말하는 쪽 (Sender) - 사용자 A

#### 1.1 음성 입력
```kotlin
// MajlisScreen.kt
// Bluetooth SCO Audio Capture 시작
audioCapture = BluetoothScoAudioCapture(...)
audioCapture.startScoConnection()

// STT 서비스 시작
deepgramSTT.startListening()
// 또는
googleSTT.startListening()
// 또는
openAIRealtime.start(sourceLang, targetLang)
```

#### 1.2 STT (Speech-to-Text)
```kotlin
// STT 콜백
deepgramSTT.onTranscript = { text, isFinal ->
    if (isFinal || hasSentenceEnd) {
        handleTranscript(text)  // 문장 끝 감지 시 처리
    }
}
```

#### 1.3 Firebase 전송
```kotlin
// handleTranscript() 함수
fun handleTranscript(text: String) {
    // Firebase에 원본 텍스트만 전송
    firebaseService.sendMessage(
        originalText = text,
        speakerLanguage = mySpeakingLanguage
    )
    
    // 자신의 언어와 듣는 언어가 다르면 번역
    if (mySpeakingLanguage != myListeningLanguage) {
        val translation = translate(text)
        // TTS 재생
        speak(translation)
    }
}
```

#### 1.4 Firebase 전송 내용
```kotlin
// FirebaseRoomService.kt
fun sendMessage(originalText: String, speakerLanguage: String) {
    messageRef.setValue(mapOf(
        "senderId" to myUserId,
        "senderName" to myName,
        "senderLanguage" to speakerLanguage,
        "originalText" to originalText,  // 원본 텍스트만
        "timestamp" to ServerValue.TIMESTAMP
    ))
}
```

**중요**: 서버는 원본 텍스트만 전송합니다. 번역은 각 수신자가 클라이언트에서 처리합니다.

---

### 2. 듣는 쪽 (Receiver) - 사용자 B

#### 2.1 Firebase 메시지 수신
```kotlin
// MajlisScreen.kt - LaunchedEffect(firebaseMessages.size)
LaunchedEffect(firebaseMessages.size) {
    val latestMessage = firebaseMessages.last()
    
    // 메시지 처리
    if (latestMessage.senderLanguage == myListeningLanguage) {
        // 같은 언어 - 번역 불필요
        handleSameLanguageMessage(latestMessage)
    } else {
        // 다른 언어 - 번역 필요
        handleDifferentLanguageMessage(latestMessage)
    }
}
```

#### 2.2 같은 언어인 경우
```kotlin
// 번역 없이 원본 텍스트 그대로 사용
chatHistory = chatHistory + ChatMessage(
    speaker = latestMessage.senderName,
    original = latestMessage.originalText,
    translated = latestMessage.originalText,  // 번역 없음
    isComplete = true
)

// TTS 재생
openAI.speak(
    text = latestMessage.originalText,
    language = myListeningLanguage
)
```

#### 2.3 다른 언어인 경우 - OpenAI Realtime TTS 사용

##### 2.3.1 WebSocket 연결
```kotlin
// OpenAI Realtime TTS 연결
if (!openAIRealtimeTTS.isConnected()) {
    openAIRealtimeTTS.connect(
        sourceLang = latestMessage.senderLanguage,  // 발신자 언어
        targetLang = myListeningLanguage             // 내가 듣는 언어
    )
}
```

##### 2.3.2 번역 + TTS 요청
```kotlin
// 원본 텍스트를 Realtime TTS로 전송
openAIRealtimeTTS.translateAndSpeak(latestMessage.originalText)
```

##### 2.3.3 스트리밍 응답 처리
```kotlin
// OpenAIRealtimeTTSService.kt
// 1. 번역 텍스트 스트리밍
"response.audio_transcript.delta" -> {
    val delta = json.optString("delta", "")
    currentTranslationText += delta
    onTranslationDelta?.invoke(currentTranslationText)  // UI 업데이트
}

// 2. 오디오 스트리밍
"response.audio.delta" -> {
    val audioBase64 = json.optString("delta", "")
    val audioBytes = Base64.decode(audioBase64, Base64.DEFAULT)
    playAudioChunk(audioBytes)  // 즉시 재생
}

// 3. 번역 완료
"response.audio_transcript.done" -> {
    val transcript = json.optString("transcript", "")
    onTranslation?.invoke(transcript)  // 최종 번역 텍스트
}
```

##### 2.3.4 UI 업데이트
```kotlin
// 스트리밍 중 UI 업데이트
translationDeltaCallbacks[messageId] = { streamingText ->
    chatHistory = chatHistory.map { msg ->
        if (msg.messageId == messageId) {
            msg.copy(
                translated = streamingText,  // 스트리밍 텍스트
                isComplete = false           // 아직 완료 안 됨
            )
        } else msg
    }
}

// 완료 시 UI 업데이트
translationCallbacks[messageId] = { translatedText ->
    chatHistory = chatHistory.map { msg ->
        if (msg.messageId == messageId) {
            msg.copy(
                translated = translatedText,  // 최종 번역
                isComplete = true             // 완료
            )
        } else msg
    }
}
```

---

## 주요 컴포넌트

### 1. STT 서비스 (Speech-to-Text)
- **DeepgramWhisperService**: 영어, 한국어, 스페인어
- **GoogleSpeechService**: 아랍어 등
- **VoskSpeechService**: 오프라인 STT
- **OpenAIWhisperService**: OpenAI Whisper API
- **OpenAIRealtimeService**: 실시간 STT + 번역 + TTS 통합

### 2. 번역 서비스
- **OpenAITranslationService**: GPT-3.5-turbo 기반 번역
- **GoogleTranslationService**: Google Translate API
- **OpenAIRealtimeTTSService**: Realtime API 기반 번역 + TTS

### 3. TTS 서비스 (Text-to-Speech)
- **OpenAITranslationService.speak()**: OpenAI TTS API
- **GoogleTTSService**: Google TTS API
- **OpenAIRealtimeTTSService**: Realtime API 스트리밍 TTS

### 4. Firebase 서비스
- **FirebaseRoomService**: 
  - 방 입장/퇴장
  - 메시지 전송/수신
  - 사용자 상태 관리

---

## 데이터 흐름

### 말하는 쪽 → Firebase
```
원본 텍스트 (originalText)
  ↓
Firebase Realtime Database
  /rooms/{roomId}/messages/{messageId}
  {
    senderId: "user123",
    senderName: "사용자A",
    senderLanguage: "ko",
    originalText: "안녕하세요",
    timestamp: 1234567890
  }
```

### Firebase → 듣는 쪽
```
Firebase 메시지 수신
  ↓
originalText 추출
  ↓
언어 확인 (senderLanguage vs myListeningLanguage)
  ↓
같은 언어? → TTS 재생
다른 언어? → 번역 + TTS
```

---

## 핵심 설계 원칙

### 1. 클라이언트 측 번역
- **서버는 원본 텍스트만 전송**
- **각 수신자가 자신의 언어로 번역**
- 장점:
  - 서버 부하 감소
  - 확장성 (사용자 증가 시 서버 부담 없음)
  - 각 사용자가 독립적으로 번역 처리

### 2. 스트리밍 번역
- **OpenAI Realtime API 사용**
- 번역 텍스트와 TTS 오디오를 동시에 스트리밍
- 장점:
  - 낮은 지연시간
  - 실시간 피드백
  - 자연스러운 사용자 경험

### 3. 메시지 매칭
- **messageId 기반 매칭**
- 각 메시지에 고유 ID 부여
- 스트리밍 중에도 올바른 메시지에 업데이트

### 4. 상태 관리
- **isComplete 플래그**: 메시지 완료 여부 추적
- **스트리밍 중 표시**: "⏳" 아이콘으로 진행 중 표시
- **타임스탬프 정렬**: 메시지 순서 보장

---

## 시퀀스 다이어그램

### 같은 언어인 경우
```
사용자A                Firebase              사용자B
  │                      │                      │
  │--[음성 입력]-------->│                      │
  │                      │                      │
  │--[STT]--------------│                      │
  │                      │                      │
  │--[originalText]----->│                      │
  │                      │--[originalText]----->│
  │                      │                      │
  │                      │                      │--[TTS 재생]
  │                      │                      │
```

### 다른 언어인 경우
```
사용자A                Firebase              사용자B              OpenAI
  │                      │                      │                    │
  │--[음성 입력]-------->│                      │                    │
  │                      │                      │                    │
  │--[STT]--------------│                      │                    │
  │                      │                      │                    │
  │--[originalText]----->│                      │                    │
  │                      │--[originalText]----->│                    │
  │                      │                      │                    │
  │                      │                      │--[WebSocket 연결]->│
  │                      │                      │                    │
  │                      │                      │--[originalText]--->│
  │                      │                      │                    │
  │                      │                      │<--[delta 텍스트]---│
  │                      │                      │                    │
  │                      │                      │--[UI 업데이트]     │
  │                      │                      │                    │
  │                      │                      │<--[delta 오디오]---│
  │                      │                      │                    │
  │                      │                      │--[TTS 재생]        │
  │                      │                      │                    │
  │                      │                      │<--[완료]-----------│
  │                      │                      │                    │
```

---

## 파일 구조

```
app/src/main/java/com/meta/wearable/dat/externalsampleapps/landmarkguide/
│
├── ui/
│   └── MajlisScreen.kt          # 메인 UI 및 플로우 제어
│
├── firebase/
│   └── FirebaseRoomService.kt   # Firebase 통신
│
├── translation/
│   ├── StreamingSpeechService.kt      # Deepgram STT
│   ├── GoogleSpeechService.kt        # Google STT
│   ├── VoskSpeechService.kt           # 오프라인 STT
│   ├── OpenAIWhisperService.kt        # OpenAI Whisper STT
│   ├── OpenAIRealtimeService.kt       # Realtime STT+번역+TTS
│   ├── OpenAITranslationService.kt    # 번역 + TTS
│   ├── GoogleTranslationService.kt    # Google 번역
│   ├── GoogleTTSService.kt            # Google TTS
│   └── OpenAIRealtimeTTSService.kt    # Realtime 번역+TTS
│
└── audio/
    └── BluetoothScoAudioCapture.kt    # Bluetooth 오디오 캡처
```

---

## 요약

### 말하는 쪽
1. 음성 입력 → STT → 원본 텍스트
2. Firebase에 원본 텍스트만 전송
3. 자신의 언어와 다르면 번역 후 TTS 재생

### 듣는 쪽
1. Firebase에서 원본 텍스트 수신
2. 언어 확인 (같은 언어 vs 다른 언어)
3. 같은 언어: 원본 텍스트 그대로 TTS 재생
4. 다른 언어: OpenAI Realtime TTS로 번역 + TTS 스트리밍
5. UI에 실시간 업데이트 (스트리밍 중)

### 핵심 특징
- **서버는 원본만 전송**: 번역은 각 클라이언트에서 처리
- **스트리밍 번역**: OpenAI Realtime API로 실시간 번역 + TTS
- **메시지 ID 기반 매칭**: 올바른 메시지에 번역 텍스트 매칭
- **상태 관리**: 완료 여부 추적 및 UI 표시
