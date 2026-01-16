# 번역/음성 문제 해결 가이드

## 문제: 수신자가 transcript만 보고 번역과 음성이 안 나옴

## 수정 사항

### 1. `onChildChanged` 개선
- `translatedTexts`가 비어있어도 다시 처리하도록 수정
- 더 자세한 로그 추가

### 2. 디버깅 로그 추가
- 메시지 ID, 언어, translatedTexts 내용 로그
- TTS 재생 여부 로그

## 문제 진단 방법

### 1단계: 로그 확인

Android Studio Logcat에서 다음 로그를 확인:

```bash
# 필터: "MajlisRoom" 또는 "FirebaseRoom"
```

**확인할 로그:**
- `📨 Firebase message received` - 메시지 수신 확인
- `🔄 Message updated with translations` - 번역 업데이트 확인
- `✅ Server translation received` - 번역 수신 확인
- `🔊 Playing TTS` - TTS 재생 확인
- `⏳ Waiting for server translation` - 번역 대기 중

### 2단계: Firebase Database 확인

Firebase Console → Realtime Database:
```
/rooms/{roomId}/messages/{messageId}
```

**확인 사항:**
- `originalText` 필드가 있는지
- `translatedTexts` 필드가 있는지
- `translatedTexts`에 수신자의 언어가 있는지

예:
```json
{
  "originalText": "안녕하세요",
  "senderLanguage": "ko",
  "translatedTexts": {
    "ko": "안녕하세요",
    "en": "Hello",
    "ar": "مرحبا"
  }
}
```

### 3단계: Firebase Functions 로그 확인

Google Cloud Console → Functions → `onMessageCreated` → Logs:

**확인할 로그:**
- `🔄 Processing new message` - 메시지 처리 시작
- `📋 Translating to X languages` - 번역할 언어 목록
- `✅ Translations complete` - 번역 완료

### 4단계: TTS 설정 확인

앱 내에서:
- Peer TTS 토글이 켜져 있는지 확인
- 볼륨이 켜져 있는지 확인
- Bluetooth 오디오가 연결되어 있는지 확인

## 가능한 원인 및 해결

### 원인 1: Firebase Functions가 번역을 안 함

**증상:**
- `translatedTexts` 필드가 없음
- Functions 로그에 에러

**해결:**
1. Functions 로그 확인
2. 환경 변수 `OPENAI_API_KEY` 확인
3. Functions 재배포

### 원인 2: `onChildChanged`가 트리거 안 됨

**증상:**
- `translatedTexts`는 있지만 앱에서 업데이트 안 됨
- `🔄 Message updated` 로그가 안 보임

**해결:**
- 이미 수정됨: `onChildChanged`에서 항상 `onMessageReceived` 호출

### 원인 3: 언어 코드 불일치

**증상:**
- `translatedTexts`는 있지만 내 언어가 없음
- 로그에 "Waiting for server translation" 계속 나옴

**해결:**
- 언어 코드 확인:
  - 앱에서 설정한 언어: `myListeningLanguage`
  - Firebase에 저장된 언어: `user.language`
  - 둘이 일치해야 함

### 원인 4: TTS가 재생 안 됨

**증상:**
- 번역은 화면에 나오지만 음성이 안 나옴

**해결:**
1. `peerTtsEnabled` 확인 (앱 내 토글)
2. Bluetooth 오디오 연결 확인
3. 볼륨 확인
4. 로그에서 `🔊 Playing TTS` 확인

## 테스트 방법

1. **두 디바이스에서 앱 실행**
2. **같은 방에 참여**
3. **언어 설정 확인**
   - 발화자: 한국어
   - 수신자: 영어
4. **발화자가 말함**
5. **로그 확인:**
   ```bash
   adb logcat | grep -E "MajlisRoom|FirebaseRoom"
   ```
6. **Firebase Database 확인**
7. **수신자 화면 확인**

## 다음 단계

로그를 확인한 후:
- 어떤 로그가 나오는지 알려주세요
- Firebase Database에 `translatedTexts`가 있는지 확인
- Functions 로그에 에러가 있는지 확인

이 정보를 주시면 정확한 해결 방법을 제시하겠습니다!
