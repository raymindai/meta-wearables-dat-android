# STT + 언어 감지 통합 개선안

## 현재 문제점

### 현재 플로우
```
1. OpenAI Realtime STT → 텍스트 획득
2. Google Translation API detectLanguage() → 별도 API 호출
3. 언어 필터링
```

**문제**:
- ❌ 불필요한 API 호출 (STT + 언어 감지 = 2번 호출)
- ❌ 추가 지연 시간 (언어 감지 API 응답 대기)
- ❌ 비용 증가 (Translation API 호출 비용)
- ❌ 네트워크 오버헤드

## 개선 방안

### Google STT 사용 (권장)

Google Cloud Speech-to-Text는 **STT와 언어 감지를 동시에** 처리합니다.

#### 장점
- ✅ **단일 API 호출**: STT + 언어 감지 통합
- ✅ **더 빠름**: 별도 언어 감지 API 호출 불필요
- ✅ **비용 절감**: Translation API 호출 제거
- ✅ **더 정확**: 오디오 기반 언어 감지 (텍스트 기반보다 정확)

#### Google STT 언어 감지 기능
```kotlin
// GoogleSpeechService.kt에 이미 구현되어 있음
put("languageCode", "auto")  // 자동 감지
put("alternativeLanguageCodes", JSONArray().apply {
    put("ar-SA")
    put("ko-KR")
    put("en-US")
    put("es-ES")
})

// 응답에서 언어 정보 자동 제공
val detectedLanguage = result.optString("languageCode", null)
onLanguageDetected?.invoke(detectedLanguage)
```

### 구현 옵션

#### 옵션 1: Google STT를 기본으로 사용 (권장)
- OpenAI Realtime STT 대신 Google STT 사용
- 언어 감지 통합
- 더 빠르고 효율적

#### 옵션 2: 하이브리드 접근
- Google STT: 언어 감지가 중요한 경우
- OpenAI Realtime: 실시간 스트리밍이 중요한 경우
- 사용자가 선택 가능

#### 옵션 3: Google STT로 언어 감지만
- OpenAI Realtime STT 유지
- 언어 감지만 Google STT 사용 (오디오 기반)
- 하지만 여전히 2번 호출이므로 비효율적

## 권장 구현: 옵션 1

### 변경 사항

1. **MajlisScreen.kt**:
   ```kotlin
   // 현재
   var useRealtime by remember { mutableStateOf(true) }
   
   // 변경
   var useGoogleSTT by remember { mutableStateOf(true) }  // Google STT 기본
   var useRealtime by remember { mutableStateOf(false) }    // 옵션으로
   ```

2. **Google STT 사용 시**:
   ```kotlin
   googleSTT.onLanguageDetected = { detectedLang ->
       // 언어 필터링 (별도 API 호출 불필요!)
       val expectedLangCode = when (mySpeakingLanguage) {
           TranslationService.LANG_KOREAN -> "ko"
           TranslationService.LANG_ENGLISH -> "en"
           TranslationService.LANG_ARABIC -> "ar"
           TranslationService.LANG_SPANISH -> "es"
           else -> null
       }
       
       // Google STT가 이미 언어를 감지했으므로 별도 API 호출 불필요
       val isMatch = detectedLang.startsWith(expectedLangCode) || 
                    expectedLangCode?.let { detectedLang.contains(it) } == true
       
       if (!isMatch) {
           return@onLanguageDetected  // 차단
       }
   }
   
   googleSTT.onTranscript = { transcript, isFinal ->
       // 언어는 이미 감지됨 (별도 API 호출 불필요)
       // 바로 처리 가능
       handleTranscript(transcript)
   }
   ```

3. **언어 감지 API 호출 제거**:
   ```kotlin
   // 제거할 코드
   val detectedLang = translationService.detectLanguage(transcript)  // ❌ 제거
   
   // Google STT가 이미 언어를 제공하므로 불필요
   ```

### 성능 개선 예상

- **API 호출**: 2번 → 1번 (50% 감소)
- **지연 시간**: ~200-300ms 감소 (언어 감지 API 응답 시간)
- **비용**: Translation API 호출 비용 제거
- **정확도**: 오디오 기반 언어 감지 (텍스트 기반보다 정확)

## 비교

| 항목 | 현재 (OpenAI Realtime + Translation API) | 개선안 (Google STT) |
|------|-------------------------------------------|---------------------|
| API 호출 | 2번 (STT + 언어 감지) | 1번 (STT + 언어 감지 통합) |
| 지연 시간 | STT + 언어 감지 API 응답 | STT만 (언어 감지 포함) |
| 비용 | STT + Translation API | STT만 |
| 정확도 | 텍스트 기반 언어 감지 | 오디오 기반 언어 감지 (더 정확) |
| 스트리밍 | 실시간 스트리밍 | VAD 기반 (약간 지연) |

## 결론

**Google STT 사용을 권장합니다:**
- ✅ 더 효율적 (단일 API 호출)
- ✅ 더 빠름 (별도 언어 감지 불필요)
- ✅ 더 정확 (오디오 기반 언어 감지)
- ✅ 비용 절감

**OpenAI Realtime은 다음 경우에 유용:**
- 실시간 스트리밍이 매우 중요한 경우
- STT + 번역 + TTS를 하나의 WebSocket으로 처리해야 하는 경우

**하지만 현재는 언어 감지를 별도로 하고 있으므로, Google STT가 더 효율적입니다.**
