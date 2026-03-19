// Copyright (c) 2026 @Natfii. All rights reserved.

//! Embedded HTML/JS viewer page for the ClawBoy WebSocket stream.
//!
//! Contains a self-contained HTML page that renders Game Boy frames
//! streamed over WebSocket as RGB565 binary data. The page is served
//! by the viewer server on HTTP GET requests and then upgrades to a
//! WebSocket connection on the same host:port for frame streaming.

/// Self-contained HTML page that renders Game Boy frames from a WebSocket.
///
/// Served by the viewer server on HTTP GET requests. The page connects
/// to the same host:port via WebSocket and renders RGB565 frames to a
/// `<canvas>` element.
///
/// # Frame format
///
/// Each WebSocket binary message is exactly 46,080 bytes (160 × 144 × 2),
/// encoding one full Game Boy frame in RGB565 little-endian pixel format.
///
/// # Features
///
/// - Real-time frame rendering to an HTML5 canvas
/// - RGB565 → RGBA decoding with `image-rendering: pixelated` scaling
/// - Zoom controls (1×–6×, default 3×)
/// - Auto-reconnect on WebSocket close (2-second retry)
/// - FPS counter and play-time display (MM:SS)
/// - Connection status indicator (green/red dot)
pub const VIEWER_HTML: &str = r#"<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>ClawBoy</title>
<style>
*, *::before, *::after {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

body {
  background: #1a1a2e;
  color: #e0e0e0;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  overflow: hidden;
}

header {
  text-align: center;
  margin-bottom: 12px;
}

header h1 {
  font-size: 1.4rem;
  font-weight: 700;
  letter-spacing: 0.08em;
  color: #e0e0e0;
}

.status-bar {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  margin-top: 6px;
  font-size: 0.8rem;
  color: #a0a0b0;
}

.status-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #ef4444;
  margin-right: 4px;
  vertical-align: middle;
}

.status-dot.connected {
  background: #22c55e;
}

#screen {
  image-rendering: pixelated;
  image-rendering: -webkit-optimize-contrast;
  -webkit-image-rendering: pixelated;
  border: 2px solid #2a2a4e;
  border-radius: 4px;
}

.controls {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 12px;
}

.controls button {
  background: #2a2a4e;
  color: #e0e0e0;
  border: 1px solid #3a3a5e;
  border-radius: 4px;
  padding: 4px 12px;
  font-size: 0.9rem;
  cursor: pointer;
  transition: background 0.15s;
  min-width: 32px;
}

.controls button:hover {
  background: #3a3a5e;
}

.controls button:active {
  background: #4a4a6e;
}

.controls span {
  font-size: 0.85rem;
  color: #a0a0b0;
  min-width: 28px;
  text-align: center;
}
</style>
</head>
<body>
<header>
  <h1>ClawBoy</h1>
  <div class="status-bar">
    <span><span class="status-dot" id="statusDot"></span><span id="statusText">Connecting...</span></span>
    <span id="playTime">00:00</span>
    <span id="fpsDisplay">0 fps</span>
  </div>
</header>
<canvas id="screen" width="160" height="144"></canvas>
<div class="controls">
  <button id="zoomOut">&minus;</button>
  <span id="zoomLabel">3x</span>
  <button id="zoomIn">+</button>
</div>

<script>
(function() {
  "use strict";

  var GB_W = 160;
  var GB_H = 144;
  var FRAME_BYTES = GB_W * GB_H * 2;
  var MIN_ZOOM = 1;
  var MAX_ZOOM = 6;
  var RECONNECT_MS = 2000;

  var canvas = document.getElementById("screen");
  var ctx = canvas.getContext("2d");
  var statusDot = document.getElementById("statusDot");
  var statusText = document.getElementById("statusText");
  var playTimeEl = document.getElementById("playTime");
  var fpsDisplay = document.getElementById("fpsDisplay");
  var zoomLabel = document.getElementById("zoomLabel");
  var zoomInBtn = document.getElementById("zoomIn");
  var zoomOutBtn = document.getElementById("zoomOut");

  var zoom = 3;
  var ws = null;
  var reconnectTimer = null;
  var frameCount = 0;
  var lastFpsTime = performance.now();
  var startTime = Date.now();

  var imageData = ctx.createImageData(GB_W, GB_H);
  var pixels = imageData.data;

  function applyZoom() {
    canvas.style.width = (GB_W * zoom) + "px";
    canvas.style.height = (GB_H * zoom) + "px";
    zoomLabel.textContent = zoom + "x";
  }

  applyZoom();

  zoomInBtn.addEventListener("click", function() {
    if (zoom < MAX_ZOOM) { zoom++; applyZoom(); }
  });

  zoomOutBtn.addEventListener("click", function() {
    if (zoom > MIN_ZOOM) { zoom--; applyZoom(); }
  });

  function setStatus(connected, text) {
    if (connected) {
      statusDot.classList.add("connected");
    } else {
      statusDot.classList.remove("connected");
    }
    statusText.textContent = text;
  }

  function updatePlayTime() {
    var elapsed = Math.floor((Date.now() - startTime) / 1000);
    var m = Math.floor(elapsed / 60);
    var s = elapsed % 60;
    playTimeEl.textContent =
      (m < 10 ? "0" : "") + m + ":" + (s < 10 ? "0" : "") + s;
  }

  setInterval(updatePlayTime, 1000);

  function updateFps() {
    var now = performance.now();
    var dt = now - lastFpsTime;
    if (dt >= 1000) {
      var fps = Math.round(frameCount * 1000 / dt);
      fpsDisplay.textContent = fps + " fps";
      frameCount = 0;
      lastFpsTime = now;
    }
  }

  function decodeFrame(buffer) {
    if (buffer.byteLength !== FRAME_BYTES) return;

    var view = new DataView(buffer);
    var px = 0;

    for (var i = 0; i < GB_W * GB_H; i++) {
      var pixel = view.getUint16(i * 2, true);
      pixels[px]     = ((pixel >> 11) & 0x1F) * 255 / 31 | 0;
      pixels[px + 1] = ((pixel >> 5) & 0x3F) * 255 / 63 | 0;
      pixels[px + 2] = (pixel & 0x1F) * 255 / 31 | 0;
      pixels[px + 3] = 255;
      px += 4;
    }

    ctx.putImageData(imageData, 0, 0);
    frameCount++;
    updateFps();
  }

  function connect() {
    if (ws) {
      try { ws.close(); } catch(e) {}
      ws = null;
    }

    setStatus(false, "Connecting...");

    ws = new WebSocket("ws://" + location.host);
    ws.binaryType = "arraybuffer";

    ws.addEventListener("open", function() {
      setStatus(true, "Connected");
    });

    ws.addEventListener("message", function(ev) {
      if (ev.data instanceof ArrayBuffer) {
        decodeFrame(ev.data);
      }
    });

    ws.addEventListener("close", function() {
      setStatus(false, "Disconnected");
      scheduleReconnect();
    });

    ws.addEventListener("error", function() {
      setStatus(false, "Disconnected");
      try { ws.close(); } catch(e) {}
    });
  }

  function scheduleReconnect() {
    if (reconnectTimer) return;
    reconnectTimer = setTimeout(function() {
      reconnectTimer = null;
      connect();
    }, RECONNECT_MS);
  }

  connect();
})();
</script>
</body>
</html>"#;
