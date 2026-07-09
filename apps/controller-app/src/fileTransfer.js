// Chunked file transfer over an RTCDataChannel.
//
// Framing: on the `file` channel, STRING messages are JSON control frames
// (file-begin / file-end) and BINARY messages are chunk payloads for the
// currently in-flight transfer. WebRTC preserves the string/binary distinction
// per message, so the two never collide. Backpressure is handled via
// bufferedAmount so we don't blow up memory on large files.

const CHUNK = 16 * 1024; // 16 KB per send
const HIGH_WATER = 1024 * 1024; // pause sending above 1 MB buffered

export function fmtSize(n) {
  if (n < 1024) return n + ' B';
  if (n < 1048576) return (n / 1024).toFixed(1) + ' KB';
  return (n / 1048576).toFixed(1) + ' MB';
}

// Wire up the receive side. `log(msg)` for UI lines; `save(name, base64)` must
// persist the file and resolve to its path (a Tauri command).
export function attachFileReceiver(channel, { log, save }) {
  let incoming = null; // { id, name, size, received, chunks: [] }
  channel.binaryType = 'arraybuffer';

  channel.addEventListener('message', async (e) => {
    if (typeof e.data === 'string') {
      let m;
      try { m = JSON.parse(e.data); } catch (_) { return; }
      if (m.kind === 'file-begin') {
        incoming = { id: m.id, name: m.name, size: m.size, received: 0, chunks: [] };
        log(`receiving "${m.name}" (${fmtSize(m.size)})…`);
      } else if (m.kind === 'file-end' && incoming && incoming.id === m.id) {
        const t = incoming;
        incoming = null;
        try {
          const buf = new Uint8Array(await new Blob(t.chunks).arrayBuffer());
          const path = await save(t.name, bytesToB64(buf));
          log(`saved "${t.name}" → ${path}`);
        } catch (err) {
          log(`save failed: ${err}`);
        }
      }
      return;
    }
    // binary chunk for the in-flight transfer
    if (incoming) {
      incoming.chunks.push(e.data);
      incoming.received += e.data.byteLength;
    }
  });
}

// Send a File/Blob over the channel with backpressure. `log(msg)` for UI lines.
export async function sendFile(channel, file, { log } = {}) {
  if (!channel || channel.readyState !== 'open') {
    log && log('cannot send: no open file channel');
    return;
  }
  const id = Math.random().toString(36).slice(2);
  channel.bufferedAmountLowThreshold = 256 * 1024;
  channel.send(JSON.stringify({ kind: 'file-begin', id, name: file.name, size: file.size }));
  log && log(`sending "${file.name}" (${fmtSize(file.size)})…`);

  let offset = 0;
  while (offset < file.size) {
    if (channel.bufferedAmount > HIGH_WATER) {
      await new Promise((res) =>
        channel.addEventListener('bufferedamountlow', res, { once: true })
      );
    }
    const buf = await file.slice(offset, offset + CHUNK).arrayBuffer();
    channel.send(buf);
    offset += CHUNK;
  }
  channel.send(JSON.stringify({ kind: 'file-end', id }));
  log && log(`sent "${file.name}"`);
}

// Uint8Array -> base64, chunked to avoid arg-count limits on fromCharCode.
function bytesToB64(bytes) {
  let bin = '';
  const step = 0x8000;
  for (let i = 0; i < bytes.length; i += step) {
    bin += String.fromCharCode.apply(null, bytes.subarray(i, i + step));
  }
  return btoa(bin);
}
