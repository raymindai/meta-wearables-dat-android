# Firebase Functions Gen 1 - 환경 변수 설정 가이드

## Gen 1 사용 (환경 변수 방식)

Gen 1에서 Gen 2로 직접 업그레이드가 불가능하므로, Gen 1을 사용하고 환경 변수를 설정합니다.

## 환경 변수 설정 방법 (Gen 1)

### 방법 1: Firebase Console에서 설정 (권장)

1. **Firebase Console 접속**
   ```
   https://console.firebase.google.com/project/wearables-projects/functions
   ```

2. **함수 선택**
   - 왼쪽 메뉴에서 **"Functions"** 클릭
   - 함수 목록에서 **"onMessageCreated"** 함수를 찾습니다
   - (아직 배포 안 되었으면 다음 단계로)

3. **환경 변수 설정 위치 찾기**
   - Gen 1 함수의 경우, 함수별로 환경 변수를 설정합니다
   - **두 가지 방법이 있습니다:**

   **방법 A: 함수 배포 후 설정**
   - 먼저 함수를 배포 (환경 변수 없이)
   - 배포 후 함수를 클릭
   - **"Configuration"** 탭 클릭
   - **"Environment variables"** 섹션에서 추가

   **방법 B: 배포 시 설정 (CLI)**
   ```bash
   firebase functions:config:set openai.key="YOUR_API_KEY"
   ```
   하지만 이 방법은 deprecated되었습니다.

### 방법 2: 배포 후 Firebase Console에서 설정

1. **먼저 함수 배포** (환경 변수 없이)
   ```bash
   firebase deploy --only functions
   ```

2. **Firebase Console에서 환경 변수 추가**
   - Functions → `onMessageCreated` 함수 클릭
   - **"Configuration"** 탭
   - **"Environment variables"** → **"Add variable"**
   - Name: `OPENAI_API_KEY`
   - Value: `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`
   - **Save**

3. **함수 재배포** (환경 변수 적용)
   ```bash
   firebase deploy --only functions
   ```

## 중요 사항

- Gen 1에서는 함수별로 환경 변수를 설정해야 합니다
- 환경 변수는 함수의 Configuration 탭에서 설정합니다
- 배포 후에도 환경 변수를 추가/수정할 수 있습니다
