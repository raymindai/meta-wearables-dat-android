# 🎉 함수 배포 완료! 이제 환경 변수만 설정하면 됩니다

## ✅ 현재 상태
- ✅ `onMessageCreated` 함수 배포 완료 (v1, us-central1)
- ⏳ 환경 변수 설정 필요

## 🚀 지금 바로 해야 할 일

### 환경 변수 설정 (5분 소요)

1. **Firebase Console 열기**
   ```
   https://console.firebase.google.com/project/wearables-projects/functions
   ```

2. **함수 클릭**
   - 왼쪽 메뉴에서 **"Functions"** 클릭
   - 함수 목록에서 **`onMessageCreated`** 클릭

3. **Configuration 탭**
   - 상단 탭에서 **"Configuration"** 클릭
   - (또는 함수 상세 페이지에서 "Configuration" 탭 찾기)

4. **Environment variables 추가**
   - 페이지를 스크롤해서 **"Environment variables"** 섹션 찾기
   - **"Add variable"** 또는 **"환경 변수 추가"** 버튼 클릭
   - 입력:
     - **Name**: `OPENAI_API_KEY`
     - **Value**: `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`
   - **Save** 또는 **저장** 클릭

5. **함수 재배포** (환경 변수 적용)
   ```bash
   firebase deploy --only functions
   ```

## 📍 환경 변수 위치 찾는 방법

만약 "Environment variables" 섹션이 안 보이면:

1. 함수 상세 페이지에서 **"Configuration"** 탭 확인
2. 또는 **"Edit"** 버튼 클릭 → 환경 변수 설정 옵션 확인
3. 또는 상단 메뉴에서 **"Environment variables"** 직접 검색

## ✅ 완료 확인

환경 변수 설정 후 재배포가 성공하면:
- Firebase Console에서 함수가 정상 작동하는지 확인
- Android 앱에서 Majlis 방 테스트
- 번역이 서버에서 처리되는지 확인

## 🎯 다음 단계

환경 변수 설정이 완료되면 알려주세요! 재배포를 진행하겠습니다.
