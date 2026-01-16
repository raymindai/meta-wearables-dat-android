# 실시간성 개선 방안

## 현재 플로우의 병목 지점

### 1. 내 메시지 처리 (순차 처리)
```
STT 부분 전사 → (대기) → STT 완료 → 번역 시작 → 번역 완료 → Firebase 전송 → UI 업데이트
```

**문제점**:
- 부분 전사가 나와도 번역을 시작하지 않음
- 번역이 완료될 때까지 기다림
- Firebase 전송도 전체 문장 완료 후에만 수행

### 2. 상대방 메시지 처리 (연결 대기)
```
Firebase 수신 → OpenAI Realtime 연결 확인 → (연결 없으면 대기) → 번역 + TTS 시작
```

**문제점**:
- 연결이 없으면 500ms 대기 후 재시도
- 연결 대기 시간이 지연을 증가시킴

### 3. 번역 및 TTS (순차 처리)
```
번역 시작 → 번역 완료 → TTS 시작 → TTS 완료
```

**문제점**:
- 번역이 완료될 때까지 TTS를 시작하지 않음
- 스트리밍 번역을 활용하지 않음

---

## 개선 방안

### 1. 부분 전사 즉시 처리 (Streaming Translation)

**현재**:
```kotlin
googleSTT.onTranscript = { text, isFinal, speaker, detectedLang ->
    if (isFinal && text != lastProcessedSentence && text.isNotBlank()) {
        // 전체 문장 완료 후에만 처리
        processTranscript(text, ...)
    }
}
```

**개선**:
```kotlin
googleSTT.onTranscript = { text, isFinal, speaker, detectedLang ->
    if (isFinal) {
        // 최종 처리
        processTranscript(text, ...)
    } else {
        // 부분 전사도 즉시 처리 (스트리밍 번역)
        processPartialTranscript(text, speaker, detectedLang)
    }
}
```

**효과**:
- 부분 전사가 나오면 즉시 번역 시작
- 번역 텍스트가 스트리밍으로 UI에 표시
- 전체 문장 완료 전에 번역이 시작되어 지연 감소

### 2. 부분 전사 Firebase 전송 (Streaming Updates)

**현재**:
- 전체 문장 완료 후에만 Firebase 전송

**개선**:
```kotlin
// 부분 전사도 Firebase로 전송 (partialText 필드 활용)
if (speakerName == "나" && text.isNotBlank()) {
    firebaseService.sendPartialText(text, fullLanguage)
}
```

**효과**:
- 상대방이 내가 말하는 것을 실시간으로 볼 수 있음
- 전체 문장 완료 전에 대화 흐름 파악 가능

### 3. 스트리밍 번역 (Streaming Translation)

**현재**:
- Google Translation API는 전체 텍스트 필요
- 번역 완료까지 대기

**개선**:
- OpenAI Realtime API 활용 (이미 있음!)
- 부분 전사가 나오면 즉시 Realtime API로 전송
- 번역 텍스트가 스트리밍으로 도착

**효과**:
- 번역 지연 시간 감소 (~300ms → ~100ms)
- 부분 번역 텍스트를 즉시 UI에 표시

### 4. 스트리밍 TTS (Streaming TTS)

**현재**:
- 번역 완료 후 TTS 시작
- 전체 오디오 생성 후 재생

**개선**:
- OpenAI Realtime TTS 활용 (이미 있음!)
- 번역이 완료되기 전에 부분 번역으로 TTS 시작
- 오디오가 스트리밍으로 재생

**효과**:
- TTS 시작 시간 감소
- 더 자연스러운 대화 흐름

### 5. 연결 사전 준비 (Pre-connection)

**현재**:
```kotlin
if (!openAIRealtimeTTS.isConnected()) {
    openAIRealtimeTTS.connect(...)
    delay(500)  // 연결 대기
}
```

**개선**:
- 방 입장 시 모든 언어 쌍에 대해 미리 연결
- 또는 최근 사용한 언어 쌍 연결 유지
- 언어 변경 시에만 재연결

**효과**:
- 연결 대기 시간 제거 (500ms 절약)
- 즉시 번역 + TTS 시작 가능

### 6. 병렬 처리 최적화

**현재**:
```
STT 완료 → 번역 시작 → 번역 완료 → TTS 시작 → TTS 완료
```

**개선**:
```
STT 부분 전사 → 번역 시작 (병렬)
              → Firebase 전송 (병렬)
              → UI 업데이트 (즉시)
STT 완료 → 번역 완료 → TTS 시작 (병렬)
```

**효과**:
- 전체 처리 시간 감소
- 사용자 경험 개선

---

## 구현 우선순위

### 높은 우선순위 (즉시 효과)

1. **부분 전사 즉시 처리**
   - 부분 전사가 나오면 즉시 번역 시작
   - UI에 부분 번역 표시
   - 효과: ~500ms 지연 감소

2. **부분 전사 Firebase 전송**
   - `sendPartialText` 활용
   - 상대방이 실시간으로 볼 수 있음
   - 효과: 대화 흐름 개선

3. **연결 사전 준비**
   - 방 입장 시 연결 유지
   - 효과: 500ms 연결 대기 시간 제거

### 중간 우선순위 (추가 개선)

4. **스트리밍 번역**
   - OpenAI Realtime API 활용
   - 부분 번역 텍스트 스트리밍
   - 효과: 번역 지연 감소

5. **스트리밍 TTS**
   - 부분 번역으로 TTS 시작
   - 효과: TTS 시작 시간 감소

### 낮은 우선순위 (최적화)

6. **병렬 처리 최적화**
   - 여러 작업 동시 수행
   - 효과: 전체 처리 시간 감소

---

## 예상 효과

### 현재 플로우 (예상 지연)
```
STT 부분 전사: 0ms
STT 완료: ~1000ms
번역 시작: ~1000ms
번역 완료: ~1500ms
Firebase 전송: ~1500ms
TTS 시작: ~1500ms
TTS 완료: ~2500ms
```

### 개선 후 플로우 (예상 지연)
```
STT 부분 전사: 0ms
  → 번역 시작 (즉시): ~100ms
  → Firebase 전송 (즉시): ~100ms
  → UI 업데이트 (즉시): ~100ms
STT 완료: ~1000ms
  → 번역 완료: ~1100ms
  → TTS 시작: ~1100ms
  → TTS 완료: ~2000ms
```

**개선 효과**:
- 첫 번째 피드백: ~1500ms → ~100ms (15배 개선)
- 전체 완료: ~2500ms → ~2000ms (20% 개선)

---

## 구현 예시

### 부분 전사 즉시 처리

```kotlin
googleSTT.onTranscript = { text, isFinal, speaker, detectedLang ->
    if (isFinal) {
        // 최종 처리
        processTranscript(text, ...)
    } else {
        // 부분 전사 즉시 처리
        processPartialTranscript(text, speaker, detectedLang)
    }
}

fun processPartialTranscript(text: String, speaker: String?, detectedLang: String?) {
    scope.launch {
        // 부분 번역 시작 (스트리밍)
        if (needsTranslation) {
            // OpenAI Realtime API로 부분 번역 요청
            openAIRealtimeTTS.translatePartial(text)
        }
        
        // 부분 전사 Firebase 전송
        if (speaker == "나" || speaker == "1") {
            firebaseService.sendPartialText(text, detectedLang ?: unifiedLanguage)
        }
        
        // UI 즉시 업데이트
        currentOriginal = text
    }
}
```

### 연결 사전 준비

```kotlin
// 방 입장 시 모든 언어 쌍 연결 준비
LaunchedEffect(room.id) {
    // 주요 언어 쌍 미리 연결
    val commonPairs = listOf(
        "ko" to "en",
        "en" to "ko",
        "ar" to "en",
        "es" to "en"
    )
    
    commonPairs.forEach { (source, target) ->
        // 백그라운드에서 연결 준비
        scope.launch {
            openAIRealtimeTTS.connect(source, target)
        }
    }
}
```

---

## 결론

현재 플로우는 **순차 처리**로 인해 지연이 발생합니다. 
**스트리밍 처리**로 전환하면:
- 첫 번째 피드백 시간: **15배 개선**
- 전체 완료 시간: **20% 개선**
- 사용자 경험: **대폭 개선**

가장 효과적인 개선은 **부분 전사 즉시 처리**와 **연결 사전 준비**입니다.
