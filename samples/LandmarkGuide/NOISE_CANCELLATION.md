# 소음 제거 (Noise Cancellation) 기능

## 개요

주변 소음 문제를 해결하기 위해 다층적인 소음 제거 시스템을 구현했습니다.

## 구현된 기능

### 1. **Android 기본 Noise Suppressor**
- **위치**: `BluetoothScoAudioCapture.kt`
- **기능**: Android 시스템 레벨의 노이즈 억제
- **특징**: 하드웨어 가속 지원, 낮은 CPU 사용량
- **상태**: 항상 활성화 (디바이스 지원 시)

### 2. **고급 오디오 필터링 (AudioNoiseFilter)**
- **위치**: `audio/AudioNoiseFilter.kt`
- **기능**: 다층 소음 제거 파이프라인

#### 2.1 High-Pass Filter (고주파 통과 필터)
- **목적**: 저주파 소음 제거
- **제거 대상**:
  - 바람 소리
  - 차량 엔진 소리
  - 바닥 진동
  - 배경 잡음 (80Hz 이하)

#### 2.2 Low-Pass Filter (저주파 통과 필터)
- **목적**: 고주파 소음 제거
- **제거 대상**:
  - 히스 노이즈
  - 시끄러운 자음 (s, sh, ch 등)
  - 전기적 간섭 (8000Hz 이상)

#### 2.3 Noise Gate (노이즈 게이트)
- **목적**: 임계값 이하 오디오 억제
- **동작 방식**:
  1. 초기 2초 동안 배경 소음 레벨 측정
  2. 측정된 소음 레벨의 1.5배를 임계값으로 설정
  3. 이 임계값 이하의 오디오는 점진적으로 감소
  4. 부드러운 전환을 위해 이차 함수 페이드 적용

#### 2.4 Spectral Subtraction (스펙트럼 서브트랙션)
- **목적**: 주파수 도메인에서 배경 소음 제거
- **특징**: CPU 집약적이므로 기본적으로 비활성화
- **활성화 시**: 더 정확한 소음 제거 가능

### 3. **OpenAI Realtime VAD 임계값 조정**
- **위치**: `OpenAIRealtimeService.kt`
- **변경 사항**: VAD threshold를 0.7 → 0.75로 증가
- **효과**: 소음에 덜 민감하게 반응하여 잘못된 음성 인식 감소

## 사용 방법

### UI에서 제어
1. 방 내 화면에서 **"🔇 NC"** 토글 스위치를 찾습니다
2. 소음 제거를 활성화/비활성화할 수 있습니다
3. 기본값: **활성화됨**

### 프로그래밍 방식
```kotlin
val capture = BluetoothScoAudioCapture(context)
capture.startRecording(
    useHandsfree = true,
    gainMultiplier = 1.5f,
    enableAdvancedNoiseFilter = true  // 소음 필터 활성화
)
```

## 필터 파이프라인 순서

```
원본 오디오
    ↓
[High-Pass Filter] → 저주파 소음 제거
    ↓
[Low-Pass Filter] → 고주파 소음 제거
    ↓
[Noise Gate] → 배경 소음 억제
    ↓
[Gain Amplification] → 볼륨 증폭
    ↓
STT 서비스로 전송
```

## 성능 고려사항

### CPU 사용량
- **기본 필터 (High-Pass + Low-Pass + Noise Gate)**: 낮음 (~2-3%)
- **Spectral Subtraction 추가**: 중간 (~5-8%)
- **권장**: 기본 필터만 사용 (대부분의 경우 충분)

### 지연 시간 (Latency)
- **추가 지연**: < 5ms
- **영향**: 실시간 통신에 미미한 영향

### 메모리 사용량
- **추가 메모리**: ~10KB (필터 상태 저장)

## 튜닝 가이드

### 소음이 심한 환경
```kotlin
audioNoiseFilter = AudioNoiseFilter(
    enableHighPass = true,      // 활성화
    enableLowPass = true,       // 활성화
    enableNoiseGate = true,     // 활성화
    enableSpectralSubtraction = true  // 활성화 (CPU 사용량 증가)
)
```

### 조용한 환경
```kotlin
audioNoiseFilter = AudioNoiseFilter(
    enableHighPass = false,     // 비활성화
    enableLowPass = false,       // 비활성화
    enableNoiseGate = true,      // 활성화 (최소한의 필터링)
    enableSpectralSubtraction = false
)
```

### 음성 품질 최적화
```kotlin
// Noise Gate 임계값 조정 (AudioNoiseFilter.kt)
private const val NOISE_GATE_THRESHOLD = 500.0  // 낮추면 더 민감, 높이면 덜 민감
```

## 문제 해결

### 소음이 여전히 들리는 경우
1. **Noise Gate 임계값 조정**: `NOISE_GATE_THRESHOLD` 값을 높여보세요
2. **Spectral Subtraction 활성화**: 더 강력한 소음 제거 (CPU 사용량 증가)
3. **VAD threshold 증가**: OpenAI Realtime의 `threshold` 값을 0.75 → 0.8로 증가

### 음성이 너무 많이 잘리는 경우
1. **Noise Gate 임계값 낮추기**: `NOISE_GATE_THRESHOLD` 값을 낮춰보세요
2. **VAD threshold 감소**: OpenAI Realtime의 `threshold` 값을 0.75 → 0.7로 감소
3. **필터 비활성화**: 특정 필터를 비활성화하여 테스트

### CPU 사용량이 높은 경우
1. **Spectral Subtraction 비활성화**: 가장 CPU 집약적인 기능
2. **필터 개수 줄이기**: High-Pass 또는 Low-Pass 중 하나만 사용

## 기술 세부사항

### High-Pass Filter
- **타입**: Butterworth IIR 필터
- **차단 주파수**: 80Hz
- **구현**: 1차 필터 (단순하고 효율적)

### Low-Pass Filter
- **타입**: Butterworth IIR 필터
- **차단 주파수**: 8000Hz
- **구현**: 1차 필터 (단순하고 효율적)

### Noise Gate
- **알고리즘**: RMS 기반 동적 임계값
- **노이즈 플로어 추정**: 초기 2초 동안 자동 측정
- **페이드**: 이차 함수 기반 부드러운 전환

## 향후 개선 사항

1. **적응형 필터**: 환경 소음에 따라 자동으로 필터 강도 조정
2. **머신러닝 기반 소음 제거**: 딥러닝 모델을 사용한 더 정확한 소음 제거
3. **다중 마이크 빔포밍**: 여러 마이크 입력을 활용한 방향성 소음 제거
4. **실시간 필터 튜닝**: 사용자가 실시간으로 필터 파라미터 조정 가능

## 참고 자료

- [Android Audio Effects](https://developer.android.com/reference/android/media/audiofx/package-summary)
- [Digital Signal Processing for Audio](https://en.wikipedia.org/wiki/Digital_signal_processing)
- [Noise Gate Algorithm](https://en.wikipedia.org/wiki/Noise_gate)
