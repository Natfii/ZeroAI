// Copyright (c) 2026 @Natfii. All rights reserved.

//! Embedded HTML page for the Google Messages QR code pairing flow.
//!
//! Contains a self-contained HTML page that displays the QR code generated
//! by the pairing server. The placeholder `QR_SVG_PLACEHOLDER` is replaced
//! at runtime with the actual SVG before the page is served.

/// Embedded HTML page for the QR code pairing flow.
///
/// Served by the local HTTP server during pairing. The literal string
/// `QR_SVG_PLACEHOLDER` is substituted at runtime with the SVG produced
/// by [`generate_qr_svg`](super::messages_bridge::generate_qr_svg)
/// before the page is written to the HTTP response.
///
/// # Polling
///
/// An embedded script polls `GET /status` every 2 seconds. When the
/// server returns `{"paired":true}` the page replaces itself with a
/// success message, so the user can close the tab.
pub const PAIRING_HTML: &str = r##"<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>ZeroAI — Pair Google Messages</title>
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }

  body {
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
    background: #0f1729;
    color: #c8d6e5;
    display: flex;
    justify-content: center;
    align-items: center;
    min-height: 100vh;
  }

  .card {
    background: linear-gradient(145deg, #162038, #1a2744);
    border: 1px solid rgba(89, 166, 232, 0.15);
    border-radius: 20px;
    padding: 40px 36px;
    max-width: 420px;
    width: 100%;
    text-align: center;
    box-shadow:
      0 4px 24px rgba(0, 0, 0, 0.4),
      0 0 60px rgba(42, 111, 182, 0.06);
  }

  .mascot {
    image-rendering: pixelated;
    image-rendering: crisp-edges;
    width: 64px;
    height: 64px;
    margin: 0 auto 16px;
    display: block;
    animation: float 3s ease-in-out infinite;
  }

  @keyframes float {
    0%, 100% { transform: translateY(0); }
    50% { transform: translateY(-6px); }
  }

  h1 {
    font-size: 1.4rem;
    font-weight: 700;
    margin-bottom: 4px;
    color: #fff;
    letter-spacing: -0.01em;
  }

  .subtitle {
    font-size: 0.85rem;
    color: #7a8ba8;
    margin-bottom: 28px;
  }

  #qr-container {
    background: #ffffff;
    border-radius: 14px;
    padding: 20px;
    display: inline-block;
    margin-bottom: 24px;
    box-shadow: 0 2px 16px rgba(0, 0, 0, 0.2);
  }

  #qr-container svg { display: block; }

  .status {
    font-size: 0.9rem;
    font-weight: 500;
    padding: 10px 20px;
    border-radius: 10px;
    display: inline-flex;
    align-items: center;
    gap: 8px;
  }

  .status.waiting {
    background: rgba(89, 166, 232, 0.1);
    color: #59a6e8;
  }

  .status.paired {
    background: rgba(76, 175, 80, 0.12);
    color: #66bb6a;
  }

  @keyframes pulse {
    0%, 100% { opacity: 1; }
    50% { opacity: 0.4; }
  }

  .dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: #59a6e8;
    animation: pulse 1.5s ease-in-out infinite;
  }

  .instructions {
    text-align: left;
    margin-top: 28px;
    font-size: 0.82rem;
    color: #5a6f8a;
    padding: 0 4px;
    counter-reset: steps;
    list-style: none;
  }

  .instructions li {
    margin-bottom: 10px;
    padding-left: 28px;
    position: relative;
    line-height: 1.5;
  }

  .instructions li::before {
    counter-increment: steps;
    content: counter(steps);
    position: absolute;
    left: 0;
    width: 20px;
    height: 20px;
    background: rgba(89, 166, 232, 0.12);
    color: #59a6e8;
    border-radius: 6px;
    font-size: 0.7rem;
    font-weight: 600;
    display: flex;
    align-items: center;
    justify-content: center;
  }

  .instructions strong { color: #8fabc8; }

  .footer {
    margin-top: 28px;
    font-size: 0.72rem;
    color: #3a4d66;
  }

  .tip {
    margin-top: 20px;
    padding: 10px 14px;
    background: rgba(255, 193, 7, 0.08);
    border: 1px solid rgba(255, 193, 7, 0.18);
    border-radius: 10px;
    font-size: 0.78rem;
    color: #b8a060;
    text-align: left;
    line-height: 1.5;
  }

  .tip strong { color: #d4b96a; }

  /* Success state */
  .success-card {
    background: linear-gradient(145deg, #162038, #1a2744);
    border: 1px solid rgba(76, 175, 80, 0.2);
    border-radius: 20px;
    padding: 48px 36px;
    max-width: 420px;
    width: 100%;
    text-align: center;
    box-shadow:
      0 4px 24px rgba(0, 0, 0, 0.4),
      0 0 60px rgba(76, 175, 80, 0.08);
  }

  .success-card h1 { color: #66bb6a; font-size: 1.5rem; margin-bottom: 8px; }
  .success-card .subtitle { color: #5a6f8a; margin-bottom: 0; }
</style>
</head>
<body>
<div class="card" id="main-card">
  <svg class="mascot" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16" shape-rendering="crispEdges">
    <path fill="#2A6FB6" d="M5 2h6v1h2v2h1v6h-1v2h-2v1H5v-1H3v-2H2V5h1V3h2V2z"/>
    <path fill="#59A6E8" d="M6 4h4v1h1v1h1v4h-1v1h-1v1H6v-1H5v-1H4V6h1V5h1V4z"/>
    <path fill="#FF4B4B" d="M4 6h1v1h1V6h1v2h-1v1h-1V8H4zm4 0h1v1h1V6h1v2h-1v1h-1V8H8z"/>
    <rect x="5" y="13" width="1" height="1" fill="#2A6FB6"/>
    <rect x="10" y="13" width="1" height="1" fill="#2A6FB6"/>
  </svg>
  <h1>Pair Google Messages</h1>
  <p class="subtitle">Scan this QR code with Google Messages on your phone</p>
  <div id="qr-container">QR_SVG_PLACEHOLDER</div>
  <div id="status" class="status waiting"><span class="dot"></span> Waiting for scan&hellip;</div>
  <ol class="instructions">
    <li>Open <strong>Google Messages</strong> on your phone</li>
    <li>Tap your <strong>profile icon</strong> &rarr; <strong>Device pairing</strong></li>
    <li>Tap <strong>QR code scanner</strong></li>
    <li>Point your phone at this QR code</li>
  </ol>
  <div class="tip">
    <strong>Trouble pairing?</strong> Turn off VPNs (Tailscale, WireGuard, etc.) on both your phone and this computer, then try again. VPNs can prevent the pairing handshake from completing.
  </div>
  <p class="footer">Served by ZeroAI on your local network</p>
</div>
<script>
(function() {
  var SLEEPY_ZERO =
    '<svg class="mascot" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16" ' +
    'shape-rendering="crispEdges" style="image-rendering:pixelated;width:80px;height:80px;' +
    'display:block;margin:0 auto 20px;animation:float 4s ease-in-out infinite">' +
    '<path fill="#2A6FB6" d="M5 2h6v1h2v2h1v6h-1v2h-2v1H5v-1H3v-2H2V5h1V3h2V2z"/>' +
    '<path fill="#59A6E8" d="M6 4h4v1h1v1h1v4h-1v1h-1v1H6v-1H5v-1H4V6h1V5h1V4z"/>' +
    '<rect x="5" y="8" width="2" height="1" fill="#09111B"/>' +
    '<rect x="9" y="8" width="2" height="1" fill="#09111B"/>' +
    '<rect x="5" y="13" width="1" height="1" fill="#2A6FB6"/>' +
    '<rect x="10" y="13" width="1" height="1" fill="#2A6FB6"/>' +
    '</svg>';

  var LOVE_ZERO =
    '<svg class="mascot" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16" ' +
    'shape-rendering="crispEdges" style="image-rendering:pixelated;width:80px;height:80px;' +
    'display:block;margin:0 auto 20px">' +
    '<path fill="#2A6FB6" d="M5 2h6v1h2v2h1v6h-1v2h-2v1H5v-1H3v-2H2V5h1V3h2V2z"/>' +
    '<path fill="#59A6E8" d="M6 4h4v1h1v1h1v4h-1v1h-1v1H6v-1H5v-1H4V6h1V5h1V4z"/>' +
    '<path fill="#FF4B4B" d="M4 6h1v1h1V6h1v2h-1v1h-1V8H4zm4 0h1v1h1V6h1v2h-1v1h-1V8H8z"/>' +
    '<rect x="5" y="13" width="1" height="1" fill="#2A6FB6"/>' +
    '<rect x="10" y="13" width="1" height="1" fill="#2A6FB6"/>' +
    '</svg>';

  var done = false;
  var lastOk = Date.now();

  function showDisconnected() {
    if (done) return;
    done = true;
    document.body.innerHTML =
      '<div class="success-card" style="border-color:rgba(89,166,232,0.12);' +
      'box-shadow:0 4px 24px rgba(0,0,0,0.4),0 0 60px rgba(42,111,182,0.06)">' +
      SLEEPY_ZERO +
      '<h1 style="color:#59a6e8">ZeroAI fell asleep</h1>' +
      '<p class="subtitle" style="margin-bottom:16px">' +
      'Lost connection to ZeroAI on your phone.<br>' +
      'The pairing server may have stopped or the session completed.</p>' +
      '<p class="subtitle" style="font-size:0.75rem;color:#3a4d66">' +
      'You can close this tab.</p></div>';
  }

  function showPaired() {
    if (done) return;
    done = true;
    var el = document.getElementById('status');
    if (el) {
      el.innerHTML = '\u2705 Paired successfully!';
      el.className = 'status paired';
    }
    setTimeout(function() {
      document.body.innerHTML =
        '<div class="success-card">' +
        LOVE_ZERO +
        '<h1>Paired!</h1>' +
        '<p class="subtitle">You can close this tab now.</p></div>';
    }, 2000);
  }

  /* Fast poll: check pairing status every 2s */
  var pollInterval = setInterval(function() {
    if (done) { clearInterval(pollInterval); return; }
    fetch('/status')
      .then(function(r) { lastOk = Date.now(); return r.json(); })
      .then(function(data) { if (data.paired) showPaired(); })
      .catch(function() {});
  }, 2000);

  /* Heartbeat: every 60s check if server is still reachable */
  var heartbeat = setInterval(function() {
    if (done) { clearInterval(heartbeat); return; }
    if (Date.now() - lastOk > 60000) {
      clearInterval(heartbeat);
      clearInterval(pollInterval);
      showDisconnected();
    }
  }, 10000);
})();
</script>
</body>
</html>"##;
