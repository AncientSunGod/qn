import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.DEFAULT_APP_PORT) || 3000;
const HOST = '0.0.0.0';

const APK_PATH = path.join(__dirname, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk');
const ICON_PATH = path.join(__dirname, 'app', 'src', 'main', 'ic_launcher-playstore.png');

const APP_TITLE = 'QuickNovel';
const APP_DESCRIPTION =
  'Browse, search, download as EPUB, and read web novels across multiple online sources with customizable reader themes, bionic reading, and text-to-speech.';

function formatFileSize(bytes) {
  if (!Number.isFinite(bytes) || bytes <= 0) return 'Unavailable';
  const mb = bytes / (1024 * 1024);
  return `${mb.toFixed(1)} MB`;
}

function getApkMeta() {
  try {
    const stats = fs.statSync(APK_PATH);
    if (!stats.isFile()) return null;
    return {
      sizeBytes: stats.size,
      sizeLabel: formatFileSize(stats.size),
      updatedIso: stats.mtime.toISOString(),
      updatedHuman: stats.mtime.toUTCString(),
    };
  } catch {
    return null;
  }
}

function renderHtmlPage() {
  const apkMeta = getApkMeta();
  const hasIcon = fs.existsSync(ICON_PATH);

  const downloadAction = apkMeta
    ? `<a class="btn-primary" href="/app-debug.apk" download="QuickNovel-debug.apk">Download Debug APK (${apkMeta.sizeLabel})</a>`
    : `<div class="notice">Debug APK not built yet. Run <code>./gradlew :app:assembleDebug</code> to generate <code>app/build/outputs/apk/debug/app-debug.apk</code>.</div>`;

  const updatedRow = apkMeta
    ? `<div class="meta-item"><span class="meta-label">Built</span><span class="meta-value">${apkMeta.updatedHuman}</span></div>`
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
    .btn-primary {
      display: inline-block;
      width: 100%;
      text-align: center;
      text-decoration: none;
      background: var(--accent);
      color: #ffffff;
      font-weight: 600;
      padding: 13px 18px;
      border-radius: 10px;
      transition: background 0.15s ease;
    }
    .btn-primary:hover {
      background: var(--accent-hover);
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
        <span class="meta-value">3.8.1-DEBUG (57)</span>
      </div>
      <div class="meta-item">
        <span class="meta-label">Compatibility</span>
        <span class="meta-value">Android 6.0+ (API 23–37)</span>
      </div>
      ${updatedRow}
    </div>
    ${downloadAction}
    <p class="hint">
      QuickNovel is a native Android application built with Gradle. Download the APK above to install and test on an Android device or emulator.
    </p>
  </main>
</body>
</html>`;
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`);
  const method = req.method || 'GET';

  if (method !== 'GET' && method !== 'HEAD') {
    res.writeHead(405, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('Method Not Allowed');
    return;
  }

  if (url.pathname === '/health') {
    const body = JSON.stringify({ ok: true, apkAvailable: Boolean(getApkMeta()) });
    res.writeHead(200, {
      'Content-Type': 'application/json; charset=utf-8',
      'Content-Length': Buffer.byteLength(body),
      'Cache-Control': 'no-store',
    });
    res.end(method === 'HEAD' ? undefined : body);
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
    fs.createReadStream(ICON_PATH).pipe(res);
    return;
  }

  if (url.pathname === '/app-debug.apk') {
    const apkMeta = getApkMeta();
    if (!apkMeta) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('APK not found. Run ./gradlew :app:assembleDebug first.');
      return;
    }
    res.writeHead(200, {
      'Content-Type': 'application/vnd.android.package-archive',
      'Content-Disposition': 'attachment; filename="QuickNovel-debug.apk"',
      'Content-Length': apkMeta.sizeBytes,
      'Cache-Control': 'no-store',
    });
    if (method === 'HEAD') {
      res.end();
      return;
    }
    fs.createReadStream(APK_PATH).pipe(res);
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
});

server.listen(PORT, HOST, () => {
  console.log(`QuickNovel APK preview server listening on http://${HOST}:${PORT}`);
});
