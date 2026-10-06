const fs = require("fs");
const crypto = require("crypto");
const path = require("path");
const express = require("express");

const PORT = process.env.PORT || 3000;
const ADMIN_KEY = process.env.ADMIN_KEY || "change-me-to-something-long-and-random";
const DB_PATH = path.join(__dirname, "devices.json");
const RELEASES_DIR = path.join(__dirname, "releases");
const RELEASE_APK_PATH = path.join(RELEASES_DIR, "latest.apk");
const RELEASE_INFO_PATH = path.join(RELEASES_DIR, "version.json");

fs.mkdirSync(RELEASES_DIR, { recursive: true });

function loadReleaseInfo() {
  try {
    return JSON.parse(fs.readFileSync(RELEASE_INFO_PATH, "utf8"));
  } catch {
    return null;
  }
}

function loadDevices() {
  try {
    return JSON.parse(fs.readFileSync(DB_PATH, "utf8"));
  } catch {
    return {};
  }
}

function saveDevices(devices) {
  fs.writeFileSync(DB_PATH, JSON.stringify(devices, null, 2));
}

function requireAdmin(req, res, next) {
  if (req.get("x-admin-key") !== ADMIN_KEY) {
    return res.status(401).json({ error: "bad admin key" });
  }
  next();
}

const app = express();
app.use(express.json());

// Device polls this. No auth - the device_id itself is the only thing a
// stranger could spoof, and the worst they can do is read a lock state.
app.get("/status/:deviceId", (req, res) => {
  const devices = loadDevices();
  const id = req.params.deviceId;
  if (!devices[id]) {
    devices[id] = { locked: true, lastSeen: null };
  }
  devices[id].lastSeen = new Date().toISOString();
  saveDevices(devices);
  res.json({ locked: devices[id].locked });
});

app.post("/unlock/:deviceId", requireAdmin, (req, res) => {
  const devices = loadDevices();
  const id = req.params.deviceId;
  if (!devices[id]) devices[id] = { locked: true, lastSeen: null };
  devices[id].locked = false;
  saveDevices(devices);
  res.json({ ok: true, locked: false });
});

app.post("/lock/:deviceId", requireAdmin, (req, res) => {
  const devices = loadDevices();
  const id = req.params.deviceId;
  if (!devices[id]) devices[id] = { locked: true, lastSeen: null };
  devices[id].locked = true;
  saveDevices(devices);
  res.json({ ok: true, locked: true });
});

app.get("/devices", requireAdmin, (req, res) => {
  res.json(loadDevices());
});

// Remote app update. Push a new build:
//   curl -X POST "$SERVER/app/publish?versionCode=5" \
//        -H "x-admin-key: $ADMIN_KEY" \
//        -H "Content-Type: application/octet-stream" \
//        --data-binary @app-debug.apk
// Server computes the checksum itself (never trusts the caller for that),
// so devices always verify against what's actually on disk.
app.post(
  "/app/publish",
  requireAdmin,
  express.raw({ type: "*/*", limit: "150mb" }),
  (req, res) => {
    const versionCode = parseInt(req.query.versionCode, 10);
    if (!Number.isInteger(versionCode) || versionCode < 1) {
      return res.status(400).json({ error: "pass ?versionCode=<positive integer>" });
    }
    if (!req.body || req.body.length === 0) {
      return res.status(400).json({ error: "empty body - send the apk as raw binary" });
    }
    fs.writeFileSync(RELEASE_APK_PATH, req.body);
    const sha256 = crypto.createHash("sha256").update(req.body).digest("base64");
    const info = { versionCode, sha256, publishedAt: new Date().toISOString(), size: req.body.length };
    fs.writeFileSync(RELEASE_INFO_PATH, JSON.stringify(info, null, 2));
    res.json({ ok: true, ...info });
  }
);

// Device polls this to see if a newer build is available. No auth, same
// reasoning as /status - worst case a stranger learns the current version.
app.get("/app/latest", (req, res) => {
  const info = loadReleaseInfo();
  if (!info) return res.status(404).json({ error: "no release published yet" });
  res.json({
    versionCode: info.versionCode,
    sha256: info.sha256,
    url: `${req.protocol}://${req.get("host")}/app/latest.apk`,
  });
});

app.get("/app/latest.apk", (req, res) => {
  if (!fs.existsSync(RELEASE_APK_PATH)) return res.status(404).end();
  res.type("application/vnd.android.package-archive");
  res.sendFile(RELEASE_APK_PATH);
});

// Tiny admin page: open in a browser, paste the admin key once, lock/unlock
// any device by id. This is the "remote" in remote-unlock.
app.get("/", (req, res) => {
  res.type("html").send(`<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Device Locker Admin</title>
<style>
body{font-family:system-ui,sans-serif;max-width:480px;margin:24px auto;padding:0 16px;background:#111;color:#eee}
input{width:100%;padding:8px;margin:6px 0;background:#222;color:#eee;border:1px solid #444;border-radius:6px;box-sizing:border-box}
button{padding:8px 14px;margin:4px 4px 4px 0;border-radius:6px;border:none;cursor:pointer}
.lock{background:#a33}.unlock{background:#2a7}.refresh{background:#555;color:#eee}
.device{border:1px solid #333;border-radius:8px;padding:12px;margin:10px 0}
.state{font-weight:bold}
small{color:#888}
</style></head>
<body>
<h2>Device Locker</h2>
<input id="adminKey" placeholder="Admin key" type="password">
<button class="refresh" onclick="load()">Load devices</button>
<div id="list"></div>
<script>
const keyInput = document.getElementById('adminKey');
keyInput.value = localStorage.getItem('adminKey') || '';
async function load() {
  localStorage.setItem('adminKey', keyInput.value);
  const res = await fetch('/devices', { headers: { 'x-admin-key': keyInput.value } });
  if (!res.ok) { alert('bad admin key'); return; }
  const devices = await res.json();
  const list = document.getElementById('list');
  list.innerHTML = '';
  const ids = Object.keys(devices);
  if (ids.length === 0) { list.innerHTML = '<p><small>No devices have checked in yet.</small></p>'; return; }
  for (const id of ids) {
    const d = devices[id];
    const div = document.createElement('div');
    div.className = 'device';
    div.innerHTML = \`
      <div><b>\${id}</b> - <span class="state">\${d.locked ? 'LOCKED' : 'UNLOCKED'}</span></div>
      <div><small>last seen: \${d.lastSeen || 'never'}</small></div>
      <button class="unlock" onclick="setLock('\${id}', false)">Unlock</button>
      <button class="lock" onclick="setLock('\${id}', true)">Lock</button>
    \`;
    list.appendChild(div);
  }
}
async function setLock(id, locked) {
  await fetch('/' + (locked ? 'lock' : 'unlock') + '/' + id, {
    method: 'POST',
    headers: { 'x-admin-key': keyInput.value }
  });
  load();
}
if (keyInput.value) load();
</script>
</body></html>`);
});

app.listen(PORT, () => {
  console.log(`device-locker server listening on :${PORT}`);
});
