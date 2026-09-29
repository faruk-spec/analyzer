package com.example.aviatorsignallab.webview

object ScriptInjector {

    /**
     * Complete non-invasive client-side telemetry script.
     * Hooks WebSocket, Fetch, XMLHttpRequest, EventSource, and postMessage.
     * Captures only client-visible network metadata and game state messages.
     * Strictly avoids modifying or injecting any traffic.
     */
    val INJECTION_SCRIPT: String = """
        (function() {
            if (window.__aviatorLabInstrumented) return;
            window.__aviatorLabInstrumented = true;

            function safeDispatch(transport, direction, payload, size) {
                try {
                    if (window.AndroidBridge && window.AndroidBridge.onNetworkEvent) {
                        var strPayload = typeof payload === 'string' ? payload : JSON.stringify(payload);
                        window.AndroidBridge.onNetworkEvent(transport, direction, strPayload || '', size || (strPayload ? strPayload.length : 0));
                    }
                } catch(e) {}
            }

            function safeLog(msg) {
                try {
                    if (window.AndroidBridge && window.AndroidBridge.onDiagnostics) {
                        window.AndroidBridge.onDiagnostics(msg);
                    }
                } catch(e) {}
            }

            safeLog("Instrumenting client-side network hooks in context: " + window.location.href);

            // 1. Hook WebSocket
            if (window.WebSocket) {
                var OriginalWebSocket = window.WebSocket;
                window.WebSocket = function(url, protocols) {
                    var ws = protocols ? new OriginalWebSocket(url, protocols) : new OriginalWebSocket(url);
                    safeLog("WebSocket connection opened to: " + url);
                    safeDispatch("WEBSOCKET", "INTERNAL", JSON.stringify({ event: "CONNECT", url: url }), 0);

                    // Hook send (outgoing)
                    var origSend = ws.send;
                    ws.send = function(data) {
                        try {
                            var size = (data && data.length) ? data.length : ((data && data.byteLength) ? data.byteLength : 0);
                            var preview = typeof data === 'string' ? data : "[BINARY_DATA " + size + " bytes]";
                            safeDispatch("WEBSOCKET", "OUTGOING", preview, size);
                        } catch(e) {}
                        return origSend.apply(this, arguments);
                    };

                    // Hook message listener
                    ws.addEventListener('message', function(event) {
                        try {
                            var data = event.data;
                            var size = (data && data.length) ? data.length : ((data && data.byteLength) ? data.byteLength : 0);
                            var preview = typeof data === 'string' ? data : "[BINARY_DATA " + size + " bytes]";
                            safeDispatch("WEBSOCKET", "INCOMING", preview, size);
                        } catch(e) {}
                    });

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
                                if (url.indexOf("aviator") !== -1 || url.indexOf("game") !== -1 || bodyText.indexOf("multiplier") !== -1 || bodyText.indexOf("crash") !== -1) {
                                    safeDispatch("FETCH", "INCOMING", bodyText, bodyText.length);
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
                            if (text && (self.__xhrUrl.indexOf("aviator") !== -1 || text.indexOf("multiplier") !== -1 || text.indexOf("crash") !== -1)) {
                                safeDispatch("XHR", "INCOMING", text, text.length);
                            }
                        } catch(e) {}
                    });

                    return origSend.apply(this, arguments);
                };
            }

            // 4. Hook postMessage (for cross-iframe communication)
            window.addEventListener('message', function(event) {
                try {
                    var data = event.data;
                    var str = typeof data === 'string' ? data : JSON.stringify(data);
                    if (str && (str.indexOf("multiplier") !== -1 || str.indexOf("crash") !== -1 || str.indexOf("round") !== -1 || str.indexOf("stage") !== -1)) {
                        safeDispatch("POST_MESSAGE", "INCOMING", str, str.length);
                    }
                } catch(e) {}
            });

            // 5. DOM Stage Monitor
            var lastMultiplierText = "";
            setInterval(function() {
                try {
                    // Check for typical Aviator stage / multiplier elements
                    var candidateElements = document.querySelectorAll('.multiplier, .stage, .payout, [class*="stage"], [class*="crash"], [class*="payout"]');
                    for (var i = 0; i < candidateElements.length; i++) {
                        var txt = candidateElements[i].innerText || candidateElements[i].textContent;
                        if (txt && txt !== lastMultiplierText && (txt.indexOf('x') !== -1 || txt.indexOf('X') !== -1)) {
                            lastMultiplierText = txt;
                            safeDispatch("DOM", "INTERNAL", JSON.stringify({ type: "DOM_STAGE_UPDATE", text: txt }), txt.length);
                            break;
                        }
                    }
                } catch(e) {}
            }, 250);

        })();
    """.trimIndent()
}
