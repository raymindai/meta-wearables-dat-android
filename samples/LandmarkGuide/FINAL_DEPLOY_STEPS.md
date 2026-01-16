# 최종 배포 단계 (Gen 1)

## 현재 상황
- 코드는 Gen 1로 작성 완료
- 빌드 에러 발생 중

## 해결 방법

### 1단계: Cloud Build 로그 확인 (필수!)

빌드 에러의 정확한 원인을 확인해야 합니다:

1. **링크 열기:**
   ```
   https://console.cloud.google.com/cloud-build/builds;region=us-central1?project=131952023916
   ```

2. **가장 최근 빌드 클릭** (상단에 있는 것)

3. **로그 확인:**
   - 빨간색 에러 메시지 찾기
   - 에러 내용을 복사해서 알려주세요

### 2단계: 환경 변수 설정 (배포 성공 후)

배포가 성공하면:

1. **Firebase Console 접속**
   ```
   https://console.firebase.google.com/project/wearables-projects/functions
   ```

2. **함수 클릭**
   - `onMessageCreated` 함수 클릭

3. **Configuration 탭**
   - 상단 탭에서 **"Configuration"** 클릭

4. **Environment variables 추가**
   - **"Environment variables"** 섹션 찾기
   - **"Add variable"** 또는 **"환경 변수 추가"** 버튼 클릭
   - Name: `OPENAI_API_KEY`
   - Value: `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`
   - **Save**

5. **함수 재배포** (환경 변수 적용)
   ```bash
   firebase deploy --only functions
   ```

## 중요 사항

- **Gen 1에서는 함수별로 환경 변수를 설정합니다**
- 환경 변수는 함수의 Configuration 탭에서만 설정 가능합니다
- 프로젝트 레벨 환경 변수는 Gen 2에서만 사용 가능합니다

## 다음 단계

Cloud Build 로그의 에러 메시지를 알려주시면 정확한 해결 방법을 제시하겠습니다!
