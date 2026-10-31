#!/bin/bash
# Esegue il test end-to-end sull'emulatore e recupera i risultati.
# Le chiavi arrivano dalle variabili d'ambiente (secrets di GitHub), mai dal codice.
set -u
APP=app/build/outputs/apk/debug/app-debug.apk
TEST=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
mkdir -p e2e-out

adb wait-for-device
adb install -r "$APP"
adb install -r "$TEST"
adb logcat -c

adb shell am instrument -w -r \
  -e GEMINI_API_KEY "${GEMINI_API_KEY:-none}" \
  -e GROQ_API_KEY "${GROQ_API_KEY:-none}" \
  -e PEXELS_API_KEY "${PEXELS_API_KEY:-none}" \
  -e CF_ACCOUNT_ID "${CF_ACCOUNT_ID:-none}" \
  -e CF_API_TOKEN "${CF_API_TOKEN:-none}" \
  -e class it.shortsgen.app.EndToEndTest \
  it.shortsgen.app.test/androidx.test.runner.AndroidJUnitRunner > e2e-out/instrument.txt 2>&1
cat e2e-out/instrument.txt | tail -60

adb pull /sdcard/Android/data/it.shortsgen.app/files/e2e/. e2e-out/ || true
adb logcat -d -v time -s EndToEndTest:* GenerationPipeline:* PexelsProvider:* VideoComposer:* AndroidRuntime:* TransformerInternal:* > e2e-out/logcat.txt || true
ls -la e2e-out
echo "----- report -----"
cat e2e-out/report.txt 2>/dev/null || echo "report.txt non prodotto"

grep -q "OK (1 test)" e2e-out/instrument.txt
