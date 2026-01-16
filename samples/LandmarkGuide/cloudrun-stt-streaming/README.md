# stt-streaming (Cloud Run)

WebSocket bridge to **Google Speech-to-Text v2** `StreamingRecognize` using **Chirp 3** (`model=chirp_3`).

## Deploy (example)

```bash
gcloud run deploy stt-streaming \
  --source ./cloudrun-stt-streaming \
  --region asia-northeast1 \
  --service-account wearables-projects@appspot.gserviceaccount.com \
  --allow-unauthenticated
```

## WebSocket protocol

- Connect: `wss://<cloud-run-url>/ws`
- First message: JSON text

```json
{
  "languageCodes": ["ko-KR"],
  "location": "asia-northeast1",
  "model": "chirp_3",
  "sampleRateHertz": 8000
}
```

- Then stream binary audio chunks: PCM16 LE mono.
- Server responds with JSON:
  - `{ "type": "ready" }`
  - `{ "type": "result", "text": "...", "isFinal": false, "languageCode": "ko-KR" }`
  - `{ "type": "error", "message": "..." }`

