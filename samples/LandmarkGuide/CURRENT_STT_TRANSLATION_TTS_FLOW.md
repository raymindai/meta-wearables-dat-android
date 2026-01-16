# 현재 STT-번역-TTS 플로우 및 스택

## 📊 전체 플로우 다이어그램

```
┌─────────────────────────────────────────────────────────────────┐
│                    사용자 A (말하는 쪽)                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  🎤 음성 입력 (Bluetooth SCO)                                  │
│    ↓                                                            │
│  📝 Google STT (기본)                                          │
│    - 언어 자동 감지 통합 (별도 API 호출 불필요!)                │
│    - languageCode: "auto"                                      │
│    - alternativeLanguageCodes: [ar-SA, ko-KR, en-US, es-ES]   │
│    ↓                                                            │
│  🔍 언어 필터링 (Google STT가 감지한 언어 사용)                 │
│    - detectedLanguageBySTT 사용                                │
│    - 내 언어가 아니면 차단                                      │
│    ↓                                                            │
│  📤 Firebase Realtime Database                                 │
│    - originalText 전송                                          │
│    - senderLanguage 전송                                        │
│    ↓                                                            │
│  💬 채팅 히스토리에 즉시 추가 (UI 업데이트)                    │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
                              │
                              │ Firebase Realtime Database
                              │ (originalText만 전송)
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│                    사용자 B (듣는 쪽)                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  📨 Firebase 메시지 수신                                       │
│    - originalText (원본 텍스트)                                │
│    - senderLanguage (발신자 언어)                               │
│    ↓                                                            │
│  🔍 언어 확인                                                   │
│    - senderLanguage == myListeningLanguage?                    │
│    ↓                                                            │
│  ┌─────────────────────────────────────────┐                │
│  │ 같은 언어인 경우                          │                │
│  │   - 번역 없이 originalText 그대로 표시    │                │
│  │   - OpenAI TTS API로 재생                 │                │
│  └─────────────────────────────────────────┘                │
│    │                                                          │
│    ┌─────────────────────────────────────────┐              │
│    │ 다른 언어인 경우                        │                │
│    │   - OpenAI Realtime TTS                 │                │
│    │     (번역 + TTS 스트리밍)                 │                │
│    │   - 실시간 UI 업데이트                   │                │
│    └─────────────────────────────────────────┘              │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

## 🎤 STT (Speech-to-Text) - 말하는 쪽

### ✅ **Google Cloud Speech-to-Text** (기본, 활성)
- **상태**: `useGoogleSTT = true` (기본값)
- **언어 감지**: 통합 (별도 API 호출 불필요)
- **설정**:
  ```kotlin
  googleSTT.startListening("auto")  // 자동 언어 감지
  ```
- **언어 코드**: 
  - `languageCode: "auto"` (자동 감지)
  - `alternativeLanguageCodes: [ar-SA, ko-KR, en-US, es-ES]`
- **콜백**:
  - `onLanguageDetected`: 언어 감지 결과 (별도 API 호출 불필요!)
  - `onTranscript`: 텍스트 + 언어 정보
- **파일**: `GoogleSpeechService.kt`
- **특징**:
  - ✅ STT + 언어 감지 통합 (단일 API 호출)
  - ✅ 오디오 기반 언어 감지 (텍스트 기반보다 정확)
  - ✅ VAD (Voice Activity Detection) 내장
  - ✅ 실시간 스트리밍

### ⚠️ **OpenAI Realtime API** (옵션, 비활성)
- **상태**: `useRealtime = false` (옵션)
- **용도**: 실시간 스트리밍이 매우 중요한 경우
- **특징**: STT + 번역 + TTS 통합 (하지만 언어 감지는 별도 API 호출 필요)

## 🌐 번역 - 듣는 쪽

### ✅ **OpenAI Realtime TTS API** (기본, 활성)
- **용도**: 듣는 쪽 번역 (상대방 메시지)
- **상태**: `openAIRealtimeTTS.translateAndSpeak()` 사용
- **특징**:
  - 실시간 스트리밍 번역
  - 번역과 TTS 통합
  - WebSocket 기반
- **파일**: `OpenAIRealtimeTTSService.kt`
- **사용 위치**: `MajlisScreen.kt` - 듣는 쪽 번역

### ✅ **Google Cloud Translation API** (활성)
- **용도**: 언어 감지 (말하는 쪽 필터링)
- **상태**: Google STT의 `onLanguageDetected` 사용 (별도 API 호출 불필요!)
- **특징**: 
  - Google STT가 이미 언어를 감지하므로 별도 호출 불필요
  - 오디오 기반 언어 감지 (더 정확)
- **파일**: `TranslationService.kt` (사용 안 함 - Google STT가 대체)

### ⚠️ **Fallback 번역** (비활성, 백업용)
- **OpenAI GPT-3.5-turbo**: `openAIRealtimeTTS` 연결 실패 시에만 사용
- **Google Translation**: `handleTranscript`에서 다른 언어일 때 사용 (하지만 `useGoogleSTT=true`면 거의 사용 안 함)

## 🔊 TTS (Text-to-Speech) - 듣는 쪽

### ✅ **OpenAI Realtime TTS API** (기본, 활성)
- **용도**: 듣는 쪽 TTS (상대방 메시지)
- **상태**: `openAIRealtimeTTS.translateAndSpeak()` 내부 TTS 사용
- **특징**: 
  - 스트리밍 오디오 재생
  - 번역과 동시에 재생
  - WebSocket 기반
- **파일**: `OpenAIRealtimeTTSService.kt`
- **사용 위치**: `MajlisScreen.kt` - 듣는 쪽 TTS

### ✅ **OpenAI TTS API** (활성)
- **용도**: 같은 언어인 경우 TTS
- **상태**: `openAI.speak()` 사용
- **특징**: 
  - 자연스러운 음성
  - 다양한 음성 옵션
- **파일**: `OpenAITranslationService.kt`

### ⚠️ **Fallback TTS** (비활성, 백업용)
- **OpenAI TTS API**: `openAIRealtimeTTS` 연결 실패 시에만 사용

## 🔄 실제 사용 중인 플로우

### 말하는 쪽 (Sender) - 실제 플로우
```
1. 🎤 Bluetooth SCO Audio Capture
   ↓
2. 📝 Google Cloud Speech-to-Text
   - languageCode: "auto" (자동 언어 감지)
   - alternativeLanguageCodes: [ar-SA, ko-KR, en-US, es-ES]
   - STT + 언어 감지 통합 (단일 API 호출)
   ↓
3. 🔍 언어 필터링
   - Google STT의 onLanguageDetected 콜백 사용
   - detectedLanguageBySTT 변수에 저장
   - 내 언어가 아니면 차단 (별도 API 호출 불필요!)
   ↓
4. 📤 Firebase Realtime Database
   - originalText 전송
   - senderLanguage 전송
   ↓
5. 💬 채팅 히스토리에 즉시 추가 (UI 업데이트)
```

### 듣는 쪽 (Receiver) - 실제 플로우
```
1. 📨 Firebase Realtime Database
   - 원본 텍스트 수신
   ↓
2. 🔍 언어 확인
   - senderLanguage == myListeningLanguage?
   ↓
3. ┌─────────────────────────────────────┐
   │ 같은 언어인 경우                        │
   │   - 번역 없이 originalText 그대로 표시 │
   │   - OpenAI TTS API로 재생              │
   └─────────────────────────────────────┘
   │
   ┌─────────────────────────────────────┐
   │ 다른 언어인 경우                        │
   │   - OpenAI Realtime TTS              │
   │     (번역 + TTS 스트리밍)              │
   │   - 실시간 UI 업데이트                 │
   └─────────────────────────────────────┘
```

## 📦 실제 사용 중인 스택

### ✅ 활성 서비스

#### 말하는 쪽
1. **STT**: `GoogleSpeechService` (Google Cloud Speech-to-Text)
   - 언어 감지 통합
   - 단일 API 호출
   
2. **언어 필터링**: Google STT의 `onLanguageDetected` 콜백
   - 별도 API 호출 불필요
   - 오디오 기반 언어 감지

3. **실시간 통신**: `FirebaseRoomService` (Firebase Realtime Database)

#### 듣는 쪽
1. **번역 + TTS**: `OpenAIRealtimeTTSService` (OpenAI Realtime TTS API)
   - 번역과 TTS 통합
   - 스트리밍

2. **TTS (같은 언어)**: `OpenAITranslationService` (OpenAI TTS API)
   - 같은 언어인 경우 사용

3. **실시간 통신**: `FirebaseRoomService` (Firebase Realtime Database)

### ⚠️ 비활성 서비스 (코드에만 존재, 사용 안 함)
- Deepgram STT
- Vosk STT
- OpenAI Whisper STT
- OpenAI Realtime STT (옵션)
- Google Translation API (별도 호출 - Google STT가 대체)
- Google TTS

## 🚀 성능 최적화

### 개선 사항
1. **언어 감지 통합**: 
   - 이전: STT (1번) + 언어 감지 API (1번) = 2번 호출
   - 현재: Google STT (1번, 언어 감지 포함) = 1번 호출
   - **50% API 호출 감소**

2. **지연 시간 감소**:
   - 이전: STT + 언어 감지 API 응답 대기 (~200-300ms)
   - 현재: STT만 (언어 감지 포함)
   - **~200-300ms 지연 감소**

3. **비용 절감**:
   - Translation API 호출 제거
   - **비용 절감**

4. **정확도 향상**:
   - 이전: 텍스트 기반 언어 감지
   - 현재: 오디오 기반 언어 감지 (더 정확)

## 📝 코드 위치

### 주요 파일
- `MajlisScreen.kt`: 메인 UI 및 플로우 제어
- `GoogleSpeechService.kt`: Google STT + 언어 감지
- `OpenAIRealtimeTTSService.kt`: 번역 + TTS (듣는 쪽)
- `OpenAITranslationService.kt`: TTS (같은 언어)
- `FirebaseRoomService.kt`: 실시간 통신

### 주요 변수
```kotlin
// STT 선택
var useGoogleSTT by remember { mutableStateOf(true) }  // 기본
var useRealtime by remember { mutableStateOf(false) }   // 옵션

// 언어 감지 (Google STT가 제공)
var detectedLanguageBySTT by remember { mutableStateOf<String?>(null) }

// TTS 설정
// - 상대 음성: 항상 활성 (peerTtsEnabled 제거됨)
// - 내 음성: 항상 비활성 (myTtsEnabled 제거됨)
```

## 🎯 핵심 특징

1. **단일 API 호출**: Google STT가 STT + 언어 감지를 동시에 처리
2. **오디오 기반 언어 감지**: 텍스트 기반보다 정확
3. **실시간 스트리밍**: 번역과 TTS가 동시에 스트리밍
4. **엄격한 언어 필터링**: 내 언어가 아니면 차단
5. **즉시 UI 업데이트**: 채팅 히스토리에 즉시 추가
