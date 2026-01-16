# Firebase Functions Gen 2 - Secret 설정 가이드

## Gen 2로 변경 완료!

코드를 Gen 2로 변경했습니다. 이제 Secret 설정이 훨씬 간단합니다.

## Secret 설정 방법 (Gen 2)

### 1단계: Secret이 이미 생성되어 있는지 확인

이미 Secret을 생성했으므로 확인만 하면 됩니다:

```bash
firebase functions:secrets:access OPENAI_API_KEY
```

### 2단계: 배포 (Secret 자동 연결)

Gen 2에서는 코드에서 `secrets: [openaiApiKeySecret]`로 선언했으므로,
배포 시 자동으로 Secret이 연결됩니다!

```bash
firebase deploy --only functions
```

## Gen 1 vs Gen 2 차이점

### Gen 1 (이전)
- 환경 변수를 Firebase Console에서 수동 설정 필요
- 함수별로 개별 설정 필요
- 설정이 복잡함

### Gen 2 (현재)
- Secret을 코드에서 선언 (`defineSecret`)
- 배포 시 자동으로 연결됨
- Firebase Console에서 별도 설정 불필요!

## 확인 방법

배포 후 Firebase Console에서:
1. Functions → `onMessageCreated` 함수 클릭
2. "Configuration" 탭 확인
3. "Secrets" 섹션에 `OPENAI_API_KEY`가 자동으로 연결되어 있음

## 문제 해결

만약 Secret이 없다면:
```bash
echo "YOUR_API_KEY" | firebase functions:secrets:set OPENAI_API_KEY
```
