import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.DEFAULT_APP_PORT) || 3000;
const HOST = '0.0.0.0';

const CHUNK_SIZE_BYTES = 8 * 1024 * 1024; // 8 MiB per request (well below 32 MiB proxy response limit)
const MAX_CHUNK_BYTES = 16 * 1024 * 1024;

const APK_PATHS = [
  path.join(__dirname, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk'),
  path.join(__dirname, '.build-outputs', 'app-debug.apk'),
];
const ICON_PATH = path.join(__dirname, 'app', 'src', 'main', 'ic_launcher-playstore.png');
const FAVICON_PATHS = [
  path.join(__dirname, 'favicon.ico'),
  path.join(__dirname, 'public', 'favicon.ico'),
  path.join(__dirname, 'dist', 'favicon.ico'),
  ICON_PATH,
];

const APP_TITLE = 'QuickNovel';
const APP_DESCRIPTION =
  'Browse, search, download as EPUB, and read web novels across multiple online sources with customizable reader themes, bionic reading, text-to-speech, and full LNReader repository extensions.';

function formatFileSize(bytes) {
  if (!Number.isFinite(bytes) || bytes <= 0) return 'Unavailable';
  const mb = bytes / (1024 * 1024);
  return `${mb.toFixed(1)} MB`;
}

const APK_VALIDATION_CACHE = new Map();
const MAX_CD_BYTES = 32 * 1024 * 1024;

function validateApkZip(filePath, stats) {
  if (!stats || !stats.isFile() || stats.size < 52) return false;
  const cacheKey = `${filePath}:${stats.size}:${stats.mtimeMs}`;
  if (APK_VALIDATION_CACHE.has(cacheKey)) {
    return APK_VALIDATION_CACHE.get(cacheKey);
  }

  let isValid = false;
  let fd = null;
  try {
    fd = fs.openSync(filePath, 'r');

    // 1. Verify ZIP local file header signature (PK\x03\x04) at byte 0
    const sigBuf = Buffer.allocUnsafe(4);
    if (fs.readSync(fd, sigBuf, 0, 4, 0) !== 4) {
      return false;
    }
    if (sigBuf[0] !== 0x50 || sigBuf[1] !== 0x4b || sigBuf[2] !== 0x03 || sigBuf[3] !== 0x04) {
      return false;
    }

    // 2. Locate End of Central Directory (EOCD: PK\x05\x06) in trailing bytes
    const tailLen = Math.min(stats.size, 22 + 65535);
    const tailStart = stats.size - tailLen;
    const tailBuf = Buffer.allocUnsafe(tailLen);
    if (fs.readSync(fd, tailBuf, 0, tailLen, tailStart) !== tailLen) {
      return false;
    }

    let eocdRel = -1;
    for (let i = tailLen - 22; i >= 0; i--) {
      if (
        tailBuf[i] === 0x50 &&
        tailBuf[i + 1] === 0x4b &&
        tailBuf[i + 2] === 0x05 &&
        tailBuf[i + 3] === 0x06
      ) {
        const commentLen = tailBuf.readUInt16LE(i + 20);
        if (i + 22 + commentLen === tailLen) {
          eocdRel = i;
          break;
        }
      }
    }
    if (eocdRel < 0) return false;

    const eocdOffset = tailStart + eocdRel;
    const diskNumber = tailBuf.readUInt16LE(eocdRel + 4);
    const cdStartDisk = tailBuf.readUInt16LE(eocdRel + 6);
    const entriesOnDisk = tailBuf.readUInt16LE(eocdRel + 8);
    const totalEntries = tailBuf.readUInt16LE(eocdRel + 10);
    const cdSize = tailBuf.readUInt32LE(eocdRel + 12);
    const cdOffset = tailBuf.readUInt32LE(eocdRel + 16);

    if (
      diskNumber !== 0 ||
      cdStartDisk !== 0 ||
      entriesOnDisk !== totalEntries ||
      totalEntries === 0 ||
      cdSize === 0 ||
      cdSize > MAX_CD_BYTES ||
      cdOffset + cdSize > eocdOffset
    ) {
      return false;
    }

    // 3. Parse every Central Directory entry and confirm required APK components
    const cdBuf = Buffer.allocUnsafe(cdSize);
    if (fs.readSync(fd, cdBuf, 0, cdSize, cdOffset) !== cdSize) {
      return false;
    }

    let cursor = 0;
    let parsedEntries = 0;
    let hasManifest = false;
    let hasDex = false;

    while (cursor < cdSize) {
      if (cursor + 46 > cdSize) return false;
      if (
        cdBuf[cursor] !== 0x50 ||
        cdBuf[cursor + 1] !== 0x4b ||
        cdBuf[cursor + 2] !== 0x01 ||
        cdBuf[cursor + 3] !== 0x02
      ) {
        return false;
      }
      const compSize = cdBuf.readUInt32LE(cursor + 20);
      const nameLen = cdBuf.readUInt16LE(cursor + 28);
      const extraLen = cdBuf.readUInt16LE(cursor + 30);
      const commentLen = cdBuf.readUInt16LE(cursor + 32);
      const localHeaderOffset = cdBuf.readUInt32LE(cursor + 42);

      const recordLen = 46 + nameLen + extraLen + commentLen;
      if (cursor + recordLen > cdSize) return false;
      if (localHeaderOffset + 30 + nameLen + compSize > cdOffset) return false;

      const entryName = cdBuf.toString('utf8', cursor + 46, cursor + 46 + nameLen);
      if (entryName === 'AndroidManifest.xml') {
        hasManifest = true;
      } else if (/\.dex$/i.test(entryName)) {
        hasDex = true;
      }

      cursor += recordLen;
      parsedEntries += 1;
    }

    isValid = cursor === cdSize && parsedEntries === totalEntries && hasManifest && hasDex;
    return isValid;
  } catch {
    return false;
  } finally {
    if (fd !== null) {
      try {
        fs.closeSync(fd);
      } catch {
        // Ignore close error
      }
    }
    if (APK_VALIDATION_CACHE.size > 16) {
      APK_VALIDATION_CACHE.clear();
    }
    APK_VALIDATION_CACHE.set(cacheKey, isValid);
  }
}

function getApkMeta() {
  let newestValid = null;
  for (const candidate of APK_PATHS) {
    try {
      const stats = fs.statSync(candidate);
      if (!validateApkZip(candidate, stats)) continue;
      if (!newestValid || stats.mtimeMs > newestValid.mtimeMs) {
        newestValid = {
          filePath: candidate,
          mtimeMs: stats.mtimeMs,
          sizeBytes: stats.size,
          sizeLabel: formatFileSize(stats.size),
          updatedIso: stats.mtime.toISOString(),
          updatedHuman: stats.mtime.toUTCString(),
        };
      }
    } catch {
      // Ignore missing or unreadable candidate path
    }
  }
  return newestValid;
}

function resolveFaviconPath() {
  for (const candidate of FAVICON_PATHS) {
    try {
      if (fs.statSync(candidate).isFile()) return candidate;
    } catch {
      // Ignore missing candidate path
    }
  }
  return null;
}

function renderHtmlPage() {
  const apkMeta = getApkMeta();
  const hasIcon = fs.existsSync(ICON_PATH);

  const downloadAction = apkMeta
    ? `<div class="download-box">
        <button id="download-btn" class="btn-primary" type="button" data-size="${apkMeta.sizeBytes}">
          Download Debug APK (${apkMeta.sizeLabel})
        </button>
        <div id="progress-wrap" class="progress-wrap" hidden>
          <div class="progress-track">
            <div id="progress-fill" class="progress-fill"></div>
          </div>
          <div id="progress-status" class="progress-status">Preparing download…</div>
        </div>
        <div id="fallback-link-wrap" class="fallback-row">
          <span class="muted">Chunked browser transfer bypasses proxy size limits to deliver the complete ${apkMeta.sizeLabel} APK.</span>
        </div>
      </div>`
    : `<div class="notice">Debug APK not built yet. Run <code>./gradlew :app:assembleDebug</code> to generate <code>app/build/outputs/apk/debug/app-debug.apk</code>.</div>`;

  const updatedRow = apkMeta
    ? `<div class="meta-item"><span class="meta-label">Built</span><span class="meta-value">${apkMeta.updatedHuman}</span></div>`
    : '';

  const sizeRow = apkMeta
    ? `<div class="meta-item"><span class="meta-label">APK Size</span><span class="meta-value">${apkMeta.sizeLabel} (${apkMeta.sizeBytes.toLocaleString()} B)</span></div>`
    : '';

  return `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>${APP_TITLE}</title>
  <meta name="description" content="${APP_DESCRIPTION}" />
  <meta property="og:title" content="${APP_TITLE}" />
  <meta property="og:description" content="${APP_DESCRIPTION}" />
  <style>
    :root {
      color-scheme: dark;
      --bg: #0f1115;
      --surface: #171a21;
      --border: #262b36;
      --text: #eef1f6;
      --muted: #9aa4b5;
      --accent: #5b8cff;
      --accent-hover: #4779f0;
      --error: #ff6b6b;
      --success: #48e484;
    }
    * { box-sizing: border-box; }
    body {
      margin: 0;
      min-height: 100vh;
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
      background: radial-gradient(circle at top, #181d29 0%, var(--bg) 60%);
      color: var(--text);
      display: grid;
      place-items: center;
      padding: 24px;
    }
    main {
      width: 100%;
      max-width: 640px;
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: 16px;
      padding: 28px;
      box-shadow: 0 20px 50px rgba(0, 0, 0, 0.45);
    }
    .header {
      display: flex;
      align-items: center;
      gap: 16px;
      margin-bottom: 16px;
    }
    .icon {
      width: 64px;
      height: 64px;
      border-radius: 14px;
      object-fit: cover;
      border: 1px solid var(--border);
      flex-shrink: 0;
    }
    h1 {
      margin: 0 0 4px;
      font-size: 1.65rem;
      letter-spacing: -0.02em;
    }
    .subtitle {
      margin: 0;
      color: var(--muted);
      font-size: 0.92rem;
    }
    .description {
      margin: 0 0 22px;
      color: #cfd6e3;
      line-height: 1.55;
      font-size: 0.96rem;
    }
    .meta-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
      gap: 10px;
      margin-bottom: 24px;
    }
    .meta-item {
      background: rgba(255, 255, 255, 0.02);
      border: 1px solid var(--border);
      border-radius: 10px;
      padding: 10px 12px;
    }
    .meta-label {
      display: block;
      font-size: 0.74rem;
      text-transform: uppercase;
      letter-spacing: 0.06em;
      color: var(--muted);
      margin-bottom: 4px;
    }
    .meta-value {
      font-size: 0.9rem;
      word-break: break-word;
    }
    .download-box {
      display: flex;
      flex-direction: column;
      gap: 10px;
    }
    .btn-primary {
      display: inline-block;
      width: 100%;
      border: none;
      cursor: pointer;
      font-family: inherit;
      font-size: 0.98rem;
      text-align: center;
      text-decoration: none;
      background: var(--accent);
      color: #ffffff;
      font-weight: 600;
      padding: 13px 18px;
      border-radius: 10px;
      transition: background 0.15s ease, opacity 0.15s ease;
    }
    .btn-primary:hover:not(:disabled) {
      background: var(--accent-hover);
    }
    .btn-primary:disabled {
      opacity: 0.72;
      cursor: progress;
    }
    .progress-wrap {
      background: rgba(255, 255, 255, 0.03);
      border: 1px solid var(--border);
      border-radius: 10px;
      padding: 10px 12px;
    }
    .progress-track {
      height: 8px;
      width: 100%;
      background: rgba(255, 255, 255, 0.08);
      border-radius: 999px;
      overflow: hidden;
      margin-bottom: 6px;
    }
    .progress-fill {
      height: 100%;
      width: 0%;
      background: var(--accent);
      transition: width 0.12s linear;
    }
    .progress-status {
      font-size: 0.84rem;
      color: var(--muted);
    }
    .progress-status.error { color: var(--error); }
    .progress-status.success { color: var(--success); }
    .fallback-row {
      font-size: 0.82rem;
      color: var(--muted);
    }
    .notice {
      padding: 14px;
      border-radius: 10px;
      border: 1px solid var(--border);
      background: rgba(255, 255, 255, 0.03);
      color: var(--muted);
      font-size: 0.9rem;
      line-height: 1.5;
    }
    code {
      font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
      font-size: 0.85em;
      color: var(--text);
    }
    .hint {
      margin: 16px 0 0;
      color: var(--muted);
      font-size: 0.84rem;
      line-height: 1.45;
    }
  </style>
</head>
<body>
  <main>
    <div class="header">
      ${hasIcon ? '<img class="icon" src="/icon.png" alt="QuickNovel icon" />' : ''}
      <div>
        <h1>${APP_TITLE}</h1>
        <p class="subtitle">Native Android Novel Reader (APK Build)</p>
      </div>
    </div>
    <p class="description">${APP_DESCRIPTION}</p>
    <div class="meta-grid">
      <div class="meta-item">
        <span class="meta-label">Package ID</span>
        <span class="meta-value">com.lagradost.quicknovel.debug</span>
      </div>
      <div class="meta-item">
        <span class="meta-label">Version</span>
        <span class="meta-value">3.9.1-DEBUG (57)</span>
      </div>
      <div class="meta-item">
        <span class="meta-label">Compatibility</span>
        <span class="meta-value">Android 6.0+ (API 23–37)</span>
      </div>
      ${sizeRow}
      ${updatedRow}
    </div>
    ${downloadAction}
    <p class="hint">
      QuickNovel is a native Android application built with Gradle. Download the APK above to install and test on an Android device or emulator.
    </p>
  </main>
  <script>
    (function () {
      const btn = document.getElementById('download-btn');
      if (!btn) return;
      const progressWrap = document.getElementById('progress-wrap');
      const progressFill = document.getElementById('progress-fill');
      const progressStatus = document.getElementById('progress-status');

      function setStatus(text, stateClass) {
        progressWrap.hidden = false;
        progressStatus.textContent = text;
        progressStatus.className = 'progress-status' + (stateClass ? ' ' + stateClass : '');
      }

      async function downloadChunkedApk() {
        btn.disabled = true;
        progressFill.style.width = '0%';
        setStatus('Checking APK metadata…', '');

        try {
          const infoRes = await fetch('/apk-info', { cache: 'no-store' });
          if (!infoRes.ok) throw new Error('Could not read APK metadata (HTTP ' + infoRes.status + ')');
          const info = await infoRes.json();
          if (!info.available || !info.sizeBytes) {
            throw new Error('APK file is not available on the server.');
          }

          const totalBytes = info.sizeBytes;
          const chunkSize = info.chunkSize || ${CHUNK_SIZE_BYTES};
          const parts = [];
          let received = 0;

          while (received < totalBytes) {
            const length = Math.min(chunkSize, totalBytes - received);
            const chunkUrl = '/apk-chunk?offset=' + received + '&length=' + length + '&t=' + Date.now();
            const res = await fetch(chunkUrl, { cache: 'no-store' });
            if (!res.ok) {
              throw new Error('Chunk transfer failed at byte ' + received + ' (HTTP ' + res.status + ')');
            }
            const buf = await res.arrayBuffer();
            if (buf.byteLength !== length) {
              throw new Error('Incomplete chunk received (' + buf.byteLength + ' of ' + length + ' bytes).');
            }
            if (received === 0) {
              const header = new Uint8Array(buf, 0, Math.min(4, buf.byteLength));
              if (header[0] !== 0x50 || header[1] !== 0x4b) {
                throw new Error('Downloaded header is not a valid Android APK archive.');
              }
            }
            parts.push(buf);
            received += buf.byteLength;
            const pct = Math.min(100, Math.round((received / totalBytes) * 100));
            const recvMb = (received / (1024 * 1024)).toFixed(1);
            const totalMb = (totalBytes / (1024 * 1024)).toFixed(1);
            progressFill.style.width = pct + '%';
            setStatus('Downloading: ' + recvMb + ' / ' + totalMb + ' MB (' + pct + '%)', '');
          }

          const blob = new Blob(parts, { type: 'application/vnd.android.package-archive' });
          if (blob.size !== totalBytes) {
            throw new Error('Assembled APK size mismatch (' + blob.size + ' vs ' + totalBytes + ' bytes).');
          }

          const objectUrl = URL.createObjectURL(blob);
          const link = document.createElement('a');
          link.href = objectUrl;
          link.download = info.filename || 'QuickNovel-debug.apk';
          document.body.appendChild(link);
          link.click();
          link.remove();
          setTimeout(() => URL.revokeObjectURL(objectUrl), 60_000);

          setStatus('Complete! Saved ' + (blob.size / (1024 * 1024)).toFixed(1) + ' MB APK.', 'success');
        } catch (err) {
          setStatus('Download error: ' + (err && err.message ? err.message : String(err)), 'error');
        } finally {
          btn.disabled = false;
        }
      }

      btn.addEventListener('click', downloadChunkedApk);
    })();
  </script>
</body>
</html>`;
}

function parseByteRange(rangeHeader, totalSize) {
  if (!rangeHeader || typeof rangeHeader !== 'string') return null;
  const match = /^bytes=(\d*)-(\d*)$/i.exec(rangeHeader.trim());
  if (!match) return { invalid: true };
  const startStr = match[1];
  const endStr = match[2];
  let start = 0;
  let end = totalSize - 1;
  if (startStr === '' && endStr === '') return { invalid: true };
  if (startStr === '') {
    const suffixLength = Number(endStr);
    if (!Number.isFinite(suffixLength) || suffixLength <= 0) return { invalid: true };
    start = Math.max(0, totalSize - suffixLength);
    end = totalSize - 1;
  } else {
    start = Number(startStr);
    end = endStr === '' ? totalSize - 1 : Math.min(totalSize - 1, Number(endStr));
  }
  if (!Number.isFinite(start) || !Number.isFinite(end) || start < 0 || start >= totalSize || end < start) {
    return { invalid: true };
  }
  return { start, end };
}

function pipeFile(filePath, options, res) {
  const stream = fs.createReadStream(filePath, options);
  stream.on('error', () => {
    if (!res.headersSent) {
      res.writeHead(500, { 'Content-Type': 'text/plain; charset=utf-8' });
    }
    if (!res.writableEnded) {
      res.end('Internal Server Error');
    }
  });
  res.on('close', () => {
    if (!stream.destroyed) stream.destroy();
  });
  stream.pipe(res);
}

const server = http.createServer((req, res) => {
  try {
    const url = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`);
    const method = req.method || 'GET';

    if (method !== 'GET' && method !== 'HEAD') {
      res.writeHead(405, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('Method Not Allowed');
      return;
    }

    if (url.pathname === '/health') {
      const meta = getApkMeta();
      const body = JSON.stringify({
        ok: true,
        apkAvailable: Boolean(meta),
        apkSizeBytes: meta ? meta.sizeBytes : 0,
      });
      res.writeHead(200, {
        'Content-Type': 'application/json; charset=utf-8',
        'Content-Length': Buffer.byteLength(body),
        'Cache-Control': 'no-store',
      });
      res.end(method === 'HEAD' ? undefined : body);
      return;
    }

    if (url.pathname === '/apk-info') {
      const meta = getApkMeta();
      const payload = meta
        ? {
            available: true,
            filename: 'QuickNovel-debug.apk',
            sizeBytes: meta.sizeBytes,
            sizeLabel: meta.sizeLabel,
            updatedIso: meta.updatedIso,
            updatedHuman: meta.updatedHuman,
            chunkSize: CHUNK_SIZE_BYTES,
          }
        : {
            available: false,
            filename: 'QuickNovel-debug.apk',
            sizeBytes: 0,
            sizeLabel: 'Unavailable',
            chunkSize: CHUNK_SIZE_BYTES,
          };
      const body = JSON.stringify(payload);
      res.writeHead(200, {
        'Content-Type': 'application/json; charset=utf-8',
        'Content-Length': Buffer.byteLength(body),
        'Cache-Control': 'no-store',
      });
      res.end(method === 'HEAD' ? undefined : body);
      return;
    }

    if (url.pathname === '/apk-chunk') {
      const meta = getApkMeta();
      if (!meta) {
        res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
        res.end('APK not found.');
        return;
      }
      const offset = Number(url.searchParams.get('offset') ?? '0');
      const requestedLength = Number(url.searchParams.get('length') ?? String(CHUNK_SIZE_BYTES));
      if (
        !Number.isInteger(offset) ||
        !Number.isInteger(requestedLength) ||
        offset < 0 ||
        offset >= meta.sizeBytes ||
        requestedLength <= 0 ||
        requestedLength > MAX_CHUNK_BYTES
      ) {
        res.writeHead(400, { 'Content-Type': 'text/plain; charset=utf-8' });
        res.end('Invalid chunk offset or length.');
        return;
      }
      const clampedLength = Math.min(requestedLength, meta.sizeBytes - offset);
      res.writeHead(200, {
        'Content-Type': 'application/octet-stream',
        'Content-Length': clampedLength,
        'Cache-Control': 'no-store',
        'X-APK-Offset': String(offset),
        'X-APK-Total-Size': String(meta.sizeBytes),
      });
      if (method === 'HEAD') {
        res.end();
        return;
      }
      pipeFile(meta.filePath, { start: offset, end: offset + clampedLength - 1 }, res);
      return;
    }

    if (url.pathname === '/favicon.ico') {
      const faviconPath = resolveFaviconPath();
      if (!faviconPath) {
        res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
        res.end('Not Found');
        return;
      }
      const stat = fs.statSync(faviconPath);
      res.writeHead(200, {
        'Content-Type': faviconPath.endsWith('.png') ? 'image/png' : 'image/x-icon',
        'Content-Length': stat.size,
        'Cache-Control': 'public, max-age=3600',
      });
      if (method === 'HEAD') {
        res.end();
        return;
      }
      pipeFile(faviconPath, undefined, res);
      return;
    }

    if (url.pathname === '/icon.png') {
      if (!fs.existsSync(ICON_PATH)) {
        res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
        res.end('Not Found');
        return;
      }
      const stat = fs.statSync(ICON_PATH);
      res.writeHead(200, {
        'Content-Type': 'image/png',
        'Content-Length': stat.size,
        'Cache-Control': 'public, max-age=3600',
      });
      if (method === 'HEAD') {
        res.end();
        return;
      }
      pipeFile(ICON_PATH, undefined, res);
      return;
    }

    if (url.pathname === '/app-debug.apk') {
      const apkMeta = getApkMeta();
      if (!apkMeta) {
        res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
        res.end('APK not found. Run ./gradlew :app:assembleDebug first.');
        return;
      }
      const range = parseByteRange(req.headers.range, apkMeta.sizeBytes);
      if (range && range.invalid) {
        res.writeHead(416, {
          'Content-Range': `bytes */${apkMeta.sizeBytes}`,
          'Content-Type': 'text/plain; charset=utf-8',
        });
        res.end('Range Not Satisfiable');
        return;
      }
      if (range) {
        const length = range.end - range.start + 1;
        res.writeHead(206, {
          'Content-Type': 'application/vnd.android.package-archive',
          'Content-Disposition': 'attachment; filename="QuickNovel-debug.apk"',
          'Accept-Ranges': 'bytes',
          'Content-Range': `bytes ${range.start}-${range.end}/${apkMeta.sizeBytes}`,
          'Content-Length': length,
          'Cache-Control': 'no-store',
        });
        if (method === 'HEAD') {
          res.end();
          return;
        }
        pipeFile(apkMeta.filePath, { start: range.start, end: range.end }, res);
        return;
      }
      res.writeHead(200, {
        'Content-Type': 'application/vnd.android.package-archive',
        'Content-Disposition': 'attachment; filename="QuickNovel-debug.apk"',
        'Accept-Ranges': 'bytes',
        'Content-Length': apkMeta.sizeBytes,
        'Cache-Control': 'no-store',
      });
      if (method === 'HEAD') {
        res.end();
        return;
      }
      pipeFile(apkMeta.filePath, undefined, res);
      return;
    }

    if (url.pathname === '/' || url.pathname === '/index.html') {
      const html = renderHtmlPage();
      res.writeHead(200, {
        'Content-Type': 'text/html; charset=utf-8',
        'Content-Length': Buffer.byteLength(html),
        'Cache-Control': 'no-store',
      });
      res.end(method === 'HEAD' ? undefined : html);
      return;
    }

    res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('Not Found');
  } catch (err) {
    if (!res.headersSent) {
      res.writeHead(400, { 'Content-Type': 'text/plain; charset=utf-8' });
    }
    if (!res.writableEnded) {
      res.end('Bad Request');
    }
  }
});

server.on('error', (err) => {
  console.error('Preview server error:', err);
  process.exit(1);
});

server.listen(PORT, HOST, () => {
  console.log(`QuickNovel APK preview server listening on http://${HOST}:${PORT}`);
});
