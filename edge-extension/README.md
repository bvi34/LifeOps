# LifeOps Secrets for Edge

Load this directory through `edge://extensions` → **Developer mode** → **Load unpacked**.

The companion stores only the encrypted `OPSVAULT` envelope in extension storage. It has no network
permission and unlocks the envelope locally with the master passphrase. Pairing uses a one-time
request key and numbered encrypted QR frames, so it does not need a server or a native host.

The QR renderer/scanner UI is intentionally the next UI increment: this source package exposes the
request string and accepts the frame strings so the transfer protocol remains inspectable and can be
tested with a QR utility without granting the extension network access.
