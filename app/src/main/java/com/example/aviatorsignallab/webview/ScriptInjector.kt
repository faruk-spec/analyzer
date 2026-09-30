package com.example.aviatorsignallab.webview

object ScriptInjector {

    /**
     * Stealth client-side telemetry script.
     * Hooks WebSocket (JSON, Blobs, ArrayBuffers), Console, Canvas 2D fillText, Fetch, and XHR.
     * Extracts multipliers, round IDs, and crash signals in real-time across all frames.
     * Prevents false updates on casino lobbies by isolating game frames.
     */
    val INJECTION_SCRIPT: String = """
        (function() {
            if (window.__aviatorLabInstrumented) return;
            window.__aviatorLabInstrumented = true;

            // 0. Stealth anti-detection: Hide automation indicators
            try {
                Object.defineProperty(navigator, 'webdriver', {
                    get: function() { return undefined; }
                });
            } catch(e) {}

            function safeDispatch(transport, direction, payload, size) {
                try {
                    var strPayload = typeof payload === 'string' ? payload : JSON.stringify(payload);
                    var payloadSize = size || (strPayload ? strPayload.length : 0);

                    if (window.AndroidBridge && window.AndroidBridge.onNetworkEvent) {
                        window.AndroidBridge.onNetworkEvent(transport, direction, strPayload || '', payloadSize);
                    }
                    // Also forward to parent/top window for cross-origin iframes
                    if (window.top && window.top !== window) {
                        window.top.postMessage({
                            __aviatorLabEvent: true,
                            transport: transport,
                            direction: direction,
                            payload: strPayload || '',
                            size: payloadSize
                        }, '*');
                    }
                } catch(e) {}
            }

            function safeLog(msg) {
                try {
                    if (window.AndroidBridge && window.AndroidBridge.onDiagnostics) {
                        window.AndroidBridge.onDiagnostics(msg);
                    }
                    if (window.top && window.top !== window) {
                        window.top.postMessage({ __aviatorLabDiag: true, msg: msg }, '*');
                    }
                } catch(e) {}
            }

            // Report frame loading
            try {
                var currentHref = window.location.href || "";
                var isTopFrame = (window.top === window);
                safeLog("[IFRAME] Loaded: " + currentHref + " (isTop=" + isTopFrame + ")");
                safeDispatch("IFRAME", "INTERNAL", JSON.stringify({
                    url: currentHref,
                    isTop: isTopFrame,
                    title: document.title || ""
                }), 0);
            } catch(e) {}

            function isGameContext() {
                try {
                    var href = (window.location.href || "").toLowerCase();
                    if (href.indexOf('aviator') !== -1 || href.indexOf('spribe') !== -1) return true;
                    // If inside an iframe and document has canvas
                    if (window.top !== window && document.querySelector('canvas')) return true;
                } catch(e) {}
                return false;
            }

            // Listener for cross-iframe forwarded events
            window.addEventListener('message', function(event) {
                try {
                    if (event.data && event.data.__aviatorLabEvent) {
                        safeDispatch(event.data.transport, event.data.direction, event.data.payload, event.data.size);
                    } else if (event.data && event.data.__aviatorLabDiag) {
                        safeLog(event.data.msg);
                    } else if (event.data && event.data.__aviatorLabAnimStutter) {
                        if (window.AndroidBridge && window.AndroidBridge.onAnimationStutter) {
                            window.AndroidBridge.onAnimationStutter(event.data.delta, event.data.mult);
                        }
                    } else if (event.data && event.data.__aviatorSyncMult !== undefined) {
                        window.__aviatorCurrentMult = event.data.__aviatorSyncMult;
                        if (event.data.__aviatorFlightActive !== undefined) {
                            window.__aviatorFlightActive = event.data.__aviatorFlightActive;
                        }
                    }
                } catch(e) {}
            });

            // 1. Console Interception (Captures console.log, info, warn, error)
            try {
                var origLog = console.log;
                var origWarn = console.warn;
                var origErr = console.error;
                var origInfo = console.info;

                function formatArgs(args) {
                    var parts = [];
                    for (var i = 0; i < args.length; i++) {
                        var arg = args[i];
                        if (typeof arg === 'object' && arg !== null) {
                            try { parts.push(JSON.stringify(arg)); } catch(e) { parts.push(String(arg)); }
                        } else {
                            parts.push(String(arg));
                        }
                    }
                    return parts.join(' ');
                }

                console.log = function() {
                    try {
                        var msg = formatArgs(arguments);
                        safeLog("[CONSOLE_LOG] " + msg);
                    } catch(e) {}
                    return origLog.apply(console, arguments);
                };

                console.warn = function() {
                    try {
                        var msg = formatArgs(arguments);
                        safeLog("[CONSOLE_WARN] " + msg);
                    } catch(e) {}
                    return origWarn.apply(console, arguments);
                };

                console.error = function() {
                    try {
                        var msg = formatArgs(arguments);
                        safeLog("[CONSOLE_ERROR] " + msg);
                    } catch(e) {}
                    return origErr.apply(console, arguments);
                };

                console.info = function() {
                    try {
                        var msg = formatArgs(arguments);
                        safeLog("[CONSOLE_INFO] " + msg);
                    } catch(e) {}
                    return origInfo.apply(console, arguments);
                };
            } catch(e) {}

            // 2. Stealth Hook for WebSocket (Decodes Text, Blobs, and ArrayBuffers)
            if (window.WebSocket && !window.__aviatorWsHooked) {
                window.__aviatorWsHooked = true;
                var NativeWebSocket = window.WebSocket;

                function processWsMessage(direction, data) {
                    try {
                        if (typeof data === 'string') {
                            // Track live flight multiplier from incoming packets
                            // Track live flight multiplier & flight state from incoming packets
                            var mulMatch = data.match(/"mul"\s*:\s*"?([0-9]+\.?[0-9]*)"?/);
                            if (mulMatch && mulMatch[1]) {
                                var mVal = parseFloat(mulMatch[1]);
                                if (mVal >= 1.0) {
                                    window.__aviatorCurrentMult = mVal;
                                    var isActiveFlight = mVal >= 1.08;
                                    window.__aviatorFlightActive = isActiveFlight;
                                    try {
                                        if (window.top && window.top !== window) {
                                            window.top.postMessage({ __aviatorSyncMult: mVal, __aviatorFlightActive: isActiveFlight }, '*');
                                        }
                                    } catch(e) {}
                                }
                            }

                            // Command 84: 1=waiting/betting, 2=flying, 3=crashed
                            if (direction === 'INCOMING' && data.indexOf('"cmd":84') !== -1) {
                                if (data.indexOf('"sta":1') !== -1 || data.indexOf('"sta":3') !== -1) {
                                    window.__aviatorCurrentMult = 0.0;
                                    window.__aviatorFlightActive = false;
                                    try {
                                        if (window.top && window.top !== window) {
                                            window.top.postMessage({ __aviatorSyncMult: 0.0, __aviatorFlightActive: false }, '*');
                                        }
                                    } catch(e) {}
                                } else if (data.indexOf('"sta":2') !== -1) {
                                    window.__aviatorFlightActive = true;
                                }
                            }
                            safeDispatch("WEBSOCKET", direction, data, data.length);
                        } else if (data instanceof Blob) {
                            data.text().then(function(txt) {
                                safeDispatch("WEBSOCKET", direction, txt, txt.length);
                            }).catch(function() {
                                var s = data.size || 0;
                                safeDispatch("WEBSOCKET", direction, "[BLOB " + s + " bytes]", s);
                            });
                        } else if (data instanceof ArrayBuffer) {
                            try {
                                var txt = new TextDecoder("utf-8").decode(new Uint8Array(data));
                                safeDispatch("WEBSOCKET", direction, txt, txt.length);
                            } catch(e) {
                                var s = data.byteLength || 0;
                                safeDispatch("WEBSOCKET", direction, "[ARRAYBUFFER " + s + " bytes]", s);
                            }
                        } else {
                            safeDispatch("WEBSOCKET", direction, String(data), 0);
                        }
                    } catch(e) {}
                }

                function InstrumentedWebSocket(url, protocols) {
                    var ws;
                    if (arguments.length > 1 && protocols !== undefined) {
                        ws = new NativeWebSocket(url, protocols);
                    } else {
                        ws = new NativeWebSocket(url);
                    }

                    try {
                        safeDispatch("WEBSOCKET", "INTERNAL", JSON.stringify({ event: "CONNECT", url: String(url) }), 0);

                        var origSend = ws.send;
                        ws.send = function(data) {
                            processWsMessage("OUTGOING", data);
                            return origSend.apply(this, arguments);
                        };

                        ws.addEventListener('message', function(evt) {
                            processWsMessage("INCOMING", evt.data);
                        }, false);
                    } catch(e) {}

                    return ws;
                }

                InstrumentedWebSocket.CONNECTING = 0;
                InstrumentedWebSocket.OPEN = 1;
                InstrumentedWebSocket.CLOSING = 2;
                InstrumentedWebSocket.CLOSED = 3;
                InstrumentedWebSocket.prototype = NativeWebSocket.prototype;

                try {
                    Object.defineProperty(InstrumentedWebSocket, 'name', { value: 'WebSocket' });
                    InstrumentedWebSocket.toString = function() { return "function WebSocket() { [native code] }"; };
                } catch(e) {}

                window.WebSocket = InstrumentedWebSocket;
            }

            // 3. Canvas 2D Real-Time Multiplier & Crash Interceptor (Gated to Game Context)
            if (window.CanvasRenderingContext2D && !window.__aviatorCanvasHooked) {
                window.__aviatorCanvasHooked = true;
                var origFillText = CanvasRenderingContext2D.prototype.fillText;
                var lastCanvasMult = "";
                var strictCanvasMultRegex = /^\s*([0-9]{1,4}\.[0-9]{2})\s*[xX]\s*$/;

                CanvasRenderingContext2D.prototype.fillText = function(text, x, y, maxWidth) {
                    try {
                        if (typeof text === 'string' && text.length > 0 && text.length < 35) {
                            var trimmed = text.trim();
                            var upper = trimmed.toUpperCase();
                            if (upper === "FLEW AWAY!" || upper === "FLEW AWAY" || upper.indexOf("FLEW-AWAY") !== -1 || upper.indexOf("CRASHED") !== -1) {
                                window.__aviatorCurrentMult = 0.0;
                                window.__aviatorFlightActive = false;
                                try {
                                    if (window.top && window.top !== window) {
                                        window.top.postMessage({ __aviatorSyncMult: 0.0, __aviatorFlightActive: false }, '*');
                                    }
                                } catch(e) {}
                                if (lastCanvasMult !== "CRASH") {
                                    lastCanvasMult = "CRASH";
                                    safeDispatch("CANVAS", "INTERNAL", JSON.stringify({
                                        type: "DOM_CRASH_SIGNAL",
                                        status: "crash",
                                        rawText: trimmed
                                    }), trimmed.length);
                                }
                            } else {
                                var match = strictCanvasMultRegex.exec(trimmed);
                                if (match && match[1]) {
                                    var num = parseFloat(match[1]);
                                    if (num >= 1.0 && num <= 100000.0 && match[1] !== lastCanvasMult) {
                                        lastCanvasMult = match[1];
                                        window.__aviatorCurrentMult = num;
                                        var isCanvasActive = num >= 1.08;
                                        window.__aviatorFlightActive = isCanvasActive;
                                        try {
                                            if (window.top && window.top !== window) {
                                                window.top.postMessage({ __aviatorSyncMult: num, __aviatorFlightActive: isCanvasActive }, '*');
                                            }
                                        } catch(e) {}
                                        safeDispatch("CANVAS", "INTERNAL", JSON.stringify({
                                            type: "DOM_MULTIPLIER_UPDATE",
                                            multiplier: match[1],
                                            rawText: trimmed
                                        }), trimmed.length);
                                    }
                                }
                            }
                        }
                    } catch(e) {}
                    return origFillText.apply(this, arguments);
                };
            }

            // 4. Stealth Hook for Fetch
            if (window.fetch && !window.__aviatorFetchHooked) {
                window.__aviatorFetchHooked = true;
                var origFetch = window.fetch;
                window.fetch = function() {
                    var args = arguments;
                    var url = "";
                    try {
                        url = (typeof args[0] === 'string') ? args[0] : (args[0] && args[0].url ? args[0].url : "");
                        var options = args[1] || {};
                        var method = options.method || "GET";

                        if (options.body) {
                            var bodyStr = typeof options.body === 'string' ? options.body : "[BODY_DATA]";
                            safeDispatch("FETCH", "OUTGOING", JSON.stringify({ url: url, method: method, body: bodyStr }), bodyStr.length);
                        }
                    } catch(e) {}

                    return origFetch.apply(this, args).then(function(response) {
                        try {
                            var clone = response.clone();
                            clone.text().then(function(bodyText) {
                                if (bodyText && bodyText.length > 0 && bodyText.length < 50000) {
                                    var lower = bodyText.toLowerCase();
                                    if (lower.indexOf('multiplier') !== -1 || lower.indexOf('crash') !== -1 || lower.indexOf('round') !== -1 || 
                                        lower.indexOf('wingo') !== -1 || lower.indexOf('lottery') !== -1 || lower.indexOf('issue') !== -1 ||
                                        (url && (url.toLowerCase().indexOf('aviator') !== -1 || url.toLowerCase().indexOf('wingo') !== -1 || url.toLowerCase().indexOf('lottery') !== -1))) {
                                        safeDispatch("FETCH", "INCOMING", JSON.stringify({
                                            __url: url,
                                            __body: bodyText,
                                            data: (function(){ try { return JSON.parse(bodyText); } catch(e){ return bodyText; } })()
                                        }), bodyText.length);
                                    }
                                }
                            }).catch(function(){});
                        } catch(e) {}
                        return response;
                    });
                };

                try {
                    window.fetch.toString = function() { return "function fetch() { [native code] }"; };
                } catch(e) {}
            }

            // 5. Stealth Hook for XMLHttpRequest
            if (window.XMLHttpRequest && !window.__aviatorXhrHooked) {
                window.__aviatorXhrHooked = true;
                var OrigXHR = window.XMLHttpRequest;
                var origOpen = OrigXHR.prototype.open;
                var origSend = OrigXHR.prototype.send;

                OrigXHR.prototype.open = function(method, url) {
                    try {
                        this.__xhrUrl = url;
                        this.__xhrMethod = method;
                    } catch(e) {}
                    return origOpen.apply(this, arguments);
                };

                OrigXHR.prototype.send = function(body) {
                    var self = this;
                    try {
                        if (body) {
                            var bodyStr = typeof body === 'string' ? body : "[XHR_BODY]";
                            safeDispatch("XHR", "OUTGOING", JSON.stringify({ url: self.__xhrUrl, method: self.__xhrMethod, body: bodyStr }), bodyStr.length);
                        }

                        this.addEventListener('load', function() {
                            try {
                                var text = self.responseText;
                                if (text && text.length > 0 && text.length < 50000) {
                                    var lower = text.toLowerCase();
                                    if (lower.indexOf('multiplier') !== -1 || lower.indexOf('crash') !== -1 || lower.indexOf('round') !== -1 || 
                                        lower.indexOf('wingo') !== -1 || lower.indexOf('lottery') !== -1 || lower.indexOf('issue') !== -1 ||
                                        (self.__xhrUrl && (self.__xhrUrl.toLowerCase().indexOf('aviator') !== -1 || self.__xhrUrl.toLowerCase().indexOf('wingo') !== -1 || self.__xhrUrl.toLowerCase().indexOf('lottery') !== -1))) {
                                        safeDispatch("XHR", "INCOMING", JSON.stringify({
                                            __url: self.__xhrUrl,
                                            __body: text,
                                            data: (function(){ try { return JSON.parse(text); } catch(e){ return text; } })()
                                        }), text.length);
                                    }
                                }
                            } catch(e) {}
                        }, false);
                    } catch(e) {}

                    return origSend.apply(this, arguments);
                };
            }

            // 6. Real-time WinGo Live State Extractor (DOM & Screen Sync)
            try {
                var lastPeriod = "";
                var lastTime = "";
                var lastRoom = "";

                function checkWingoDom() {
                    try {
                        var text = document.body ? document.body.innerText : "";
                        if (!text || (text.indexOf("WinGo") === -1 && text.indexOf("wingo") === -1)) return;

                        // 1. Detect active room tab (30sec, 1 Min, 3 Min, 5 Min)
                        var activeRoom = "WinGo_30S";
                        var tabs = document.querySelectorAll('.van-tab--active, .active, [class*="active"], [class*="select"]');
                        for (var t = 0; t < tabs.length; t++) {
                            var tabText = (tabs[t].innerText || "").toLowerCase();
                            if (tabText.indexOf("30s") !== -1 || tabText.indexOf("30sec") !== -1) { activeRoom = "WinGo_30S"; break; }
                            if (tabText.indexOf("3") !== -1 && tabText.indexOf("min") !== -1) { activeRoom = "WinGo_3M"; break; }
                            if (tabText.indexOf("5") !== -1 && tabText.indexOf("min") !== -1) { activeRoom = "WinGo_5M"; break; }
                            if (tabText.indexOf("1") !== -1 && tabText.indexOf("min") !== -1) { activeRoom = "WinGo_1M"; break; }
                        }

                        // 2. Detect 16-18 digit period ID (e.g. 20260929100052298)
                        var periodMatch = text.match(/\b(202\d{13,15})\b/);
                        var currentPeriod = periodMatch ? periodMatch[1] : "";

                        // 3. Detect countdown time (e.g. 00:23 or 02:46 or 0 0 : 2 3)
                        var timeMatch = text.match(/(\d{1,2})\s*:\s*(\d{2})/);
                        var remainingSecs = 0;
                        var timeStr = "";
                        if (timeMatch) {
                            var mins = parseInt(timeMatch[1], 10);
                            var secs = parseInt(timeMatch[2], 10);
                            remainingSecs = mins * 60 + secs;
                            timeStr = (mins < 10 ? "0" + mins : "" + mins) + ":" + (secs < 10 ? "0" + secs : "" + secs);
                        }

                        if (currentPeriod && (currentPeriod !== lastPeriod || timeStr !== lastTime || activeRoom !== lastRoom)) {
                            lastPeriod = currentPeriod;
                            lastTime = timeStr;
                            lastRoom = activeRoom;

                            safeDispatch("WINGO_DOM", "INCOMING", JSON.stringify({
                                type: "WINGO_DOM_SYNC",
                                gameType: activeRoom,
                                periodId: currentPeriod,
                                timeText: timeStr,
                                remainingSeconds: remainingSecs,
                                isLocked: remainingSecs <= 5
                            }), 50);
                        }
                    } catch(e) {}
                }

                setInterval(checkWingoDom, 400);
            } catch(e) {}

            // 7. Automated Bottom Banner & Security Modal Cleaner (Safe & Non-Destructive)
            // Removes ONLY the intrusive withdrawal warning overlay without touching game modals or iframes
            try {
                function cleanBannersAndModals() {
                    try {
                        // Dismiss ONLY dialogs matching "WITHDRAWAL SECURITY WARNING"
                        var allDivs = document.querySelectorAll('div, p, span, h2, h3, [role="dialog"]');
                        for (var d = 0; d < allDivs.length; d++) {
                            var el = allDivs[d];
                            var text = el.innerText || el.textContent || "";
                            if (text.indexOf("WITHDRAWAL SECURITY WARNING") !== -1 && el.children.length < 8) {
                                var container = el.closest('.van-dialog, .van-popup, [role="dialog"]') || el;
                                container.style.display = 'none';
                                // Also hide the dark backdrop behind this specific modal
                                var backdrop = container.previousElementSibling || container.nextElementSibling;
                                if (backdrop && backdrop.classList && backdrop.classList.contains('van-overlay')) {
                                    backdrop.style.display = 'none';
                                }
                            }
                        }

                        // Auto-dismiss standard withdrawal alert confirm buttons
                        var confirmButtons = document.querySelectorAll('.van-dialog__confirm, .van-button--danger');
                        for (var b = 0; b < confirmButtons.length; b++) {
                            var btn = confirmButtons[b];
                            var p = btn.closest('.van-dialog, .van-popup');
                            if (p && (p.innerText || "").indexOf("WITHDRAWAL") !== -1) {
                                btn.click();
                            }
                        }
                    } catch(err) {}
                }
                setInterval(cleanBannersAndModals, 600);
            } catch(e) {}

            // 8. Adaptive Animation Micro-Blink & Render Hitch Detector (Fires BEFORE Crash)
            // Monitors requestAnimationFrame frame deltas against dynamic device refresh rate (60Hz, 90Hz, 120Hz).
            // A micro-stutter / dropped frame during flight precedes the crash animation sequence.
            try {
                var lastRafTime = performance.now();
                var lastStutterAlertTime = 0;
                var smoothedFrameDelta = 16.6;

                function frameAuditLoop(now) {
                    var delta = now - lastRafTime;
                    lastRafTime = now;

                    // Smoothly learn baseline frame duration (60Hz=16.6ms, 90Hz=11.1ms, 120Hz=8.3ms)
                    if (delta >= 4.0 && delta <= 30.0) {
                        smoothedFrameDelta = smoothedFrameDelta * 0.92 + delta * 0.08;
                    }

                    var curM = window.__aviatorCurrentMult || 0.0;
                    var flightActive = window.__aviatorFlightActive && curM >= 1.25;

                    // STRICT: Only evaluate stutters when the plane is actively flying and multiplier has started!
                    // Zero vibration before multiplier start, zero vibration during betting, countdown, or page load.
                    if (!flightActive) {
                        requestAnimationFrame(frameAuditLoop);
                        return;
                    }

                    // Adaptive spike calculation:
                    // For high multiplier (>6.0x), even a single dropped frame produces a glaring visual blink:
                    var spikeRatio = curM >= 6.0 ? 1.40 : 1.55;
                    var minAbsolute = curM >= 6.0 ? 18.0 : 22.0;
                    var stutterThreshold = Math.max(minAbsolute, smoothedFrameDelta * spikeRatio);

                    if (delta >= stutterThreshold && (now - lastStutterAlertTime > 2200)) {
                        lastStutterAlertTime = now;
                        safeLog("[ANIMATION_MICRO_BLINK] Frame drop: " + delta.toFixed(1) + "ms (baseline=" + smoothedFrameDelta.toFixed(1) + "ms, mult=" + curM.toFixed(2) + "x)");

                        if (window.AndroidBridge && window.AndroidBridge.onAnimationStutter) {
                            window.AndroidBridge.onAnimationStutter(delta, String(curM));
                        }
                        try {
                            if (window.top && window.top !== window) {
                                window.top.postMessage({
                                    __aviatorLabAnimStutter: true,
                                    delta: delta,
                                    mult: String(curM)
                                }, '*');
                            }
                        } catch(e) {}
                    }

                    requestAnimationFrame(frameAuditLoop);
                }
                requestAnimationFrame(frameAuditLoop);
            } catch(e) {}

            // 9. Real-Time Aviator HTML DOM Multiplier & Crash Scanner (Ultra-Fast 100ms sync)
            // Synchronizes multipliers when Aviator is rendered using WebGL/PixiJS and HTML DOM stage board
            try {
                var lastDomAviatorMult = "";
                var aviatorMultRegex = /^\s*([0-9]{1,4}\.[0-9]{2})\s*[xX]\s*$/;

                function checkAviatorDom() {
                    try {
                        var elements = document.querySelectorAll('.stage-board, .payout, [class*="multiplier"], [class*="coefficient"], [class*="payout"], [class*="game-display"], div, span');
                        for (var i = 0; i < elements.length; i++) {
                            var el = elements[i];
                            if (el.children.length <= 1) {
                                var txt = (el.innerText || el.textContent || "").trim();
                                if (txt.length > 0 && txt.length < 25) {
                                    var mMatch = aviatorMultRegex.exec(txt);
                                    if (mMatch && mMatch[1]) {
                                        var mVal = parseFloat(mMatch[1]);
                                        if (mVal >= 1.0 && mMatch[1] !== lastDomAviatorMult) {
                                            lastDomAviatorMult = mMatch[1];
                                            window.__aviatorCurrentMult = mVal;
                                            var isDomActive = mVal >= 1.08;
                                            window.__aviatorFlightActive = isDomActive;
                                            try {
                                                if (window.top && window.top !== window) {
                                                    window.top.postMessage({ __aviatorSyncMult: mVal, __aviatorFlightActive: isDomActive }, '*');
                                                }
                                            } catch(e) {}
                                            safeDispatch("DOM", "INTERNAL", JSON.stringify({
                                                type: "DOM_MULTIPLIER_UPDATE",
                                                multiplier: mMatch[1],
                                                rawText: txt
                                            }), txt.length);
                                            break;
                                        }
                                    } else if (txt.toUpperCase() === "FLEW AWAY!" || txt.toUpperCase() === "FLEW AWAY" || txt.indexOf("FLEW-AWAY") !== -1 || txt.indexOf("CRASHED") !== -1) {
                                        window.__aviatorCurrentMult = 0.0;
                                        window.__aviatorFlightActive = false;
                                        try {
                                            if (window.top && window.top !== window) {
                                                window.top.postMessage({ __aviatorSyncMult: 0.0, __aviatorFlightActive: false }, '*');
                                            }
                                        } catch(e) {}
                                        if (lastDomAviatorMult !== "CRASH") {
                                            lastDomAviatorMult = "CRASH";
                                            safeDispatch("DOM", "INTERNAL", JSON.stringify({
                                                type: "DOM_CRASH_SIGNAL",
                                                status: "crash",
                                                rawText: txt
                                            }), txt.length);
                                        }
                                    }
                                }
                            }
                        }
                    } catch(e) {}
                }
                setInterval(checkAviatorDom, 120);
            } catch(e) {}

        })();
    """.trimIndent()
}
