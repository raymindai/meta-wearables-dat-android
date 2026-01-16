# 배포 가이드 - 서버 측 번역 기능

## ✅ 완료된 작업

1. ✅ Firebase Cloud Functions 코드 생성 (`firebase-functions/index.js`)
2. ✅ Android 클라이언트 코드 수정 (서버 번역 사용)
3. ✅ Android 앱 재빌드 및 설치 완료

## 🔧 Firebase Functions 배포 필요

서버 측 번역 기능을 활성화하려면 Firebase Functions를 배포해야 합니다.

### 1. Firebase 프로젝트 설정

```bash
# Firebase CLI 로그인 (아직 안 했다면)
firebase login

# 프로젝트 디렉토리에서 Firebase 초기화
cd /Users/hyunsangcho/Desktop/Projects/meta-wearables-dat-android/samples/LandmarkGuide
firebase init functions

# 기존 프로젝트 사용 선택 또는 새 프로젝트 생성
# firebase-functions 폴더 선택 (이미 있으므로 덮어쓰지 않기)
```

### 2. OpenAI API 키 설정

```bash
# 방법 1: Firebase Functions Config 사용 (권장)
firebase functions:config:set openai.key="YOUR_OPENAI_API_KEY"

# 방법 2: 환경 변수 사용
# .env 파일 생성 또는 Firebase Console에서 설정
export OPENAI_API_KEY="YOUR_OPENAI_API_KEY"
```

### 3. Firebase Functions 배포

```bash
cd firebase-functions
npm install  # 이미 설치되어 있지만 확인용
firebase deploy --only functions
```

### 4. 배포 확인

```bash
# Functions 로그 확인
firebase functions:log

# Functions 목록 확인
firebase functions:list
```

## 📱 Android 앱 테스트

1. **앱 실행**: 이미 설치된 "Humain Eyes" 앱 실행
2. **Majlis 모드 진입**: 홈 화면에서 Majlis 선택
3. **방 생성/참여**: QR 코드 또는 딥링크로 방 참여
4. **테스트 시나리오**:
   - 사용자 A: 한국어로 말하기
   - 사용자 B: 영어로 설정된 상태에서 수신
   - **확인 사항**: B가 번역 딜레이 없이 즉시 번역된 텍스트를 받는지 확인

## 🔍 문제 해결

### 번역이 안 되는 경우

1. **Firebase Functions 배포 확인**:
   ```bash
   firebase functions:list
   # onMessageCreated 함수가 보여야 함
   ```

2. **Functions 로그 확인**:
   ```bash
   firebase functions:log --only onMessageCreated
   ```

3. **OpenAI API 키 확인**:
   ```bash
   firebase functions:config:get
   ```

4. **Firebase Console에서 확인**:
   - Firebase Console → Functions 탭
   - 함수 실행 상태 및 에러 로그 확인

### 번역이 느린 경우

- Firebase Functions는 병렬 번역을 사용하므로 일반적으로 200-500ms 내 완료됩니다
- 네트워크 상태 확인
- OpenAI API 상태 확인: https://status.openai.com/

## 📊 성능 모니터링

### Firebase Console에서 확인:
1. Functions 탭 → `onMessageCreated` 함수 선택
2. 실행 시간, 호출 횟수, 에러율 확인
3. 로그에서 번역 시간 확인

### 예상 성능:
- 번역 시간: 200-500ms (GPT-3.5-turbo)
- 동시 사용자: 수십 명까지 문제없음
- 비용: 매우 저렴 ($0.0015/1K tokens)

## 🎯 다음 단계

1. Firebase Functions 배포 완료 후
2. 두 기기에서 Majlis 방 테스트
3. 번역 딜레이가 제거되었는지 확인
4. Firebase Console에서 Functions 성능 모니터링

## 📝 참고

- Firebase Functions 코드: `firebase-functions/index.js`
- Android 클라이언트 코드: `app/src/main/java/.../ui/MajlisScreen.kt`
- Firebase 서비스 코드: `app/src/main/java/.../firebase/FirebaseRoomService.kt`
