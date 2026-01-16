# 기술 스택 (Tech Stack)

> **⚠️ 중요**: 이 문서는 실제로 **현재 사용 중인** 서비스만 정리합니다.
> 코드에 존재하지만 사용되지 않는 서비스는 "비활성"으로 표시합니다.

## 📱 플랫폼
- **OS**: Android
- **Min SDK**: 31 (Android 12)
- **Target SDK**: 34 (Android 14)
- **Compile SDK**: 35
- **언어**: Kotlin 2.1.20
- **빌드 시스템**: Gradle 8.6.0

## 🎨 UI 프레임워크
- **Jetpack Compose**: 2024.04.01 BOM
- **Material 3**: 최신 Material Design
- **Compose Compiler**: Kotlin 2.1.20
- **Lifecycle**: ViewModel, StateFlow, LaunchedEffect

## 🎤 STT (Speech-to-Text) - 실제 사용 중

### ✅ **OpenAI Realtime API** (기본, 활성)
- **용도**: 말하는 쪽 STT
- **모델**: `gpt-4o-realtime-preview-2024-12-17`
- **상태**: `useRealtime = true` (기본값)
- **특징**: 
  - 실시간 STT + 번역 + TTS 통합
  - 서버 VAD (Voice Activity Detection)
  - WebSocket 스트리밍
- **파일**: `OpenAIRealtimeService.kt`
- **사용 위치**: `MajlisScreen.kt` - 말하는 쪽 STT

### ⚠️ **다른 STT 서비스들** (비활성, 백업용)
- **Deepgram**: 코드에 있지만 `useRealtime=true`일 때 사용 안 함
- **Google STT**: 코드에 있지만 `useRealtime=true`일 때 사용 안 함
- **Vosk**: `useVosk = false` (기본값) - 사용 안 함
- **OpenAI Whisper**: 코드에 있지만 사용 안 함

## 🌐 번역 서비스 - 실제 사용 중

### ✅ **OpenAI Realtime API** (기본, 활성)
- **용도**: 듣는 쪽 번역 (상대방 메시지)
- **상태**: `openAIRealtimeTTS.translateAndSpeak()` 사용
- **특징**: 
  - 실시간 스트리밍 번역
  - 번역과 TTS 통합
  - WebSocket 기반
- **파일**: `OpenAIRealtimeTTSService.kt`
- **사용 위치**: `MajlisScreen.kt` - 듣는 쪽 번역

### ✅ **Google Cloud Translation API** (활성)
- **용도**: 언어 감지 (언어 필터링)
- **상태**: `translationService.detectLanguage()` 사용
- **특징**: 
  - 언어 자동 감지
  - 엄격한 언어 필터링에 사용
- **파일**: `TranslationService.kt`
- **사용 위치**: `MajlisScreen.kt` - 언어 필터링

### ⚠️ **Fallback 번역** (비활성, 백업용)
- **OpenAI GPT-3.5-turbo**: `openAIRealtimeTTS` 연결 실패 시에만 사용
- **Google Translation**: `handleTranscript`에서 다른 언어일 때 사용 (하지만 `useRealtime=true`면 거의 사용 안 함)

## 🔊 TTS (Text-to-Speech) - 실제 사용 중

### ✅ **OpenAI Realtime API** (기본, 활성)
- **용도**: 듣는 쪽 TTS (상대방 메시지)
- **상태**: `openAIRealtimeTTS.translateAndSpeak()` 내부 TTS 사용
- **특징**: 
  - 스트리밍 오디오 재생
  - 번역과 동시에 재생
  - WebSocket 기반
- **파일**: `OpenAIRealtimeTTSService.kt`
- **사용 위치**: `MajlisScreen.kt` - 듣는 쪽 TTS

### ⚠️ **Fallback TTS** (비활성, 백업용)
- **OpenAI TTS API**: `openAIRealtimeTTS` 연결 실패 시에만 사용
- **Google TTS**: 코드에 있지만 사용 안 함

## 🔥 백엔드 & 실시간 통신

### 1. **Firebase Realtime Database**
- **용도**: 다중 사용자 실시간 메시지 동기화
- **특징**: 
  - 즉시 연결 (Nearby Connections 대비)
  - 실시간 동기화
  - 사용자 상태 관리
- **라이브러리**: `com.google.firebase:firebase-database-ktx`
- **파일**: `FirebaseRoomService.kt`

### 2. **Google Nearby Connections** (백업)
- **용도**: 오프라인 P2P 통신
- **특징**: 
  - 오프라인 지원
  - P2P 직접 연결
  - Firebase 대비 느림
- **라이브러리**: `com.google.android.gms:play-services-nearby:19.3.0`
- **파일**: `NearbyConnectionService.kt`

## 🎧 오디오 처리

### 1. **Bluetooth SCO Audio Capture**
- **용도**: 블루투스 헤드셋/이어폰에서 오디오 캡처
- **특징**: 
  - 실시간 오디오 스트리밍
  - SCO (Synchronous Connection-Oriented) 프로토콜
  - 핸즈프리 모드 지원
- **파일**: `BluetoothScoAudioCapture.kt`

### 2. **AudioTrack** (Android)
- **용도**: TTS 오디오 재생
- **특징**: 
  - 낮은 지연시간
  - 스트리밍 재생 지원
  - 블루투스 오디오 라우팅

## 🤖 AI & 머신러닝

### 1. **Google Gemini 2.5 Flash**
- **용도**: AI 비전 가이드 (Guide Mode)
- **특징**: 
  - 이미지 분석
  - 랜드마크 인식
  - 실시간 가이드
- **파일**: `VisionAnalyzer.kt`

### 2. **ONNX Runtime**
- **용도**: OpenWakeWord 모델 실행
- **라이브러리**: `com.microsoft.onnxruntime:onnxruntime-android:1.16.3`
- **파일**: `OpenWakeWordService.kt`

### 3. **Picovoice Porcupine**
- **용도**: 웨이크 워드 감지
- **라이브러리**: `ai.picovoice:porcupine-android:3.0.2`
- **파일**: `WakeWordService.kt`

## 📡 네트워크 & API

### 1. **OkHttp 4.12.0**
- **용도**: HTTP/WebSocket 클라이언트
- **특징**: 
  - WebSocket 지원
  - 스트리밍 지원
  - 타임아웃 설정

### 2. **Gson 2.10.1**
- **용도**: JSON 파싱
- **특징**: 빠른 파싱, 간단한 API

## 🗺️ 위치 & 지도

### 1. **Google Maps**
- **용도**: 지도 표시, 위치 서비스
- **라이브러리**: 
  - `com.google.maps.android:maps-compose:4.3.0`
  - `com.google.android.gms:play-services-maps:18.2.0`
  - `com.google.android.gms:play-services-location:21.0.1`

## 📱 메타 웨어러블 SDK

### 1. **MWDAT Core 0.3.0**
- **용도**: Meta Wearables Device Access Toolkit
- **특징**: 
  - 디바이스 발견
  - 세션 관리
  - 스트리밍 제어
- **라이브러리**: `com.meta.wearable:mwdat-core:0.3.0`

### 2. **MWDAT Camera**
- **용도**: 카메라 스트리밍
- **라이브러리**: `com.meta.wearable:mwdat-camera:0.3.0`

### 3. **MWDAT Mock Device**
- **용도**: 모의 디바이스 테스트
- **라이브러리**: `com.meta.wearable:mwdat-mockdevice:0.3.0`

## 🔐 인증 & API 키

### API 키 관리 (local.properties)
- `GEMINI_API_KEY`: Google Gemini API
- `GOOGLE_CLOUD_API_KEY`: Google Cloud (STT, Translation, TTS)
- `DEEPGRAM_API_KEY`: Deepgram STT
- `OPENAI_API_KEY`: OpenAI (GPT, Whisper, Realtime, TTS)
- `PICOVOICE_API_KEY`: Picovoice Wake Word
- `MAPS_API_KEY`: Google Maps

## 📦 실제 사용 중인 의존성만

```kotlin
// ✅ 활성 서비스 (실제 사용 중)

// STT (말하는 쪽)
- OpenAI Realtime API (gpt-4o-realtime-preview-2024-12-17)

// 언어 감지
- Google Cloud Translation API (언어 필터링)

// 번역 + TTS (듣는 쪽)
- OpenAI Realtime TTS API (통합)

// TTS (Fallback)
- OpenAI TTS API

// 백엔드
- Firebase Realtime Database

// 네트워크
- OkHttp 4.12.0 (WebSocket 클라이언트)
- Gson 2.10.1 (JSON 파싱)

// 오디오
- Android AudioTrack (TTS 재생)
- Bluetooth SCO Audio Capture (오디오 입력)

// ⚠️ 비활성 서비스 (코드에만 존재, 사용 안 함)
- Deepgram STT
- Google STT
- Vosk STT
- OpenAI Whisper STT
- Google Translation (Fallback만)
- Google TTS
- Google Nearby Connections (백업용)
```

## 🎯 핵심 스택 요약

### 실제 사용 중인 파이프라인

**말하는 쪽**:
```
Bluetooth SCO 
  → OpenAI Realtime STT 
  → Google Translation (언어 감지/필터링)
  → Firebase 전송
```

**듣는 쪽**:
```
Firebase 수신 
  → 언어 확인
  → OpenAI Realtime TTS (번역 + TTS 스트리밍)
  → 실시간 UI 업데이트
```

## 🏗️ 아키텍처 패턴

- **MVVM**: ViewModel + StateFlow
- **Compose State Management**: remember, LaunchedEffect
- **Coroutines**: 비동기 처리
- **Flow**: 반응형 데이터 스트리밍
- **Service Layer**: STT/TTS/Translation 서비스 분리

## 🔄 실제 사용 중인 스택 (Majlis 모드)

### 말하는 쪽 (Sender) - 실제 플로우
```
1. 🎤 Bluetooth SCO Audio Capture
   ↓
2. 📝 OpenAI Realtime API (STT)
   - 모델: gpt-4o-realtime-preview-2024-12-17
   - WebSocket 스트리밍
   ↓
3. 🔍 Google Cloud Translation API (언어 감지)
   - 언어 필터링 (엄격한 매칭)
   - 내 언어가 아니면 차단
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

### 실제 사용 중인 서비스만

#### ✅ 활성 서비스
1. **STT (말하는 쪽)**: `OpenAIRealtimeService`
2. **언어 감지**: `TranslationService` (Google Cloud Translation API)
3. **번역 (듣는 쪽)**: `OpenAIRealtimeTTSService`
4. **TTS (듣는 쪽)**: `OpenAIRealtimeTTSService` (통합)
5. **TTS (Fallback)**: `OpenAITranslationService.speak()` (OpenAI TTS API)
6. **실시간 통신**: Firebase Realtime Database
7. **오디오 캡처**: Bluetooth SCO Audio Capture

#### ⚠️ 비활성 서비스 (코드에만 존재, 사용 안 함)
- Deepgram STT
- Google STT
- Vosk STT
- OpenAI Whisper STT
- Google Translation (Fallback만)
- Google TTS

## 🚀 성능 최적화

- **스트리밍**: 실시간 오디오/텍스트 스트리밍
- **즉시 UI 업데이트**: 채팅 히스토리에 즉시 추가
- **언어 필터링**: 엄격한 언어 매칭으로 오탐 방지
- **Mic 토글 제어**: 상태 확인으로 불필요한 오디오 처리 방지
