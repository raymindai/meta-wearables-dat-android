/**
 * Firebase Cloud Functions for Majlis Real-time Translation
 * 
 * When a new message is added to /rooms/{roomId}/messages/{messageId}:
 * 1. Get all users in the room and their preferred languages
 * 2. Translate the original text to each user's language in parallel
 * 3. Update the message with translatedTexts field
 * 
 * This eliminates client-side translation delay - users receive
 * pre-translated text and only need to play TTS.
 */

const functions = require('firebase-functions/v1');
const admin = require('firebase-admin');
const OpenAI = require('openai');
const { GoogleAuth } = require('google-auth-library');

// Initialize Firebase Admin (Gen 1 functions use default credentials)
// For Gen 1, admin.initializeApp() uses default credentials automatically
if (!admin.apps.length) {
  admin.initializeApp({
    databaseURL: 'https://wearables-projects-default-rtdb.firebaseio.com'
  });
}

// Helper function to get OpenAI client (called at runtime)
// Gen 1에서는 환경 변수를 process.env로 접근
function getOpenAIClient() {
  const apiKey = process.env.OPENAI_API_KEY;
  if (!apiKey) {
    throw new Error('OPENAI_API_KEY environment variable is not set. Please set it in Firebase Console → Functions → Configuration → Environment variables.');
  }
  return new OpenAI({
    apiKey: apiKey
  });
}

// Language code mapping
const LANGUAGE_CODES = {
  'ko': 'Korean',
  'en': 'English',
  'ar': 'Saudi Arabic',
  'es': 'Castilian Spanish'
};

/**
 * Translate text using OpenAI GPT-3.5-turbo (fastest model)
 */
async function translateText(text, targetLang, sourceLang) {
  try {
    const openai = getOpenAIClient();
    const targetLangName = LANGUAGE_CODES[targetLang] || 'English';
    const sourceLangName = LANGUAGE_CODES[sourceLang] || 'Korean';
    
    const response = await openai.chat.completions.create({
      model: 'gpt-3.5-turbo',
      messages: [
        {
          role: 'system',
          content: `You are a professional translator. Translate the user's message from ${sourceLangName} to ${targetLangName}. Return ONLY the translation, no explanations or additional text.`
        },
        {
          role: 'user',
          content: text
        }
      ],
      max_tokens: 100,
      temperature: 0.1
    });
    
    return response.choices[0]?.message?.content?.trim() || text;
  } catch (error) {
    console.error(`Translation error (${sourceLang} → ${targetLang}):`, error.message);
    return text; // Fallback to original text
  }
}

/**
 * Cloud Function: Triggered when a new message is added
 * Translates to all user languages in parallel
 * Gen 1 방식 사용 (환경 변수는 Firebase Console에서 설정)
 */
exports.onMessageCreated = functions
  .runWith({
    timeoutSeconds: 540,  // 9분 (최대값)
    memory: '256MB'
  })
  .database
  .ref('/rooms/{roomId}/messages/{messageId}')
  .onCreate(async (snapshot, context) => {
    const messageData = snapshot.val();
    const roomId = context.params.roomId;
    const messageId = context.params.messageId;
    
    // Skip if already translated (prevents infinite loop)
    if (messageData.translatedTexts) {
      console.log(`Message ${messageId} already has translations, skipping`);
      return null;
    }
    
    const originalText = messageData.originalText;
    const senderLanguage = messageData.senderLanguage || 'en';
    
    if (!originalText || originalText.trim() === '') {
      console.log(`Message ${messageId} has no text, skipping`);
      return null;
    }
    
    console.log(`🔄 Processing new message: "${originalText.substring(0, 30)}..." (${senderLanguage})`);
    
    try {
      console.log(`📋 Getting users for room: ${roomId}`);
      
      // Get all users in the room
      // Use snapshot.ref.parent to get room reference
      const roomRef = snapshot.ref.parent.parent;
      const usersRef = roomRef.child('users');
      
      console.log(`🔍 Reading from: ${usersRef.toString()}`);
      
      let users = {};
      try {
        const usersSnapshot = await usersRef.once('value');
        users = usersSnapshot.val() || {};
        console.log(`👥 Found ${Object.keys(users).length} users in room:`, Object.keys(users));
      } catch (dbError) {
        console.error(`❌ Database read error:`, dbError.message);
        // Fallback: use sender's language only
        console.log(`⚠️ Using fallback: only sender's language`);
        await snapshot.ref.child('translatedTexts').set({
          [senderLanguage]: originalText
        });
        return null;
      }
      
      const userLanguages = new Set();
      
      // Collect unique languages (excluding sender's language)
      Object.values(users).forEach(user => {
        if (user.language && user.language !== senderLanguage) {
          userLanguages.add(user.language);
        }
      });
      
      // Also include sender's language (for same-language users)
      userLanguages.add(senderLanguage);
      
      console.log(`📋 Translating to ${userLanguages.size} languages:`, Array.from(userLanguages));
      
      if (userLanguages.size === 0) {
        console.log(`⚠️ No languages to translate to, using original text only`);
        await snapshot.ref.child('translatedTexts').set({
          [senderLanguage]: originalText
        });
        return null;
      }
      
      // Translate to all languages in parallel with timeout
      const translationPromises = Array.from(userLanguages).map(async (targetLang) => {
        if (targetLang === senderLanguage) {
          // Same language - no translation needed
          return { lang: targetLang, text: originalText };
        }
        
        try {
          const translatedText = await Promise.race([
            translateText(originalText, targetLang, senderLanguage),
            new Promise((_, reject) => 
              setTimeout(() => reject(new Error('Translation timeout')), 15000)
            )
          ]);
          return { lang: targetLang, text: translatedText };
        } catch (error) {
          console.error(`❌ Translation failed for ${targetLang}:`, error.message);
          // Fallback to original text
          return { lang: targetLang, text: originalText };
        }
      });
      
      const translations = await Promise.all(translationPromises);
      
      // Build translatedTexts object
      const translatedTexts = {};
      translations.forEach(({ lang, text }) => {
        translatedTexts[lang] = text;
      });
      
      console.log(`✅ Translations ready:`, Object.keys(translatedTexts));
      
      // Update message with translations with timeout
      await Promise.race([
        snapshot.ref.child('translatedTexts').set(translatedTexts),
        new Promise((_, reject) => 
          setTimeout(() => reject(new Error('Database write timeout')), 10000)
        )
      ]);
      
      console.log(`✅ Translations complete for message ${messageId}:`, Object.keys(translatedTexts));
      
      return null;
    } catch (error) {
      console.error(`❌ Error processing message ${messageId}:`, error.message);
      console.error(`❌ Error stack:`, error.stack);
      
      // Try to update with at least original text to prevent retry loop
      try {
        await snapshot.ref.child('translatedTexts').set({
          [senderLanguage]: originalText
        });
      } catch (updateError) {
        console.error(`❌ Failed to update message:`, updateError.message);
      }
      
      return null;
    }
  });

/**
 * Cloud Function: STT v2 Recognize (Chirp 3) proxy
 *
 * Why: Speech-to-Text v2 requires IAM auth (OAuth/service account). API key calls from Android
 * will fail with `speech.recognizers.recognize` permission denied. This function runs with
 * the Cloud Functions service account (ADC) and calls STT v2 on behalf of the client.
 *
 * Docs:
 * - Chirp 3 API methods (v2 only): https://docs.cloud.google.com/speech-to-text/docs/models/chirp-3#api_methods
 * - V1 → V2 migration (recognizer + auto_decoding_config): https://docs.cloud.google.com/speech-to-text/docs/migration
 */
exports.sttRecognizeV2 = functions
  .runWith({
    timeoutSeconds: 60,
    memory: '256MB',
  })
  .https
  .onRequest(async (req, res) => {
    // CORS (simple, allow local testing)
    res.set('Access-Control-Allow-Origin', '*');
    res.set('Access-Control-Allow-Methods', 'POST, OPTIONS');
    res.set('Access-Control-Allow-Headers', 'Content-Type, Authorization');
    if (req.method === 'OPTIONS') {
      res.status(204).send('');
      return;
    }

    if (req.method !== 'POST') {
      res.status(405).json({ error: 'Method not allowed. Use POST.' });
      return;
    }

    try {
      const {
        audioContentBase64,
        languageCodes,
        model,
        location,
        projectId,
        sampleRateHertz,
      } = req.body || {};

      if (!audioContentBase64 || typeof audioContentBase64 !== 'string') {
        res.status(400).json({ error: 'Missing audioContentBase64 (base64 encoded LINEAR16).' });
        return;
      }

      const project = (projectId && String(projectId)) || process.env.GCLOUD_PROJECT || process.env.GCP_PROJECT;
      if (!project) {
        res.status(500).json({ error: 'Server misconfigured: missing project id (GCLOUD_PROJECT).' });
        return;
      }

      // Chirp 3 is available in specific regions; asia-northeast1 is GA and good for KR latency.
      const loc = (location && String(location)) || 'asia-northeast1';
      const endpoint = `https://${loc}-speech.googleapis.com/v2/projects/${project}/locations/${loc}/recognizers/_:recognize`;

      const langs = Array.isArray(languageCodes) && languageCodes.length > 0
        ? languageCodes.map(String)
        : ['ko-KR'];

      const sttModel = (model && String(model)) || 'chirp_3';
      const srHz = Number.isFinite(Number(sampleRateHertz)) ? Number(sampleRateHertz) : 8000;

      const body = {
        recognizer: `projects/${project}/locations/${loc}/recognizers/_`,
        config: {
          // IMPORTANT: We send raw PCM16 bytes (no container headers), so autoDecodingConfig can't infer.
          // Use explicitDecodingConfig for LINEAR16.
          explicitDecodingConfig: {
            encoding: 'LINEAR16',
            sampleRateHertz: srHz,
            audioChannelCount: 1,
          },
          languageCodes: langs,
          model: sttModel,
          features: {
            enableAutomaticPunctuation: true,
            profanityFilter: false,
          },
        },
        content: audioContentBase64,
      };

      // ADC token (Cloud Functions service account)
      const auth = new GoogleAuth({
        scopes: ['https://www.googleapis.com/auth/cloud-platform'],
      });
      const client = await auth.getClient();
      const tokenResponse = await client.getAccessToken();
      const accessToken = tokenResponse && tokenResponse.token ? tokenResponse.token : tokenResponse;

      if (!accessToken) {
        res.status(500).json({ error: 'Failed to obtain access token (ADC).' });
        return;
      }

      const apiResp = await fetch(endpoint, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${accessToken}`,
        },
        body: JSON.stringify(body),
      });

      const text = await apiResp.text();
      if (!apiResp.ok) {
        console.error('STT v2 error:', apiResp.status, text);
        res.status(apiResp.status).json({ error: 'stt_v2_error', status: apiResp.status, details: text });
        return;
      }

      const json = JSON.parse(text || '{}');
      const results = Array.isArray(json.results) ? json.results : [];

      let transcript = '';
      let detectedLanguageCode = null;
      if (results.length > 0) {
        for (const r of results) {
          if (!detectedLanguageCode && r.languageCode) detectedLanguageCode = r.languageCode;
          if (r.alternatives && r.alternatives.length > 0 && r.alternatives[0].transcript) {
            transcript += r.alternatives[0].transcript;
          }
        }
        transcript = transcript.trim();
      }

      res.status(200).json({
        transcript,
        languageCode: detectedLanguageCode,
        model: sttModel,
        location: loc,
      });
    } catch (e) {
      console.error('sttRecognizeV2 failed:', e && e.stack ? e.stack : e);
      res.status(500).json({ error: 'internal_error', message: String(e && e.message ? e.message : e) });
    }
  });

/**
 * Cloud Function: STT v2 Recognize (Chirp 3) via Realtime Database trigger
 *
 * Why: Some org policies forbid public HTTPS invocation (allUsers). A DB trigger avoids needing
 * Cloud Functions Invoker changes and still uses the function's service account IAM to call STT v2.
 *
 * Client writes:
 *   /sttRequests/{requestId} = { audioContentBase64, languageCodes[], model, location, createdAt }
 * Function writes:
 *   /sttResponses/{requestId} = { transcript, languageCode, model, location, error?, details? }
 */
exports.onSttRequestCreated = functions
  .runWith({
    timeoutSeconds: 60,
    memory: '512MB',
  })
  .database
  .ref('/sttRequests/{requestId}')
  .onCreate(async (snapshot, context) => {
    const requestId = context.params.requestId;
    const data = snapshot.val() || {};

    const audioContentBase64 = data.audioContentBase64;
    const languageCodes = Array.isArray(data.languageCodes) ? data.languageCodes : null;
    const model = typeof data.model === 'string' ? data.model : 'chirp_3';
    const location = typeof data.location === 'string' ? data.location : 'asia-northeast1';
    const sampleRateHertz = Number.isFinite(Number(data.sampleRateHertz)) ? Number(data.sampleRateHertz) : 8000;

    const respRef = admin.database().ref(`/sttResponses/${requestId}`);

    if (!audioContentBase64 || typeof audioContentBase64 !== 'string') {
      await respRef.set({ error: 'bad_request', details: 'Missing audioContentBase64' });
      return null;
    }

    const project = process.env.GCLOUD_PROJECT || process.env.GCP_PROJECT;
    if (!project) {
      await respRef.set({ error: 'server_misconfigured', details: 'Missing GCLOUD_PROJECT' });
      return null;
    }

    const langs = (languageCodes && languageCodes.length > 0)
      ? languageCodes.map(String)
      : ['ko-KR'];

    const endpoint = `https://${location}-speech.googleapis.com/v2/projects/${project}/locations/${location}/recognizers/_:recognize`;
    const body = {
      recognizer: `projects/${project}/locations/${location}/recognizers/_`,
      config: {
        // Raw PCM16 bytes (no headers) → must use explicitDecodingConfig.
        explicitDecodingConfig: {
          encoding: 'LINEAR16',
          sampleRateHertz,
          audioChannelCount: 1,
        },
        languageCodes: langs,
        model,
        features: {
          enableAutomaticPunctuation: true,
          profanityFilter: false,
        },
      },
      content: audioContentBase64,
    };

    try {
      const auth = new GoogleAuth({
        scopes: ['https://www.googleapis.com/auth/cloud-platform'],
      });
      const client = await auth.getClient();
      const tokenResponse = await client.getAccessToken();
      const accessToken = tokenResponse && tokenResponse.token ? tokenResponse.token : tokenResponse;

      if (!accessToken) {
        await respRef.set({ error: 'auth_error', details: 'Failed to obtain access token (ADC)' });
        return null;
      }

      const apiResp = await fetch(endpoint, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${accessToken}`,
        },
        body: JSON.stringify(body),
      });

      const text = await apiResp.text();
      if (!apiResp.ok) {
        console.error('STT v2 error:', apiResp.status, text);
        await respRef.set({ error: 'stt_v2_error', status: apiResp.status, details: text, model, location });
        return null;
      }

      const json = JSON.parse(text || '{}');
      const results = Array.isArray(json.results) ? json.results : [];

      let transcript = '';
      let detectedLanguageCode = null;
      if (results.length > 0) {
        for (const r of results) {
          if (!detectedLanguageCode && r.languageCode) detectedLanguageCode = r.languageCode;
          if (r.alternatives && r.alternatives.length > 0 && r.alternatives[0].transcript) {
            transcript += r.alternatives[0].transcript;
          }
        }
        transcript = transcript.trim();
      }

      await respRef.set({
        transcript,
        languageCode: detectedLanguageCode,
        model,
        location,
      });

      // Optional cleanup: keep requests small
      await snapshot.ref.remove();
      return null;
    } catch (e) {
      console.error('onSttRequestCreated failed:', e && e.stack ? e.stack : e);
      await respRef.set({ error: 'internal_error', details: String(e && e.message ? e.message : e), model, location });
      return null;
    }
  });
