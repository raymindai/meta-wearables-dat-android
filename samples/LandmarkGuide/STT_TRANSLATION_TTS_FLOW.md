# STT-번역-TTS 플로우 상세 분석

## 전체 플로우 개요

```
음성 입력 → STT (Soniox) → 언어 감지/필터링 → 번역 (Google/OpenAI) → Firebase 전송 → TTS 재생
```

---

## 1. STT (Speech-to-Text) - Soniox Streaming STT

### 1.1 오디오 캡처
**위치**: `MajlisScreen.kt` (라인 2203-2209)

```kotlin
val capture = BluetoothScoAudioCapture(context)
capture.setListener(object : BluetoothScoAudioCapture.AudioCaptureListener {
    override fun onAudioData(data: ByteArray, size: Int) {
        if (!isSpeaking || userState != "LISTENING") {
            return  // Mic disabled - ignore audio
        }
        // 오디오 데이터를 STT 서비스로 전송
        googleSTT.sendAudio(audioData)
    }
})
```

**특징**:
- Bluetooth SCO를 통한 오디오 캡처
- `isSpeaking` 상태가 활성화되어 있을 때만 처리
- PCM16 형식, 8kHz 샘플레이트, 모노 채널

### 1.2 Soniox STT 연결 및 설정
**위치**: `SonioxStreamingSpeechService.kt` (라인 102-149)

```kotlin
val start = JSONObject().apply {
    put("api_key", apiKey)
    put("model", "stt-rt-preview")
    put("audio_format", "s16le")
    put("sample_rate", 8000)
    put("num_channels", 1)
    put("enable_endpoint_detection", true)  // 문장 끝 감지 활성화
    put("enable_speaker_diarization", true)  // 화자 분리 활성화
    
    if (currentLanguage == "auto") {
        put("enable_language_identification", true)  // 자동 언어 감지
    } else {
        put("enable_language_identification", false)
        put("language_hints", JSONArray().apply { put(isoCode) })  // 언어 힌트 제공
    }
}
```

**주요 기능**:
- **Endpoint Detection**: `<end>` 토큰으로 문장 끝 감지 (정규식 패턴 불필요)
- **Speaker Diarization**: 화자 ID 추출 ("1", "2", etc.)
- **Language Hints**: 선택한 언어에 대한 인식 편향 제공

### 1.3 STT 결과 수신
**위치**: `SonioxStreamingSpeechService.kt` (라인 181-299)

```kotlin
private fun processTokens(json: JSONObject) {
    val tokens = json.optJSONArray("tokens") ?: return
    
    // 각 토큰 처리
    for (i in 0 until tokens.length()) {
        val token = tokens.optJSONObject(i)
        val text = token.optString("text", "")
        val isFinal = token.optBoolean("is_final", false)
        val speaker = token.optString("speaker", "")  // 화자 ID
        val lang = token.optString("language", "")  // 감지된 언어
        
        // <end> 토큰 감지
        if (text == "<end>") {
            hasEndToken = true
            hasFinal = true
            // 모든 토큰을 final로 재전송
        }
    }
    
    // 콜백 호출
    if (hasEndToken || hasFinal) {
        onTranscript?.invoke(fullText, true, currentSpeaker, detectedLang)
    } else {
        onTranscript?.invoke(fullText, false, currentSpeaker, detectedLang)  // Partial
    }
}
```

**출력**:
- `text`: 전사된 텍스트
- `isFinal`: 최종 결과 여부 (`<end>` 토큰 수신 시 `true`)
- `speaker`: 화자 ID (예: "1", "2")
- `detectedLang`: 감지된 언어 코드 (예: "ko", "en", "ar", "es")

---

## 2. 언어 필터링 및 처리

### 2.1 STT 콜백 처리
**위치**: `MajlisScreen.kt` (라인 1316-1363)

```kotlin
googleSTT.onTranscript = { text, isFinal, speaker, detectedLang ->
    // Soniox endpoint detection 사용 - isFinal이 true일 때만 처리
    if (isFinal && text != lastProcessedSentence && text.isNotBlank()) {
        lastProcessedSentence = text
        
        if (unifiedLanguage == "auto") {
            // Auto 모드: 감지된 언어 사용
            handleTranscriptWithDetectedLanguage(text, detectedLang, speaker)
        } else {
            // 특정 언어 선택: 선택한 언어와 일치하는지 확인
            val selectedLangCode = when (unifiedLanguage) {
                TranslationService.LANG_KOREAN -> "ko"
                TranslationService.LANG_ENGLISH -> "en"
                TranslationService.LANG_ARABIC -> "ar"
                TranslationService.LANG_SPANISH -> "es"
                else -> null
            }
            
            // 언어 일치 확인
            val actualDetectedLang = detectedLang ?: detectLanguageFromText(text)
            if (actualDetectedLang != null) {
                val detectedLangSimple = actualDetectedLang.split("-").first().lowercase()
                if (detectedLangSimple != selectedLangCode.lowercase()) {
                    // 언어 불일치 - 차단
                    return@launch
                }
            }
            
            handleTranscriptWithSpecificLanguage(text, unifiedLanguage, speaker, detectedLang)
        }
    }
}
```

**언어 필터링 로직**:
1. **Auto 모드**: 감지된 언어 사용 (감지 실패 시 차단)
2. **특정 언어 선택**: 
   - Soniox 언어 감지 결과 확인
   - 실패 시 텍스트 분석으로 언어 감지 (`detectLanguageFromText`)
   - 선택한 언어와 일치하지 않으면 차단

### 2.2 텍스트 기반 언어 감지 (Fallback)
**위치**: `MajlisScreen.kt` (라인 1152-1194)

```kotlin
fun detectLanguageFromText(text: String): String? {
    // Unicode 범위 분석
    var koreanCount = 0  // 한글 문자 (AC00-D7AF)
    var arabicCount = 0  // 아랍 문자 (0600-06FF)
    var latinCount = 0   // 라틴 문자
    var spanishCharCount = 0  // 스페인 특수 문자 (ñ, á, é, etc.)
    
    // 문자 분석
    for (char in text) {
        when {
            char.code in 0xAC00..0xD7AF -> koreanCount++
            char.code in 0x0600..0x06FF -> arabicCount++
            // ... 스페인 특수 문자 감지
        }
    }
    
    // 30% 이상이 특정 스크립트면 해당 언어로 판단
    return when {
        koreanCount * 100 / totalChars > 30 -> "ko"
        arabicCount * 100 / totalChars > 30 -> "ar"
        spanishCharCount > 0 && latinCount * 100 / totalChars > 30 -> "es"
        latinCount * 100 / totalChars > 30 -> "en"
        else -> null
    }
}
```

---

## 3. 번역 (Translation)

### 3.1 processTranscript 함수
**위치**: `MajlisScreen.kt` (라인 1039-1149)

```kotlin
fun processTranscript(text: String, languageCode: String, speaker: String? = null) {
    scope.launch {
        val fullLanguage = when (languageCode) {
            "ko" -> TranslationService.LANG_KOREAN
            "en" -> TranslationService.LANG_ENGLISH
            "ar" -> TranslationService.LANG_ARABIC
            "es" -> TranslationService.LANG_SPANISH
            else -> TranslationService.LANG_ENGLISH
        }
        
        val speakerName = when {
            speaker == null -> "나"
            speaker == "1" -> "나"
            else -> "Speaker $speaker"
        }
        
        val currentListeningLang = if (unifiedLanguage == "auto") {
            fullLanguage  // Auto 모드: 감지된 언어 사용
        } else {
            unifiedLanguage  // 선택한 언어 사용
        }
        
        if (currentListeningLang == fullLanguage) {
            // 같은 언어 - 번역 불필요
            // 채팅 히스토리에 추가
            // Firebase로 전송 (내 메시지인 경우)
        } else {
            // 다른 언어 - 번역 필요
            val result = googleTranslation.translate(
                text = text,
                targetLanguage = currentListeningLang,
                sourceLanguage = fullLanguage
            )
            
            if (result != null) {
                // 채팅 히스토리에 추가
                // Firebase로 전송 (내 메시지인 경우)
            }
        }
    }
}
```

### 3.2 번역 서비스
**위치**: `GoogleTranslationService.kt`

```kotlin
suspend fun translate(
    text: String,
    targetLanguage: String,
    sourceLanguage: String
): TranslationResult? {
    // Google Translation API 호출
    val response = client.newCall(request).execute()
    val translatedText = json.getString("translatedText")
    
    return TranslationResult(
        originalText = text,
        translatedText = translatedText,
        sourceLang = sourceLanguage,
        targetLang = targetLanguage
    )
}
```

**번역 조건**:
- `currentListeningLang != fullLanguage`일 때만 번역
- 내 메시지는 항상 번역 (내 언어 ≠ 듣는 언어인 경우)
- 상대방 메시지는 상대방 언어 ≠ 내 듣는 언어일 때만 번역

---

## 4. Firebase 전송

### 4.1 메시지 전송
**위치**: `MajlisScreen.kt` (라인 1102-1115)

```kotlin
// 내 메시지인 경우에만 Firebase로 전송
if (speakerName == "나") {
    firebaseService.sendMessage(text, fullLanguage)
    
    // 전송 완료 상태 업데이트
    chatHistory = chatHistory.map { msg ->
        if (msg.messageId == latestMessage.messageId) {
            msg.copy(isSent = true)
        } else {
            msg
        }
    }
}
```

**전송 내용**:
- `originalText`: 원본 텍스트만 전송
- `senderLanguage`: 발신자 언어
- 번역된 텍스트는 전송하지 않음 (각 수신자가 클라이언트에서 번역)

---

## 5. TTS (Text-to-Speech)

### 5.1 내 메시지 TTS
**위치**: `MajlisScreen.kt` (라인 1117-1120)

```kotlin
// My TTS disabled (always)
userState = "LISTENING"
currentOriginal = ""
currentTranslation = ""
```

**특징**:
- 내 메시지의 TTS는 항상 비활성화
- 내 목소리와 번역된 목소리를 듣지 않음

### 5.2 상대방 메시지 TTS
**위치**: `MajlisScreen.kt` (라인 722-748, 919-930)

#### 같은 언어인 경우:
```kotlin
val ttsSuccess = openAI.speak(
    latestMessage.originalText, 
    myListeningLanguage, 
    useBluetooth = true, 
    voice = detectedVoice
)

if (ttsSuccess) {
    // TTS 재생 완료 상태 업데이트
    chatHistory = chatHistory.map { msg ->
        if (msg.messageId == latestMessage.messageId) {
            msg.copy(isTTSPlayed = true)
        } else {
            msg
        }
    }
    // Firebase에 TTS 재생 완료 상태 저장
    firebaseService.markTTSPlayed(latestMessage.messageId)
}
```

#### 다른 언어인 경우 (번역 필요):
```kotlin
// OpenAI Realtime TTS 사용 (스트리밍 번역 + TTS)
openAIRealtimeTTS.translateAndSpeak(latestMessage.originalText)

// 또는 일반 번역 + TTS
val translationResult = openAI.translate(...)
val ttsSuccess = openAI.speak(
    translatedText, 
    myListeningLanguage, 
    useBluetooth = true, 
    voice = detectedVoice
)
```

### 5.3 TTS 큐 시스템
**위치**: `OpenAITranslationService.kt`, `GoogleTTSService.kt`

```kotlin
// 오디오 큐에 추가 (끊김 없는 재생)
private suspend fun queueAudio(
    pcmData: ByteArray,
    sampleRate: Int,
    useBluetooth: Boolean
) {
    queueMutex.withLock {
        audioQueue.add(AudioQueueItem(pcmData, sampleRate, useBluetooth))
    }
    
    // 재생 루프 시작 (아직 실행 중이 아니면)
    if (!isPlaying) {
        startPlaybackLoop()
    }
}

// 순차 재생 루프
private fun startPlaybackLoop() {
    playbackJob = playbackScope.launch {
        while (true) {
            val item = queueMutex.withLock {
                if (audioQueue.isEmpty()) null else audioQueue.removeAt(0)
            }
            
            if (item == null) {
                // 큐가 비어있으면 종료
                break
            }
            
            // 오디오 재생
            val audioTrack = AudioTrack.Builder()...
            audioTrack.write(item.audioData, 0, item.audioData.size)
            audioTrack.play()
            
            // 재생 완료 대기
            Thread.sleep(durationMs + 50)  // 끊김 방지를 위한 작은 버퍼
            
            audioTrack.stop()
            audioTrack.release()
        }
    }
}
```

**특징**:
- 큐 기반 순차 재생으로 오디오 끊김 방지
- 여러 메시지가 연속으로 와도 끊김 없이 재생
- Bluetooth SCO는 한 번만 초기화하고 유지

---

## 6. 상대방 메시지 수신 및 처리

### 6.1 Firebase 메시지 수신
**위치**: `MajlisScreen.kt` (라인 629-909)

```kotlin
LaunchedEffect(firebaseMessages.size) {
    val latestMessage = firebaseMessages.last()
    
    // 내 메시지인 경우: TTS 재생 완료 상태 업데이트만
    if (latestMessage.senderId == firebaseService.myUserId) {
        val ttsPlayedByOthers = latestMessage.ttsPlayedBy.filter { 
            it != firebaseService.myUserId 
        }.toSet()
        
        if (ttsPlayedByOthers.isNotEmpty()) {
            chatHistory = chatHistory.map { msg ->
                if (msg.messageId == latestMessage.messageId) {
                    msg.copy(ttsPlayedByOthers = ttsPlayedByOthers)
                } else {
                    msg
                }
            }
        }
        return@LaunchedEffect
    }
    
    // 상대방 메시지 처리
    val needsTranslation = latestMessage.senderLanguage != myListeningLanguage
    
    if (!needsTranslation) {
        // 같은 언어 - 번역 불필요
        // TTS 재생
        openAI.speak(latestMessage.originalText, myListeningLanguage, ...)
    } else {
        // 다른 언어 - 번역 필요
        // OpenAI Realtime TTS 또는 일반 번역 + TTS
    }
}
```

---

## 전체 플로우 요약

### 내가 말하는 경우:
```
1. 음성 입력 (Bluetooth SCO)
   ↓
2. Soniox STT (WebSocket)
   - 언어 감지/필터링
   - 화자 분리
   - Endpoint detection (<end> 토큰)
   ↓
3. 언어 확인
   - Auto 모드: 감지된 언어 사용
   - 특정 언어: 선택한 언어와 일치 확인
   ↓
4. 번역 (필요한 경우)
   - 내 언어 ≠ 듣는 언어 → Google Translation API
   ↓
5. Firebase 전송
   - 원본 텍스트만 전송
   - isSent = true 업데이트
   ↓
6. 채팅 히스토리 추가
   - 원본 + 번역 텍스트 표시
   ↓
7. TTS 재생
   - 내 메시지: 항상 비활성화 ❌
```

### 상대방이 말하는 경우:
```
1. Firebase 메시지 수신
   ↓
2. 언어 확인
   - 상대방 언어 == 내 듣는 언어 → 번역 불필요
   - 상대방 언어 ≠ 내 듣는 언어 → 번역 필요
   ↓
3. 번역 (필요한 경우)
   - OpenAI Realtime TTS (스트리밍)
   - 또는 OpenAI/Google Translation API
   ↓
4. TTS 재생
   - 오디오 큐에 추가
   - 순차 재생 (끊김 없음)
   ↓
5. TTS 재생 완료
   - isTTSPlayed = true 업데이트
   - Firebase에 상태 저장 (markTTSPlayed)
   ↓
6. 발신자에게 상태 전달
   - ttsPlayedByOthers 업데이트
   - UI에 🔊✓(N) 표시
```

---

## 주요 특징

### 1. Soniox Endpoint Detection
- 정규식 패턴 대신 `<end>` 토큰 사용
- 더 정확하고 빠른 문장 끝 감지
- 억양, 휴지, 대화 맥락 고려

### 2. 언어 필터링
- Auto 모드: 자동 언어 감지
- 특정 언어 선택: 선택한 언어만 허용
- 텍스트 분석 Fallback: Soniox 감지 실패 시 Unicode 범위 분석

### 3. 오디오 큐 시스템
- 순차 재생으로 끊김 방지
- 여러 메시지 연속 재생 가능
- Bluetooth SCO 한 번만 초기화

### 4. 상태 추적
- `isSent`: Firebase 전송 완료
- `isTTSPlayed`: TTS 재생 완료
- `ttsPlayedByOthers`: 상대방의 TTS 재생 완료 상태

---

## 파일 위치

- **STT**: `SonioxStreamingSpeechService.kt`
- **번역**: `GoogleTranslationService.kt`, `OpenAITranslationService.kt`
- **TTS**: `OpenAITranslationService.kt`, `GoogleTTSService.kt`
- **메인 플로우**: `MajlisScreen.kt` (라인 1039-1149, 1316-1363)
- **Firebase**: `FirebaseRoomService.kt`
