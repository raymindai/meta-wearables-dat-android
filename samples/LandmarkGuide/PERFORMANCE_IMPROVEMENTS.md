# 성능 개선 제안

## 현재 구현된 개선사항

### 1. 메시지 완료 상태 표시
- ✅ `ChatMessage`에 `isComplete` 필드 추가
- ✅ 스트리밍 중인 메시지에 "⏳" 및 "⋯" 표시
- ✅ 메시지 완료 시 즉시 상태 업데이트

### 2. 정렬 최적화
- ✅ `chatHistory`를 추가 시 정렬하여 유지
- ✅ UI 렌더링 시 재정렬 제거 (`chatHistory.sortedBy()` 제거)

### 3. 상태 관리 개선
- ✅ 메시지 처리 완료 시 `currentOriginal`, `currentTranslation`, `isSpeaking` 즉시 초기화
- ✅ `userState`를 "LISTENING"으로 즉시 변경

## 추가 성능 개선 제안

### 1. 메시지 버블 렌더링 최적화

**현재 문제:**
- 모든 메시지를 매번 렌더링
- `forEach`로 순회하면서 각 메시지마다 Surface 생성

**개선 방안:**
```kotlin
// LazyColumn 사용 (이미 스크롤 가능하지만 최적화 필요)
LazyColumn(
    modifier = Modifier.fillMaxSize(),
    verticalArrangement = Arrangement.spacedBy(8.dp)
) {
    items(
        items = chatHistory,
        key = { it.messageId.ifBlank { it.timestamp.toString() } }
    ) { msg ->
        // 메시지 버블 렌더링
    }
}
```

**효과:**
- 화면에 보이는 메시지만 렌더링 (가상화)
- 메시지 ID 기반 키로 안정적인 리컴포지션
- 스크롤 성능 향상

### 2. 번역 콜백 최적화

**현재 문제:**
- 전역 콜백이 매번 덮어씌워짐
- 메시지 매칭 로직이 복잡함

**개선 방안:**
```kotlin
// 메시지별 고유 콜백 관리
data class PendingTranslation(
    val messageId: String,
    val callback: (String) -> Unit,
    val timestamp: Long
)

val pendingTranslations = remember { mutableMapOf<String, PendingTranslation>() }

// Realtime TTS 응답 시 순서대로 처리
openAIRealtimeTTS.onTranslation = { translatedText ->
    // 가장 오래된 pending 메시지부터 처리
    val oldest = pendingTranslations.values.minByOrNull { it.timestamp }
    oldest?.let {
        it.callback(translatedText)
        pendingTranslations.remove(it.messageId)
    }
}
```

**효과:**
- 메시지 순서 보장
- 콜백 충돌 방지
- 메모리 누수 방지

### 3. Firebase 메시지 리스너 최적화

**현재 문제:**
- `onChildChanged`가 비활성화되어 있지만, 필요 시 재활성화 고려
- 메시지 업데이트 시 전체 리스너 재등록

**개선 방안:**
```kotlin
// 메시지별 리스너 등록 (필요한 경우만)
fun listenForMessageUpdates(messageId: String, onUpdate: (RoomMessage) -> Unit) {
    val messageRef = currentRoomRef?.child("messages")?.child(messageId)
    messageRef?.addValueEventListener(object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            parseMessage(snapshot)?.let(onUpdate)
        }
        override fun onCancelled(error: DatabaseError) {}
    })
}
```

**효과:**
- 필요한 메시지만 감시
- 불필요한 네트워크 트래픽 감소

### 4. 오디오 버퍼 최적화

**현재 구현:**
- 버퍼 크기: 8x (개선됨)
- 재시도 로직 추가 (개선됨)

**추가 개선 방안:**
```kotlin
// 오디오 큐 사용
private val audioQueue = ConcurrentLinkedQueue<ByteArray>()

private fun playAudioChunk(audioData: ByteArray) {
    audioQueue.offer(audioData)
    processAudioQueue()
}

private fun processAudioQueue() {
    scope.launch {
        while (audioQueue.isNotEmpty()) {
            val chunk = audioQueue.poll() ?: break
            // 오디오 재생
            // 버퍼가 가득 찰 경우 큐에 다시 추가
        }
    }
}
```

**효과:**
- 오디오 드롭아웃 방지
- 부드러운 재생

### 5. 상태 업데이트 배칭

**현재 문제:**
- 여러 상태가 빠르게 변경될 때 리컴포지션이 많이 발생

**개선 방안:**
```kotlin
// 상태 업데이트 배칭
var pendingUpdates = mutableListOf<() -> Unit>()

fun batchUpdate(update: () -> Unit) {
    pendingUpdates.add(update)
    if (pendingUpdates.size >= 5) {
        flushUpdates()
    }
}

fun flushUpdates() {
    pendingUpdates.forEach { it() }
    pendingUpdates.clear()
}
```

**효과:**
- 리컴포지션 횟수 감소
- UI 업데이트 성능 향상

### 6. 메모리 최적화

**현재 문제:**
- `chatHistory`가 무한정 증가
- 오래된 메시지도 계속 메모리에 유지

**개선 방안:**
```kotlin
// 최근 N개 메시지만 유지
const val MAX_CHAT_HISTORY = 100

fun addMessage(message: ChatMessage) {
    chatHistory = (chatHistory + message)
        .sortedBy { it.timestamp }
        .takeLast(MAX_CHAT_HISTORY)
}
```

**효과:**
- 메모리 사용량 제한
- 오래된 메시지 자동 제거

### 7. 네트워크 최적화

**현재 문제:**
- Firebase 메시지가 작은 단위로 전송
- 불필요한 네트워크 요청

**개선 방안:**
```kotlin
// 메시지 배치 전송
private val messageBatch = mutableListOf<RoomMessage>()

fun sendMessageBatch() {
    if (messageBatch.isEmpty()) return
    
    val batchRef = currentRoomRef?.child("messages")
    messageBatch.forEach { message ->
        batchRef?.push()?.setValue(message.toMap())
    }
    messageBatch.clear()
}

// 주기적으로 배치 전송 (예: 500ms마다)
LaunchedEffect(Unit) {
    while (true) {
        delay(500)
        sendMessageBatch()
    }
}
```

**효과:**
- 네트워크 요청 횟수 감소
- 배터리 수명 향상

### 8. 코루틴 최적화

**현재 문제:**
- 여러 코루틴이 동시에 실행
- 리소스 경합 가능

**개선 방안:**
```kotlin
// 코루틴 스코프 관리
private val translationScope = CoroutineScope(
    Dispatchers.IO + SupervisorJob() + 
    CoroutineName("Translation")
)

// 번역 작업을 순차적으로 처리
private val translationMutex = Mutex()

suspend fun translateMessage(text: String) = translationMutex.withLock {
    // 번역 로직
}
```

**효과:**
- 리소스 경합 감소
- 안정성 향상

## 우선순위

1. **높음**: 메시지 버블 렌더링 최적화 (LazyColumn)
2. **높음**: 메모리 최적화 (최근 N개 메시지만 유지)
3. **중간**: 번역 콜백 최적화
4. **중간**: 상태 업데이트 배칭
5. **낮음**: 네트워크 최적화 (배치 전송)
6. **낮음**: 코루틴 최적화

## 측정 지표

개선 후 다음 지표를 측정:
- 메시지 렌더링 시간
- 메모리 사용량
- 네트워크 요청 횟수
- 배터리 소모량
- 앱 응답성 (FPS)
