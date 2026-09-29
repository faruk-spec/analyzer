package com.example.aviatorsignallab.webview

object ScriptInjector {

    /**
     * Stealth client-side telemetry script.
     * Hooks WebSocket (JSON, Blobs, ArrayBuffers), Canvas 2D fillText, Fetch, XHR, and DOM.
     * Extracts multipliers, round IDs, and crash signals in real-time across all frames.
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

            // 1. Stealth Hook for WebSocket (Decodes Text, Blobs, and ArrayBuffers)
            if (window.WebSocket && !window.__aviatorWsHooked) {
                window.__aviatorWsHooked = true;
                var NativeWebSocket = window.WebSocket;

                function processWsMessage(direction, data) {
                    try {
                        if (typeof data === 'string') {
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

            // 2. Canvas 2D Real-Time Multiplier & Crash Interceptor (Aviator plane renderer)
            if (window.CanvasRenderingContext2D && !window.__aviatorCanvasHooked) {
                window.__aviatorCanvasHooked = true;
                var origFillText = CanvasRenderingContext2D.prototype.fillText;
                var lastCanvasMult = "";
                var canvasMultRegex = /([0-9]{1,4}\.[0-9]{1,2})\s*[xX]?/;

                CanvasRenderingContext2D.prototype.fillText = function(text, x, y, maxWidth) {
                    try {
                        if (typeof text === 'string' && text.length > 0 && text.length < 35) {
                            var trimmed = text.trim();
                            var upper = trimmed.toUpperCase();
                            if (upper.indexOf("FLEW AWAY") !== -1 || upper.indexOf("CRASH") !== -1 || upper.indexOf("FLEW-AWAY") !== -1) {
                                if (lastCanvasMult !== "CRASH") {
                                    lastCanvasMult = "CRASH";
                                    safeDispatch("DOM", "INTERNAL", JSON.stringify({
                                        type: "DOM_CRASH_SIGNAL",
                                        status: "crash",
                                        rawText: trimmed
                                    }), trimmed.length);
                                }
                            } else {
                                var match = canvasMultRegex.exec(trimmed);
                                if (match && match[1]) {
                                    var num = parseFloat(match[1]);
                                    if (num >= 1.0 && num <= 100000.0 && match[1] !== lastCanvasMult) {
                                        lastCanvasMult = match[1];
                                        safeDispatch("DOM", "INTERNAL", JSON.stringify({
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

            // 3. Stealth Hook for Fetch
            if (window.fetch && !window.__aviatorFetchHooked) {
                window.__aviatorFetchHooked = true;
                var origFetch = window.fetch;
                window.fetch = function() {
                    var args = arguments;
                    try {
                        var url = (typeof args[0] === 'string') ? args[0] : (args[0] && args[0].url ? args[0].url : "");
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
                                    if (bodyText.indexOf('multiplier') !== -1 || bodyText.indexOf('crash') !== -1 || bodyText.indexOf('round') !== -1 || (url && url.indexOf('game') !== -1)) {
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

            // 4. Stealth Hook for XMLHttpRequest
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
                                    if (text.indexOf('multiplier') !== -1 || text.indexOf('crash') !== -1 || text.indexOf('round') !== -1 || (self.__xhrUrl && self.__xhrUrl.indexOf('game') !== -1)) {
                                        safeDispatch("XHR", "INCOMING", text, text.length);
                                    }
                                }
                            } catch(e) {}
                        }, false);
                    } catch(e) {}

                    return origSend.apply(this, arguments);
                };
            }

            // 5. Visual Multiplier, Round ID & State Scanner
            var lastDomMultiplier = "";
            var lastDomRoundId = "";
            var domMultRegex = /([0-9]{1,4}\.[0-9]{1,2})\s*[xX]?/;
            var domRoundRegex = /(?:round|issue|#|game|id)\s*[:#]?\s*([0-9]{5,15})/i;

            setInterval(function() {
                try {
                    var doc = document;
                    var els = doc.querySelectorAll('div, span, p, h1, h2, h3, text, b, strong, em, [class*="stage"], [class*="crash"], [class*="payout"], [class*="multiplier"], [class*="odds"], [class*="flew"], [class*="bubble"]');
                    for (var i = 0; i < els.length; i++) {
                        var text = els[i].innerText || els[i].textContent;
                        if (!text || text.length > 40) continue;

                        var trimmed = text.trim();

                        // Scan for Round ID
                        var rMatch = domRoundRegex.exec(trimmed);
                        if (rMatch && rMatch[1] && rMatch[1] !== lastDomRoundId) {
                            lastDomRoundId = rMatch[1];
                            safeDispatch("DOM", "INTERNAL", JSON.stringify({
                                type: "DOM_ROUND_ID_UPDATE",
                                round_id: rMatch[1],
                                rawText: trimmed
                            }), trimmed.length);
                        }

                        // Scan for Multiplier
                        var mMatch = domMultRegex.exec(trimmed);
                        if (mMatch && mMatch[1]) {
                            var n = parseFloat(mMatch[1]);
                            if (n >= 1.0 && n <= 100000.0 && mMatch[1] !== lastDomMultiplier) {
                                lastDomMultiplier = mMatch[1];
                                safeDispatch("DOM", "INTERNAL", JSON.stringify({
                                    type: "DOM_MULTIPLIER_UPDATE",
                                    multiplier: mMatch[1],
                                    rawText: trimmed
                                }), trimmed.length);
                                break;
                            }
                        }

                        // Scan for Crash keyword
                        var lower = trimmed.toLowerCase();
                        if (lower.indexOf("flew away") !== -1 || lower.indexOf("crashed") !== -1 || lower.indexOf("flew-away") !== -1) {
                            if (lastDomMultiplier !== "CRASH") {
                                lastDomMultiplier = "CRASH";
                                safeDispatch("DOM", "INTERNAL", JSON.stringify({
                                    type: "DOM_CRASH_SIGNAL",
                                    status: "crash",
                                    rawText: trimmed
                                }), trimmed.length);
                                break;
                            }
                        }
                    }
                } catch(e) {}
            }, 100);

        })();
    """.trimIndent()
}
