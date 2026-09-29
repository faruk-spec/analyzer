package com.example.aviatorsignallab.webview

object ScriptInjector {

    /**
     * Enhanced non-invasive client-side telemetry script.
     * Hooks WebSocket, Fetch, XMLHttpRequest, EventSource, postMessage, and DOM.
     * Supports child iframes, Socket.IO frames, and visual multiplier extraction.
     */
    val INJECTION_SCRIPT: String = """
        (function() {
            if (window.__aviatorLabInstrumented) return;
            window.__aviatorLabInstrumented = true;

            function safeDispatch(transport, direction, payload, size) {
                try {
                    var strPayload = typeof payload === 'string' ? payload : JSON.stringify(payload);
                    var payloadSize = size || (strPayload ? strPayload.length : 0);

                    if (window.AndroidBridge && window.AndroidBridge.onNetworkEvent) {
                        window.AndroidBridge.onNetworkEvent(transport, direction, strPayload || '', payloadSize);
                    } else if (window.top && window.top !== window) {
                        // Forward from cross-origin iframe to top window
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
                    } else if (window.top && window.top !== window) {
                        window.top.postMessage({ __aviatorLabDiag: true, msg: msg }, '*');
                    }
                } catch(e) {}
            }

            safeLog("Instrumenting client-side network hooks in: " + window.location.href);

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

            // 1. Hook WebSocket (Constructor, send, addEventListener, and .onmessage property)
            if (window.WebSocket) {
                var OriginalWebSocket = window.WebSocket;
                window.WebSocket = function(url, protocols) {
                    var ws = protocols ? new OriginalWebSocket(url, protocols) : new OriginalWebSocket(url);
                    safeLog("WebSocket connected to: " + url);
                    safeDispatch("WEBSOCKET", "INTERNAL", JSON.stringify({ event: "CONNECT", url: url }), 0);

                    // Hook send
                    var origSend = ws.send;
                    ws.send = function(data) {
                        try {
                            var size = (data && data.length) ? data.length : ((data && data.byteLength) ? data.byteLength : 0);
                            var preview = typeof data === 'string' ? data : "[BINARY_OUT " + size + " bytes]";
                            safeDispatch("WEBSOCKET", "OUTGOING", preview, size);
                        } catch(e) {}
                        return origSend.apply(this, arguments);
                    };

                    // Handler for incoming messages
                    function handleIncoming(event) {
                        try {
                            var data = event.data;
                            var size = (data && data.length) ? data.length : ((data && data.byteLength) ? data.byteLength : 0);
                            var preview = typeof data === 'string' ? data : "[BINARY_IN " + size + " bytes]";
                            safeDispatch("WEBSOCKET", "INCOMING", preview, size);
                        } catch(e) {}
                    }

                    ws.addEventListener('message', handleIncoming);

                    // Intercept direct assignments like ws.onmessage = function(...)
                    var internalOnMessage = null;
                    try {
                        Object.defineProperty(ws, 'onmessage', {
                            get: function() { return internalOnMessage; },
                            set: function(handler) {
                                internalOnMessage = function(evt) {
                                    handleIncoming(evt);
                                    if (typeof handler === 'function') {
                                        handler.apply(this, arguments);
                                    }
                                };
                            }
                        });
                    } catch(e) {}

                    return ws;
                };
                window.WebSocket.prototype = OriginalWebSocket.prototype;
            }

            // 2. Hook Fetch
            if (window.fetch) {
                var origFetch = window.fetch;
                window.fetch = function() {
                    var args = arguments;
                    var url = (typeof args[0] === 'string') ? args[0] : (args[0] && args[0].url ? args[0].url : "");
                    var options = args[1] || {};
                    var method = options.method || "GET";

                    if (options.body) {
                        var bodyStr = typeof options.body === 'string' ? options.body : "[BODY_DATA]";
                        safeDispatch("FETCH", "OUTGOING", JSON.stringify({ url: url, method: method, body: bodyStr }), bodyStr.length);
                    }

                    return origFetch.apply(this, args).then(function(response) {
                        try {
                            var clone = response.clone();
                            clone.text().then(function(bodyText) {
                                if (bodyText && bodyText.length > 0 && bodyText.length < 50000) {
                                    // Capture game telemetry responses
                                    if (bodyText.indexOf('multiplier') !== -1 || bodyText.indexOf('crash') !== -1 || bodyText.indexOf('round') !== -1 || url.indexOf('game') !== -1 || url.indexOf('aviator') !== -1) {
                                        safeDispatch("FETCH", "INCOMING", bodyText, bodyText.length);
                                    }
                                }
                            }).catch(function(){});
                        } catch(e) {}
                        return response;
                    });
                };
            }

            // 3. Hook XMLHttpRequest
            if (window.XMLHttpRequest) {
                var OrigXHR = window.XMLHttpRequest;
                var origOpen = OrigXHR.prototype.open;
                var origSend = OrigXHR.prototype.send;

                OrigXHR.prototype.open = function(method, url) {
                    this.__xhrUrl = url;
                    this.__xhrMethod = method;
                    return origOpen.apply(this, arguments);
                };

                OrigXHR.prototype.send = function(body) {
                    var self = this;
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
                    });

                    return origSend.apply(this, arguments);
                };
            }

            // 4. Hook postMessage communication
            window.addEventListener('message', function(event) {
                try {
                    if (event.data && !event.data.__aviatorLabEvent && !event.data.__aviatorLabDiag) {
                        var str = typeof event.data === 'string' ? event.data : JSON.stringify(event.data);
                        if (str && str.length > 0) {
                            safeDispatch("POST_MESSAGE", "INCOMING", str, str.length);
                        }
                    }
                } catch(e) {}
            });

            // 5. Universal Visual Multiplier & State Scanner (Runs in top frame and all iframes)
            var lastMultiplierSeen = "";
            var multRegex = /([0-9]{1,4}\.[0-9]{1,2})\s*[xX]/;

            setInterval(function() {
                try {
                    var doc = document;
                    // Scan candidate elements
                    var els = doc.querySelectorAll('div, span, p, h1, h2, h3, [class*="stage"], [class*="crash"], [class*="payout"], [class*="multiplier"], [class*="odds"], [class*="flew"]');
                    for (var i = 0; i < els.length; i++) {
                        var text = els[i].innerText || els[i].textContent;
                        if (!text || text.length > 40) continue;

                        var match = multRegex.exec(text);
                        if (match && match[1] !== lastMultiplierSeen) {
                            lastMultiplierSeen = match[1];
                            safeDispatch("DOM", "INTERNAL", JSON.stringify({
                                type: "DOM_MULTIPLIER_UPDATE",
                                multiplier: match[1],
                                rawText: text.trim()
                            }), text.length);
                            break;
                        }

                        // Check for crash keywords on screen
                        var lower = text.toLowerCase();
                        if (lower.indexOf("flew away") !== -1 || lower.indexOf("crashed") !== -1 || lower.indexOf("flew-away") !== -1) {
                            if (lastMultiplierSeen !== "CRASH") {
                                lastMultiplierSeen = "CRASH";
                                safeDispatch("DOM", "INTERNAL", JSON.stringify({
                                    type: "DOM_CRASH_SIGNAL",
                                    status: "crash",
                                    rawText: text.trim()
                                }), text.length);
                                break;
                            }
                        }
                    }
                } catch(e) {}
            }, 100);

            // 6. Child iframe recursive injector (for same-origin or reachable iframes)
            setInterval(function() {
                try {
                    var iframes = document.querySelectorAll('iframe');
                    for (var j = 0; j < iframes.length; j++) {
                        try {
                            var win = iframes[j].contentWindow;
                            if (win && !win.__aviatorLabInstrumented) {
                                safeLog("Injecting into child iframe: " + (iframes[j].src || "inline"));
                                win.eval("(" + arguments.callee.caller.toString() + ")()");
                            }
                        } catch(e) {
                            // Cross-origin: Handled by WebViewCompat.addDocumentStartJavaScript
                        }
                    }
                } catch(e) {}
            }, 1000);

        })();
    """.trimIndent()
}
