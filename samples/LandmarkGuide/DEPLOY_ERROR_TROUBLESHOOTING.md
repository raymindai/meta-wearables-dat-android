# 배포 에러 해결 방법

## 현재 상황
- 함수는 이미 배포되어 있음 (`onMessageCreated`)
- 재배포 시 에러 발생

## 해결 방법

### 방법 1: 환경 변수만 설정하고 테스트 (권장)

Gen 1 함수의 경우, 환경 변수를 설정하면 **재배포 없이도 자동으로 적용**될 수 있습니다.

1. **Firebase Console에서 환경 변수 설정 완료 확인**
   - Functions → `onMessageCreated` → Configuration
   - `OPENAI_API_KEY`가 설정되어 있는지 확인

2. **함수 테스트**
   - Android 앱에서 Majlis 방 테스트
   - 메시지를 보내서 번역이 작동하는지 확인

3. **작동 안 하면 재배포 필요**

### 방법 2: Cloud Build 로그 확인

배포 에러의 정확한 원인을 확인:

1. **링크 열기:**
   ```
   https://console.cloud.google.com/cloud-build/builds;region=us-central1?project=131952023916
   ```

2. **가장 최근 빌드 클릭**

3. **에러 메시지 확인**
   - 빨간색 에러 찾기
   - 에러 내용을 알려주시면 해결 방법 제시

### 방법 3: 함수 삭제 후 재배포

만약 함수에 문제가 있다면:

```bash
# 함수 삭제
firebase functions:delete onMessageCreated --region us-central1 --force

# 재배포
firebase deploy --only functions
```

## 현재 상태 확인

함수가 정상 작동하는지 확인:

1. **Firebase Console 접속**
   ```
   https://console.firebase.google.com/project/wearables-projects/functions
   ```

2. **함수 상태 확인**
   - `onMessageCreated` 함수가 "Active" 상태인지 확인
   - "Configuration" 탭에서 환경 변수 확인

3. **테스트**
   - Android 앱에서 Majlis 방에 메시지 보내기
   - Firebase Realtime Database에서 `translatedTexts` 필드가 생성되는지 확인

## 다음 단계

1. 환경 변수가 설정되어 있다면 → **바로 테스트해보세요!**
2. 환경 변수가 없다면 → Firebase Console에서 설정
3. 여전히 작동 안 하면 → Cloud Build 로그 확인 또는 함수 재생성
