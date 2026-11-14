'use strict';
/* EZ Note server: accounts (passkeys + passwords) and note/file sync. Pure Node, no dependencies. */
const http = require('http'), crypto = require('crypto'), fs = require('fs'), path = require('path');

const b64u = b => Buffer.from(b).toString('base64url');
const unb64u = s => Buffer.from(String(s), 'base64url');
const sha256 = b => crypto.createHash('sha256').update(b).digest();
const androidOrigin = fp => 'android:apk-key-hash:' + b64u(Buffer.from(fp.replace(/:/g, ''), 'hex'));
class HttpError extends Error { constructor(code, msg) { super(msg); this.code = code; } }

/* ---- minimal CBOR reader (enough for WebAuthn attestation objects and COSE keys) ---- */
function cbor(buf, off = 0) {
  const b = buf[off++], mt = b >> 5, ai = b & 31; let n;
  if (ai < 24) n = ai; else if (ai === 24) { n = buf[off]; off += 1; }
  else if (ai === 25) { n = buf.readUInt16BE(off); off += 2; }
  else if (ai === 26) { n = buf.readUInt32BE(off); off += 4; }
  else if (ai === 27) { n = Number(buf.readBigUInt64BE(off)); off += 8; }
  else throw new Error('unsupported CBOR');
  switch (mt) {
    case 0: return [n, off];
    case 1: return [-1 - n, off];
    case 2: return [buf.subarray(off, off + n), off + n];
    case 3: return [buf.toString('utf8', off, off + n), off + n];
    case 4: { const a = []; for (let i = 0; i < n; i++) { const [v, o] = cbor(buf, off); a.push(v); off = o; } return [a, off]; }
    case 5: { const m = new Map(); for (let i = 0; i < n; i++) { const [k, o1] = cbor(buf, off), [v, o2] = cbor(buf, o1); m.set(k, v); off = o2; } return [m, off]; }
    case 6: return cbor(buf, off);
    default: return [ai === 20 ? false : ai === 21 ? true : null, off];
  }
}

function parseAuthData(buf) {
  if (buf.length < 37) throw new HttpError(400, 'Bad authenticator data');
  const o = { rpIdHash: buf.subarray(0, 32), flags: buf[32], signCount: buf.readUInt32BE(33) };
  if (o.flags & 0x40) {
    const len = buf.readUInt16BE(53);
    o.credId = buf.subarray(55, 55 + len);
    o.cose = cbor(buf, 55 + len)[0];
  }
  return o;
}
function coseToJwk(m) {
  const kty = m.get(1);
  if (kty === 2 && m.get(-1) === 1) return { kty: 'EC', crv: 'P-256', x: b64u(m.get(-2)), y: b64u(m.get(-3)) };
  if (kty === 3) return { kty: 'RSA', n: b64u(m.get(-1)), e: b64u(m.get(-2)) };
  throw new HttpError(400, 'Unsupported passkey type');
}

function createApp(cfg) {
  cfg = Object.assign({ rpId: 'localhost', rpName: 'EZ Note', dataDir: path.join(__dirname, 'data'), pkg: 'com.example.notes', fps: [], webOrigins: [], secret: '', trustProxy: false }, cfg);
  fs.mkdirSync(path.join(cfg.dataDir, 'data'), { recursive: true });
  fs.mkdirSync(path.join(cfg.dataDir, 'files'), { recursive: true });
  const dbFile = path.join(cfg.dataDir, 'users.json');
  let db = { users: {}, names: {}, cred: {} };
  try { db = JSON.parse(fs.readFileSync(dbFile, 'utf8')); } catch (e) { /* first run */ }
  const saveDb = () => { const t = dbFile + '.tmp'; fs.writeFileSync(t, JSON.stringify(db)); fs.renameSync(t, dbFile); };
  let secret = cfg.secret;
  if (!secret) { const f = path.join(cfg.dataDir, 'secret.key'); try { secret = fs.readFileSync(f, 'utf8'); } catch (e) { secret = crypto.randomBytes(32).toString('hex'); fs.writeFileSync(f, secret, { mode: 0o600 }); } }
  const origins = new Set([`https://${cfg.rpId}`, ...cfg.webOrigins, ...cfg.fps.map(androidOrigin)]);

  /* ---- sessions ---- */
  const sign = p => crypto.createHmac('sha256', secret).update(p).digest('base64url');
  const mint = u => { const p = b64u(JSON.stringify({ u: u.id, exp: Date.now() + 90 * 864e5 })); return p + '.' + sign(p); };
  function authUser(req) {
    const m = /^Bearer ([\w-]+)\.([\w-]+)$/.exec(req.headers.authorization || ''); if (!m) return null;
    const a = Buffer.from(sign(m[1])), b = Buffer.from(m[2]);
    if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) return null;
    try { const t = JSON.parse(unb64u(m[1])); return t.exp > Date.now() ? db.users[t.u] || null : null; } catch (e) { return null; }
  }
  const need = req => authUser(req) || (() => { throw new HttpError(401, 'Please sign in again'); })();

  /* ---- one-time challenges ---- */
  const chal = new Map();
  const newChal = info => { for (const [k, v] of chal) if (v.exp < Date.now()) chal.delete(k); const c = b64u(crypto.randomBytes(32)); chal.set(c, { ...info, exp: Date.now() + 5 * 60e3 }); return c; };
  const takeChal = c => { const v = chal.get(c); chal.delete(c); return v && v.exp > Date.now() ? v : null; };

  function checkClient(credential, type, kind) {
    const raw = unb64u(credential.response.clientDataJSON); let cd;
    try { cd = JSON.parse(raw.toString('utf8')); } catch (e) { throw new HttpError(400, 'Bad client data'); }
    const ch = takeChal(cd.challenge);
    if (cd.type !== type || !ch || ch.t !== kind) throw new HttpError(400, 'That request expired — try again');
    if (!origins.has(cd.origin)) throw new HttpError(400, 'This app isn’t registered with the server (origin ' + cd.origin + ')');
    return { raw, ch };
  }
  const checkAuthData = (ad, needAttested) => {
    const p = parseAuthData(ad);
    if (!p.rpIdHash.equals(sha256(cfg.rpId))) throw new HttpError(400, 'Wrong relying party');
    if (!(p.flags & 1) || !(p.flags & 4)) throw new HttpError(400, 'Passkey must be unlocked with your screen lock or biometrics');
    if (needAttested && !p.credId) throw new HttpError(400, 'No credential in response');
    return p;
  };
  const normName = s => String(s || '').trim().toLowerCase();
  const validName = s => /^[a-z0-9._@+-]{3,64}$/.test(s);
  const credOpts = (user) => (user ? user.creds : []).map(c => ({ type: 'public-key', id: c.id }));

  /* ---- sync state ---- */
  const stateFile = u => path.join(cfg.dataDir, 'data', u.id + '.json');
  const readState = u => { try { return JSON.parse(fs.readFileSync(stateFile(u), 'utf8')); } catch (e) { return { g: [], cats: [], notes: [] }; } };
  function merge(a, b) {
    const gone = new Set([...(a.g || []), ...(b.g || [])]), cats = [...(a.cats || [])], notes = new Map();
    for (const c of b.cats || []) if (!cats.includes(c)) cats.push(c);
    for (const n of [...(a.notes || []), ...(b.notes || [])]) { if (!n || typeof n.id !== 'string' || gone.has(n.id)) continue; const o = notes.get(n.id); if (!o || (n.u || 0) > (o.u || 0)) notes.set(n.id, n); }
    return { g: [...gone], cats, notes: [...notes.values()] };
  }
  const userDir = u => path.join(cfg.dataDir, 'files', u.id);
  const fileName = n => { if (!/^[A-Za-z0-9._-]{1,200}$/.test(n) || n.startsWith('.')) throw new HttpError(400, 'Bad file name'); return n; };

  /* ---- http plumbing ---- */
  const hits = new Map();
  function limit(req) {
    const ip = (cfg.trustProxy && String(req.headers['x-forwarded-for'] || '').split(',')[0].trim()) || req.socket.remoteAddress, k = ip + ':' + Math.floor(Date.now() / 6e4);
    const n = (hits.get(k) || 0) + 1; hits.set(k, n); if (hits.size > 5000) hits.clear();
    if (n > 40) throw new HttpError(429, 'Too many attempts — wait a minute');
  }
  const readBody = (req, max) => new Promise((res, rej) => {
    const parts = []; let n = 0;
    req.on('data', c => { n += c.length; if (n > max) { rej(new HttpError(413, 'Too large')); req.destroy(); } else parts.push(c); });
    req.on('end', () => res(Buffer.concat(parts))); req.on('error', rej);
  });
  const readJson = async (req, max = 5e6) => { try { return JSON.parse((await readBody(req, max)).toString('utf8') || '{}'); } catch (e) { if (e instanceof HttpError) throw e; throw new HttpError(400, 'Bad JSON'); } };
  const send = (res, code, obj, type) => { const body = Buffer.isBuffer(obj) ? obj : Buffer.from(JSON.stringify(obj)); res.writeHead(code, { 'Content-Type': type || 'application/json', 'Cache-Control': 'no-store' }); res.end(body); };

  const routes = {
    'POST /api/register/options': async req => {
      limit(req); const { username } = await readJson(req), name = normName(username);
      if (!validName(name)) throw new HttpError(400, 'Usernames are 3–64 letters, numbers or . _ @ + -');
      const existing = db.users[db.names[name]];
      if (existing && (authUser(req) || {}).id !== existing.id) throw new HttpError(409, 'That username is taken');
      const uid = existing ? existing.id : crypto.randomBytes(16).toString('hex');
      return { challenge: newChal({ t: 'reg', name }), rp: { name: cfg.rpName, id: cfg.rpId }, user: { id: b64u(Buffer.from(uid, 'hex')), name, displayName: name },
        pubKeyCredParams: [{ type: 'public-key', alg: -7 }, { type: 'public-key', alg: -257 }], timeout: 300000, attestation: 'none',
        authenticatorSelection: { residentKey: 'required', requireResidentKey: true, userVerification: 'required' }, excludeCredentials: credOpts(existing) };
    },
    'POST /api/register/verify': async req => {
      limit(req); const { username, credential } = await readJson(req), name = normName(username);
      const { ch } = checkClient(credential, 'webauthn.create', 'reg');
      if (ch.name !== name) throw new HttpError(400, 'Username changed');
      const att = cbor(unb64u(credential.response.attestationObject))[0], p = checkAuthData(att.get('authData'), true);
      if (b64u(p.credId) !== credential.id) throw new HttpError(400, 'Credential mismatch');
      const jwk = coseToJwk(p.cose); crypto.createPublicKey({ key: jwk, format: 'jwk' });
      if (db.cred[credential.id]) throw new HttpError(409, 'That passkey is already registered');
      let user = db.users[db.names[name]];
      if (user) { if ((authUser(req) || {}).id !== user.id) throw new HttpError(409, 'That username is taken'); }
      else { user = { id: crypto.randomBytes(16).toString('hex'), username: name, created: Date.now(), creds: [] }; db.users[user.id] = user; db.names[name] = user.id; }
      user.creds.push({ id: credential.id, jwk, signCount: p.signCount, created: Date.now() }); db.cred[credential.id] = user.id; saveDb();
      return { token: mint(user), username: user.username };
    },
    'POST /api/login/options': async req => { limit(req); return { challenge: newChal({ t: 'auth' }), rpId: cfg.rpId, timeout: 300000, userVerification: 'required', allowCredentials: [] }; },
    'POST /api/login/verify': async req => {
      limit(req); const { credential } = await readJson(req);
      const user = db.users[db.cred[credential.id]], cred = user && user.creds.find(c => c.id === credential.id);
      const { raw } = checkClient(credential, 'webauthn.get', 'auth');
      if (!cred) throw new HttpError(401, 'No account for that passkey');
      const ad = unb64u(credential.response.authenticatorData), p = checkAuthData(ad, false);
      const ok = crypto.verify('sha256', Buffer.concat([ad, sha256(raw)]), crypto.createPublicKey({ key: cred.jwk, format: 'jwk' }), unb64u(credential.response.signature));
      if (!ok) throw new HttpError(401, 'Passkey check failed');
      if ((p.signCount || cred.signCount) && p.signCount <= cred.signCount) throw new HttpError(401, 'Passkey counter went backwards');
      cred.signCount = p.signCount; saveDb();
      return { token: mint(user), username: user.username };
    },
    'POST /api/password/register': async req => {
      limit(req); const { username, password } = await readJson(req), name = normName(username);
      if (!validName(name)) throw new HttpError(400, 'Usernames are 3–64 letters, numbers or . _ @ + -');
      if (String(password || '').length < 8) throw new HttpError(400, 'Use at least 8 characters for the password');
      if (db.names[name]) throw new HttpError(409, 'That username is taken');
      const salt = crypto.randomBytes(16), user = { id: crypto.randomBytes(16).toString('hex'), username: name, created: Date.now(), creds: [], pw: { salt: b64u(salt), hash: b64u(crypto.scryptSync(password, salt, 32)) } };
      db.users[user.id] = user; db.names[name] = user.id; saveDb(); return { token: mint(user), username: name };
    },
    'POST /api/password/login': async req => {
      limit(req); const { username, password } = await readJson(req), user = db.users[db.names[normName(username)]];
      const bad = new HttpError(401, 'Wrong username or password');
      if (!user || !user.pw) { crypto.scryptSync(String(password), 'x', 32); throw bad; }
      const h = crypto.scryptSync(String(password), unb64u(user.pw.salt), 32);
      if (!crypto.timingSafeEqual(h, unb64u(user.pw.hash))) throw bad;
      return { token: mint(user), username: user.username };
    },
    'POST /api/sync': async req => { const u = need(req), body = await readJson(req); const m = merge(readState(u), body); fs.writeFileSync(stateFile(u), JSON.stringify(m)); return m; },
    'GET /api/files': async req => { const u = need(req); try { return fs.readdirSync(userDir(u)).map(n => { const s = fs.statSync(path.join(userDir(u), n)); return { name: n, size: s.size, mtime: s.mtimeMs }; }); } catch (e) { return []; } },
    'GET /.well-known/assetlinks.json': async () => [{ relation: ['delegate_permission/common.get_login_creds'], target: { namespace: 'android_app', package_name: cfg.pkg, sha256_cert_fingerprints: cfg.fps } }],
    'GET /healthz': async () => ({ ok: true }),
  };

  const server = http.createServer(async (req, res) => {
    try {
      const url = new URL(req.url, 'http://x'), key = req.method + ' ' + url.pathname;
      if (routes[key]) return send(res, 200, await routes[key](req));
      const m = /^\/api\/files\/([^/]+)$/.exec(url.pathname);
      if (m) {
        const u = need(req), f = path.join(userDir(u), fileName(decodeURIComponent(m[1])));
        if (req.method === 'PUT') { const data = await readBody(req, 30e6); fs.mkdirSync(userDir(u), { recursive: true }); fs.writeFileSync(f, data); return send(res, 200, { ok: true }); }
        if (req.method === 'GET') { if (!fs.existsSync(f)) throw new HttpError(404, 'Not found'); return send(res, 200, fs.readFileSync(f), 'application/octet-stream'); }
        if (req.method === 'DELETE') { try { fs.unlinkSync(f); } catch (e) { /* already gone */ } return send(res, 200, { ok: true }); }
      }
      throw new HttpError(404, 'Not found');
    } catch (e) {
      if (!(e instanceof HttpError)) console.error(e);
      send(res, e instanceof HttpError ? e.code : 500, { error: e instanceof HttpError ? e.message : 'Server error' });
    }
  });
  return { server, db: () => db, origins };
}

module.exports = { createApp };

if (require.main === module) {
  const e = process.env;
  const app = createApp({
    rpId: e.RP_ID || 'localhost', rpName: e.RP_NAME || 'EZ Note', dataDir: e.DATA_DIR || path.join(__dirname, 'data'), secret: e.SECRET || '',
    pkg: e.ANDROID_PACKAGE || 'com.example.notes', fps: (e.ANDROID_SHA256 || '').split(',').map(s => s.trim()).filter(Boolean),
    webOrigins: (e.WEB_ORIGINS || '').split(',').map(s => s.trim()).filter(Boolean), trustProxy: e.TRUST_PROXY === '1',
  });
  app.server.listen(+e.PORT || 3000, () => console.log(`EZ Note server on :${+e.PORT || 3000} (rpId ${e.RP_ID || 'localhost'}; allowed origins: ${[...app.origins].join(', ')})`));
}
