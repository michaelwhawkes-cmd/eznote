'use strict';
/* Self-test: runs the server in-process and drives it with a simulated passkey authenticator. */
const crypto = require('crypto'), fs = require('fs'), os = require('os'), path = require('path'), assert = require('assert');
const { createApp } = require('./server');
const b64u = b => Buffer.from(b).toString('base64url'), sha = b => crypto.createHash('sha256').update(b).digest();

const enc = v => { // tiny CBOR writer
  const head = (mt, n) => n < 24 ? Buffer.from([mt << 5 | n]) : n < 256 ? Buffer.from([mt << 5 | 24, n]) : Buffer.from([mt << 5 | 25, n >> 8, n & 255]);
  if (typeof v === 'number') return v >= 0 ? head(0, v) : head(1, -1 - v);
  if (typeof v === 'string') return Buffer.concat([head(3, Buffer.byteLength(v)), Buffer.from(v)]);
  if (Buffer.isBuffer(v)) return Buffer.concat([head(2, v.length), v]);
  const ents = v instanceof Map ? [...v] : Object.entries(v);
  return Buffer.concat([head(5, ents.length), ...ents.flatMap(([k, x]) => [enc(k), enc(x)])]);
};

const FP = Array.from({ length: 32 }, (_, i) => (i * 7 + 3).toString(16).padStart(2, '0').toUpperCase()).join(':');
const ANDROID = 'android:apk-key-hash:' + b64u(Buffer.from(FP.replace(/:/g, ''), 'hex'));
const RP = 'notes.example.com';

(async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ez-'));
  const app = createApp({ rpId: RP, dataDir: dir, fps: [FP], pkg: 'com.example.notes' });
  await new Promise(r => app.server.listen(0, r));
  const base = 'http://127.0.0.1:' + app.server.address().port;
  const call = async (m, p, body, tok, raw) => {
    const r = await fetch(base + p, { method: m, headers: Object.assign(tok ? { Authorization: 'Bearer ' + tok } : {}, raw ? {} : { 'Content-Type': 'application/json' }), body: raw ? body : body && JSON.stringify(body) });
    const t = Buffer.from(await r.arrayBuffer()); let j; try { j = JSON.parse(t.toString()); } catch (e) { /* binary */ }
    return { code: r.status, j, t };
  };
  let n = 0; const ok = (c, msg) => { assert(c, msg); n++; console.log('  ✓ ' + msg); };

  // --- simulated authenticator ---
  const { publicKey, privateKey } = crypto.generateKeyPairSync('ec', { namedCurve: 'P-256' });
  const jwk = publicKey.export({ format: 'jwk' }), credId = crypto.randomBytes(16);
  const cose = new Map([[1, 2], [3, -7], [-1, 1], [-2, Buffer.from(jwk.x, 'base64url')], [-3, Buffer.from(jwk.y, 'base64url')]]);
  const regResponse = (opts, flags = 0x45, origin = ANDROID) => {
    const cd = Buffer.from(JSON.stringify({ type: 'webauthn.create', challenge: opts.challenge, origin, androidPackageName: 'com.example.notes' }));
    const len = Buffer.alloc(2); len.writeUInt16BE(credId.length);
    const ad = Buffer.concat([sha(RP), Buffer.from([flags]), Buffer.alloc(4), Buffer.alloc(16), len, credId, enc(cose)]);
    return { id: b64u(credId), rawId: b64u(credId), type: 'public-key', response: { clientDataJSON: b64u(cd), attestationObject: b64u(enc({ fmt: 'none', attStmt: {}, authData: ad })) } };
  };
  let counter = 0;
  const assertion = (opts, tamper) => {
    const cd = Buffer.from(JSON.stringify({ type: 'webauthn.get', challenge: opts.challenge, origin: ANDROID }));
    const sc = Buffer.alloc(4); sc.writeUInt32BE(++counter);
    const ad = Buffer.concat([sha(RP), Buffer.from([0x05]), sc]);
    let sig = crypto.sign('sha256', Buffer.concat([ad, sha(cd)]), privateKey); if (tamper) sig = Buffer.from(sig).fill(1, 8, 12);
    return { id: b64u(credId), rawId: b64u(credId), type: 'public-key', response: { clientDataJSON: b64u(cd), authenticatorData: b64u(ad), signature: b64u(sig), userHandle: '' } };
  };

  console.log('Passkeys');
  let r = await call('POST', '/api/register/options', { username: 'Ada' });
  ok(r.code === 200 && r.j.rp.id === RP && r.j.authenticatorSelection.residentKey === 'required', 'registration options ask for a discoverable, user-verified passkey');
  const opts = r.j;
  r = await call('POST', '/api/register/verify', { username: 'ada', credential: regResponse({ challenge: 'bogus' }) });
  ok(r.code === 400, 'registration with an unknown challenge is rejected');
  r = await call('POST', '/api/register/options', { username: 'ada' }); const o2 = r.j;
  r = await call('POST', '/api/register/verify', { username: 'ada', credential: regResponse(o2, 0x41) });
  ok(r.code === 400, 'registration without user verification is rejected');
  r = await call('POST', '/api/register/options', { username: 'ada' }); const o3 = r.j;
  r = await call('POST', '/api/register/verify', { username: 'ada', credential: regResponse(o3, 0x45, 'https://evil.example') });
  ok(r.code === 400, 'registration from an unregistered app/origin is rejected');
  r = await call('POST', '/api/register/options', { username: 'ada' }); const o4 = r.j;
  r = await call('POST', '/api/register/verify', { username: 'ada', credential: regResponse(o4) });
  ok(r.code === 200 && r.j.token && r.j.username === 'ada', 'account created with a passkey (Android origin accepted)');
  const tok1 = r.j.token;
  r = await call('POST', '/api/register/options', { username: 'ADA' });
  ok(r.code === 409, 'username can’t be taken by someone else');

  r = await call('POST', '/api/login/options', {}); const lo = r.j;
  r = await call('POST', '/api/login/verify', { credential: assertion(lo, true) });
  ok(r.code === 401, 'a bad signature is rejected');
  r = await call('POST', '/api/login/options', {}); const lo2 = r.j; const good = assertion(lo2);
  r = await call('POST', '/api/login/verify', { credential: good });
  ok(r.code === 200 && r.j.username === 'ada', 'passkey sign-in works and returns the username');
  const tok2 = r.j.token;
  r = await call('POST', '/api/login/verify', { credential: good });
  ok(r.code === 400, 'replaying the same sign-in is rejected');

  console.log('Sync');
  r = await call('POST', '/api/sync', { notes: [] });
  ok(r.code === 401, 'sync needs a session');
  const note = (id, t, u) => ({ id, t, h: '<p>' + t + '</p>', c: '', u, l: [], p: [] });
  r = await call('POST', '/api/sync', { g: [], cats: ['Work'], notes: [note('a', 'first', 100), note('b', 'second', 100)] }, tok1);
  ok(r.code === 200 && r.j.notes.length === 2, 'device 1 uploads two notes');
  r = await call('POST', '/api/sync', { g: ['b'], cats: ['Ideas'], notes: [note('a', 'edited on device 2', 200), note('c', 'third', 50)] }, tok2);
  ok(r.j.notes.length === 2 && r.j.notes.find(x => x.id === 'a').t === 'edited on device 2' && !r.j.notes.some(x => x.id === 'b'), 'device 2: newest edit wins and deletions propagate');
  ok(r.j.cats.includes('Work') && r.j.cats.includes('Ideas'), 'categories merge');
  r = await call('POST', '/api/sync', { notes: [note('a', 'stale', 10)] }, tok1);
  ok(r.j.notes.find(x => x.id === 'a').t === 'edited on device 2', 'an older edit never overwrites a newer one');

  console.log('Files');
  r = await call('PUT', '/api/files/1700_doc.pdf', Buffer.from('%PDF-fake'), tok1, true);
  ok(r.code === 200, 'upload a PDF');
  r = await call('GET', '/api/files', null, tok2); ok(r.j.length === 1 && r.j[0].name === '1700_doc.pdf', 'other device sees it');
  r = await call('GET', '/api/files/1700_doc.pdf', null, tok2); ok(r.t.toString() === '%PDF-fake', 'and downloads identical bytes');
  r = await call('PUT', '/api/files/..%2F..%2Fusers.json', Buffer.from('x'), tok1, true); ok(r.code === 400, 'path traversal is rejected');

  console.log('Passwords and adding a passkey');
  r = await call('POST', '/api/password/register', { username: 'grace', password: 'short' }); ok(r.code === 400, 'short passwords are rejected');
  r = await call('POST', '/api/password/register', { username: 'grace', password: 'correct horse battery' }); ok(r.code === 200, 'password account created');
  const tokG = r.j.token;
  r = await call('POST', '/api/password/login', { username: 'grace', password: 'wrong wrong wrong' }); ok(r.code === 401, 'wrong password is rejected');
  r = await call('POST', '/api/password/login', { username: 'Grace', password: 'correct horse battery' }); ok(r.code === 200, 'password sign-in works');
  r = await call('POST', '/api/register/options', { username: 'grace' }); ok(r.code === 409, 'adding a passkey requires being signed in');
  r = await call('POST', '/api/register/options', { username: 'grace' }, tokG); ok(r.code === 200, 'a signed-in password user can add a passkey');

  console.log('Android passkey setup');
  r = await call('GET', '/.well-known/assetlinks.json'); ok(r.j[0].target.package_name === 'com.example.notes' && r.j[0].target.sha256_cert_fingerprints[0] === FP, 'assetlinks.json is served for the app');
  const saved = JSON.parse(fs.readFileSync(path.join(dir, 'users.json'), 'utf8')); ok(!/correct horse/.test(JSON.stringify(saved)), 'passwords are stored hashed');

  app.server.close(); console.log(`\nAll ${n} checks passed.`);
})().catch(e => { console.error('\nFAILED:', e.message); process.exit(1); });
