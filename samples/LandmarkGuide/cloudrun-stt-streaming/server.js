/**
 * Cloud Run WebSocket server that streams raw PCM16 audio to Google Speech-to-Text v2 StreamingRecognize (Chirp 3).
 *
 * Client protocol:
 * - Connect to: wss://<service-url>/ws
 * - First message (TEXT, JSON):
 *   {
 *     "languageCodes": ["ko-KR", "en-US"],
 *     "location": "asia-northeast1",
 *     "model": "chirp_3",
 *     "sampleRateHertz": 8000
 *   }
 * - Subsequent messages: BINARY audio chunks (PCM16 LE, mono)
 * - Server sends TEXT messages (JSON):
 *   { "type": "ready" }
 *   { "type": "result", "text": "...", "isFinal": false, "languageCode": "ko-KR" }
 *   { "type": "error", "message": "...", "details": "..." }
 */

const http = require('http');
const WebSocket = require('ws');
const speech = require('@google-cloud/speech');
const { GoogleAuth } = require('google-auth-library');
const grpc = require('@grpc/grpc-js');

const PORT = process.env.PORT || 8080;
const DEFAULT_LOCATION = process.env.STT_LOCATION || 'asia-northeast1';
const DEFAULT_MODEL = process.env.STT_MODEL || 'chirp_3';
const DEFAULT_SR = Number(process.env.STT_SAMPLE_RATE_HZ || 8000);
const DEFAULT_RECOGNIZER_ID = process.env.STT_RECOGNIZER_ID || 'chirp-streaming';
// If provided, use fully-qualified recognizer name (avoids formatting issues)
const DEFAULT_RECOGNIZER_NAME = process.env.STT_RECOGNIZER_NAME || '';

function safeJsonSend(ws, obj) {
  if (ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(obj));
  }
}

function logJson(prefix, obj) {
  // Ensure single-line logs for Cloud Run.
  // eslint-disable-next-line no-console
  console.log(`${prefix} ${JSON.stringify(obj)}`);
}

const server = http.createServer((req, res) => {
  if (req.url === '/healthz') {
    res.writeHead(200, { 'Content-Type': 'text/plain' });
    res.end('ok');
    return;
  }
  res.writeHead(404, { 'Content-Type': 'text/plain' });
  res.end('not found');
});

const wss = new WebSocket.Server({ server, path: '/ws' });

wss.on('connection', async (ws) => {
  let initialized = false;
  let sttClient = null;
  let sttStream = null;
  let location = DEFAULT_LOCATION;
  let sampleRateHertz = DEFAULT_SR;
  let model = DEFAULT_MODEL;
  let languageCodes = ['ko-KR'];

  function cleanup() {
    try { sttStream && sttStream.end(); } catch (_) {}
    sttStream = null;
    try { sttClient && sttClient.close && sttClient.close(); } catch (_) {}
    sttClient = null;
  }

  ws.on('close', cleanup);
  ws.on('error', cleanup);

  ws.on('message', async (data, isBinary) => {
    try {
      // First message must be JSON config
      if (!initialized) {
        if (isBinary) {
          safeJsonSend(ws, { type: 'error', message: 'Expected JSON config as first message' });
          ws.close();
          return;
        }
        const txt = data.toString('utf8');
        const cfg = JSON.parse(txt);

        location = typeof cfg.location === 'string' ? cfg.location : DEFAULT_LOCATION;
        model = typeof cfg.model === 'string' ? cfg.model : DEFAULT_MODEL;
        sampleRateHertz = Number.isFinite(Number(cfg.sampleRateHertz)) ? Number(cfg.sampleRateHertz) : DEFAULT_SR;
        languageCodes = Array.isArray(cfg.languageCodes) && cfg.languageCodes.length > 0 ? cfg.languageCodes : ['ko-KR'];

        // v2 client with regional endpoint
        const SpeechClientV2 = speech?.v2?.SpeechClient;
        if (!SpeechClientV2) {
          safeJsonSend(ws, { type: 'error', message: 'Missing @google-cloud/speech v2 client (SpeechClient)' });
          ws.close();
          return;
        }

        sttClient = new SpeechClientV2({
          apiEndpoint: `${location}-speech.googleapis.com`,
        });

        sttStream = sttClient.streamingRecognize()
          .on('data', (resp) => {
            const results = resp?.results || [];
            for (const r of results) {
              const alt = r?.alternatives?.[0];
              const text = alt?.transcript || '';
              if (!text) continue;
              safeJsonSend(ws, {
                type: 'result',
                text,
                isFinal: !!r.isFinal,
                languageCode: r.languageCode || null,
              });
            }
          })
          .on('error', (err) => {
            const md = err?.metadata && typeof err.metadata.getMap === 'function' ? err.metadata.getMap() : undefined;
            // eslint-disable-next-line no-console
            console.error('STT stream error', JSON.stringify({
              message: err?.message,
              code: err?.code,
              details: err?.details,
              metadataKeys: md ? Object.keys(md) : undefined,
              metadata: md,
            }));
            safeJsonSend(ws, {
              type: 'error',
              message: err?.message || String(err),
              code: err?.code,
              details: err?.details,
            });
            ws.close();
          });

        // First request: recognizer + streaming_config
        // Derive project id via ADC (Cloud Run service account)
        const auth = new GoogleAuth({ scopes: ['https://www.googleapis.com/auth/cloud-platform'] });
        const projectId = await auth.getProjectId();
        if (!projectId) {
          safeJsonSend(ws, { type: 'error', message: 'Failed to determine project id via ADC' });
          ws.close();
          return;
        }

        const recognizerName = DEFAULT_RECOGNIZER_NAME.startsWith('projects/')
          ? DEFAULT_RECOGNIZER_NAME
          : `projects/${projectId}/locations/${location}/recognizers/${DEFAULT_RECOGNIZER_ID}`;

        logJson('Starting streamingRecognize', {
          apiEndpoint: `${location}-speech.googleapis.com`,
          recognizer: recognizerName,
          model,
          sampleRateHertz,
          languageCodes,
        });

        sttStream.write({
          // Use a real recognizer resource (some environments reject implicit '_' for streaming)
          recognizer: recognizerName,
          streamingConfig: {
            config: {
              explicitDecodingConfig: {
                encoding: 'LINEAR16',
                sampleRateHertz,
                audioChannelCount: 1,
              },
              languageCodes,
              model,
              features: {
                enableAutomaticPunctuation: true,
                profanityFilter: false,
              },
            },
            streamingFeatures: {
              interimResults: true,
            },
          },
        });

        initialized = true;
        safeJsonSend(ws, { type: 'ready' });
        return;
      }

      // Subsequent messages: audio bytes
      if (!isBinary) return;
      if (!sttStream) return;
      const buf = Buffer.isBuffer(data) ? data : Buffer.from(data);
      if (buf.length === 0) return;
      sttStream.write({ audio: buf });
    } catch (e) {
      safeJsonSend(ws, { type: 'error', message: e?.message || String(e) });
      ws.close();
    }
  });
});

server.listen(PORT, () => {
  // eslint-disable-next-line no-console
  console.log(`stt-streaming listening on :${PORT}`);
});

