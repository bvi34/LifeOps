const encoder = new TextEncoder(), decoder = new TextDecoder();
let request, unlockedDocument;
const $ = id => document.getElementById(id);
const b64 = bytes => btoa(String.fromCharCode(...bytes)).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '');
const fromB64 = text => Uint8Array.from(atob(text.replaceAll('-', '+').replaceAll('_', '/') + '==='.slice((text.length + 3) % 4)), c => c.charCodeAt(0));
const random = n => crypto.getRandomValues(new Uint8Array(n));

$('new-request').onclick = () => {
  request = { session: b64(random(16)), key: random(32) };
  $('request').value = `lifeops-secrets-edge:1:${request.session}:${b64(request.key)}`;
  $('status').textContent = 'Encode this request as a QR code and scan it in Secrets.';
};

$('import').onclick = async () => {
  try {
    if (!request) throw Error('Create a transfer request first.');
    const frames = $('frames').value.trim().split(/\s+/).filter(Boolean).map(parseFrame);
    const ordered = frames.sort((a, b) => a.index - b.index);
    if (!ordered.length || ordered.length !== ordered[0].count || new Set(ordered.map(x => x.index)).size !== ordered.length || ordered.some(x => x.session !== request.session || x.count !== ordered.length)) throw Error('Frames are incomplete or belong to another request.');
    const bytes = fromB64(ordered.map(x => x.payload).join(''));
    const key = await crypto.subtle.importKey('raw', request.key, 'AES-GCM', false, ['decrypt']);
    const vault = new Uint8Array(await crypto.subtle.decrypt({ name: 'AES-GCM', iv: bytes.slice(0, 12), additionalData: encoder.encode(request.session) }, key, bytes.slice(12)));
    await chrome.storage.local.set({ sealedVault: b64(vault) });
    $('status').textContent = 'Encrypted vault imported. Enter the master passphrase to unlock it locally.';
  } catch (e) { $('status').textContent = e.message; }
};

$('unlock').onclick = async () => {
  try {
    const { sealedVault } = await chrome.storage.local.get('sealedVault');
    if (!sealedVault) throw Error('Import an encrypted vault first.');
    unlockedDocument = await openVault(fromB64(sealedVault), $('passphrase').value);
    $('passphrase').value = ''; $('status').textContent = `${unlockedDocument.items.filter(x => !x.deletedAt).length} vault items unlocked for this session.`;
  } catch (_) { unlockedDocument = undefined; $('status').textContent = 'Could not unlock this vault.'; }
};
$('lock').onclick = () => { unlockedDocument = undefined; $('passphrase').value = ''; $('status').textContent = 'Locked.'; };

function parseFrame(text) { const p = text.split(':'); if (p.length !== 6 || p.slice(0, 2).join(':') !== 'lifeops-secrets-frame' || p[1] !== '1') throw Error('Invalid transfer frame.'); return { session: p[2], index: +p[3], count: +p[4], payload: p[5] }; }
async function openVault(file, passphrase) {
  if (decoder.decode(file.slice(0, 8)) !== 'OPSVAULT' || file[8] !== 1 || file[9] !== 1) throw Error('Unsupported vault.');
  let p = 10, iterations = new DataView(file.buffer, file.byteOffset + p, 4).getUint32(); p += 4;
  const salt = file.slice(p + 1, p + 1 + file[p]); p += 1 + file[p]; const kdfHeader = file.slice(0, p);
  const wrappedNonce = file.slice(p + 1, p + 1 + file[p]); p += 1 + file[p]; const wrappedSize = new DataView(file.buffer, file.byteOffset + p, 2).getUint16(); p += 2;
  const wrapped = file.slice(p, p + wrappedSize); p += wrappedSize; const header = file.slice(0, p);
  const bodyNonce = file.slice(p + 1, p + 1 + file[p]); p += 1 + file[p]; const bodySize = new DataView(file.buffer, file.byteOffset + p, 4).getUint32(); p += 4;
  const kek = await crypto.subtle.deriveKey({ name: 'PBKDF2', salt, iterations, hash: 'SHA-256' }, await crypto.subtle.importKey('raw', encoder.encode(passphrase), 'PBKDF2', false, ['deriveKey']), { name: 'AES-GCM', length: 256 }, false, ['decrypt']);
  const vaultKey = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: wrappedNonce, additionalData: kdfHeader }, kek, wrapped);
  const document = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: bodyNonce, additionalData: header }, await crypto.subtle.importKey('raw', vaultKey, 'AES-GCM', false, ['decrypt']), file.slice(p, p + bodySize));
  return JSON.parse(decoder.decode(document));
}
