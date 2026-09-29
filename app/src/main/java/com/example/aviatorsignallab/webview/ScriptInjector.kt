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
                            // FAST-PATH: Detect crash packet via raw string matching BEFORE
                            // any JSON.stringify/parsing. This fires ~10-20ms ahead of the
                            // normal pipeline, giving the alert a head start over the game's
                            // own "Flew Away" rendering.
                            if (direction === 'INCOMING' && data.indexOf('"sta":3') !== -1 && data.indexOf('"cmd":84') !== -1) {
                                try {
                                    var mulMatch = data.match(/"mul"\s*:\s*"?([0-9]+\.?[0-9]*)\"?/);
                                    var mulStr = mulMatch ? mulMatch[1] : '0';
                                    if (window.AndroidBridge && window.AndroidBridge.onCrashFastPath) {
                                        window.AndroidBridge.onCrashFastPath(mulStr);
                                    }
                                } catch(fastErr) {}
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
                        if (typeof text === 'string' && text.length > 0 && text.length < 35 && isGameContext()) {
                            var trimmed = text.trim();
                            var upper = trimmed.toUpperCase();
                            if (upper === "FLEW AWAY!" || upper === "FLEW AWAY" || upper.indexOf("FLEW-AWAY") !== -1 || upper.indexOf("CRASHED") !== -1) {
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
                                        safeDispatch("FETCH", "INCOMING", bodyText, bodyText.length);
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
                                        safeDispatch("XHR", "INCOMING", text, text.length);
                                    }
                                }
                            } catch(e) {}
                        }, false);
                    } catch(e) {}

                    return origSend.apply(this, arguments);
                };
            }

        })();
    """.trimIndent()
}
