# Firebase Functions 환경 변수 설정 가이드

## 단계별 설정 방법

### 1. Firebase Console 접속
- URL: https://console.firebase.google.com/project/wearables-projects/functions
- 또는: Firebase Console → 프로젝트 선택 (wearables-projects) → Functions 메뉴

### 2. 환경 변수 설정
1. Functions 페이지에서 **"Configuration"** 또는 **"환경 변수"** 탭 클릭
2. **"환경 변수 추가"** 또는 **"Add environment variable"** 버튼 클릭
3. 다음 정보 입력:
   - **변수 이름 (Name)**: `OPENAI_API_KEY`
   - **값 (Value)**: `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`
4. **저장** 또는 **Save** 클릭

### 3. 확인
- 환경 변수 목록에 `OPENAI_API_KEY`가 표시되는지 확인
- 값이 올바르게 설정되었는지 확인

## 참고
- 환경 변수는 함수 배포 시 자동으로 주입됩니다
- Secret으로 설정할 수도 있지만, 환경 변수가 더 간단합니다
