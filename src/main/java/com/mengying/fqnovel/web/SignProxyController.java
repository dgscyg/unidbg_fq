package com.mengying.fqnovel.web;

import com.mengying.fqnovel.service.DeviceRegistrationService;
import com.mengying.fqnovel.service.FQEncryptServiceWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sign proxy controller - replaces sg.52dns.cc API endpoints.
 * <p>
 * Provides three endpoints that match the sg.52dns.cc API contract exactly,
 * so the fqfix book source's JS code works by only changing sixgodHost.
 * <p>
 * Endpoints:
 * - POST /api/sign              — URL signing with X-Argus/X-Gorgon headers
 * - POST /api/device/build-register   — device registration (TT encrypted)
 * - POST /api/device/build-activate   — device activation (TT encrypted)
 */
@RestController
public class SignProxyController {

    private static final Logger log = LoggerFactory.getLogger(SignProxyController.class);

    private final FQEncryptServiceWorker encryptWorker;
    private final DeviceRegistrationService deviceRegistrationService;

    public SignProxyController(FQEncryptServiceWorker encryptWorker) {
        this.encryptWorker = encryptWorker;
        this.deviceRegistrationService = new DeviceRegistrationService();
    }

    /**
     * POST /api/sign
     * <p>
     * Request format (matching sg.52dns.cc):
     * <pre>
     * {
     *   "headers": {"Content-Type": "application/json"},
     *   "body": {
     *     "user": "fq0430",
     *     "auth": "...",
     *     "url": "https://reading.snssdk.com/reading/bookapi/...",
     *     "params": {"key": "value", ...},
     *     "device": {...},
     *     "body": null,
     *     "cookie": "sessionid=...",
     *     "header": null
     *   },
     *   "method": "POST"
     * }
     * </pre>
     * <p>
     * Response format:
     * <pre>
     * {
     *   "data": {
     *     "url": "signed URL with params",
     *     "options": {
     *       "headers": {"X-Argus": "...", "X-Gorgon": "...", ...},
     *       "method": "GET",
     *       "body": null
     *     }
     *   }
     * }
     * </pre>
     */
    @SuppressWarnings("unchecked")
    @PostMapping(value = "/api/sign",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> sign(@RequestBody Map<String, Object> request) {
        try {
            // Extract body from request (Legado java.ajax format: {headers, body, method})
            Map<String, Object> signBody;
            if (request.containsKey("body") && request.get("body") instanceof Map) {
                signBody = (Map<String, Object>) request.get("body");
            } else {
                signBody = request; // bare format
            }

            String url = (String) signBody.get("url");
            Map<String, Object> device = (Map<String, Object>) signBody.get("device");
            Map<String, String> params = null;
            Object paramsObj = signBody.get("params");
            if (paramsObj instanceof Map) {
                params = (Map<String, String>) paramsObj;
            }
            String cookie = (String) signBody.get("cookie");
            Object bodyParam = signBody.get("body");
            String headerParam = (String) signBody.get("header");

            if (url == null || url.isEmpty()) {
                return errorResponse(-1, "url is required");
            }

            // Build full URL with params
            String fullUrl = url;
            if (params != null && !params.isEmpty()) {
                StringBuilder sb = new StringBuilder(url);
                if (!url.contains("?")) sb.append("?");
                else if (!url.endsWith("&")) sb.append("&");
                boolean first = !url.contains("?");
                for (Map.Entry<String, String> e : params.entrySet()) {
                    if (!first) sb.append("&");
                    sb.append(e.getKey()).append("=").append(e.getValue());
                    first = false;
                }
                fullUrl = sb.toString();
            }

            Map<String, String> signHeadersMap = new LinkedHashMap<>();
            signHeadersMap.put("User-Agent", "okhttp/3.12.13.4");
            if (cookie != null && !cookie.isEmpty()) {
                signHeadersMap.put("Cookie", cookie);
            }

            // Generate signature headers
            Map<String, String> signedHeaders;
            try {
                signedHeaders = encryptWorker.generateSignatureHeadersSync(
                    fullUrl, signHeadersMap);
            } catch (Exception sigErr) {
                log.warn("sign: signing failed for url={}, falling back to unsigned", url, sigErr);
                signedHeaders = new LinkedHashMap<>();
            }

            // Determine method
            boolean hasBody = bodyParam != null && !bodyParam.toString().isEmpty();
            String method = hasBody ? "POST" : "GET";

            // Build headers map for response
            Map<String, String> responseHeaders = new LinkedHashMap<>();
            responseHeaders.put("User-Agent", "okhttp/3.12.13.4");
            if (cookie != null && !cookie.isEmpty()) {
                responseHeaders.put("Cookie", cookie);
            }
            responseHeaders.putAll(signedHeaders);

            // Build response matching sg.52dns.cc format
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("headers", responseHeaders);
            options.put("method", method);
            if (hasBody) {
                options.put("body", bodyParam.toString());
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("url", fullUrl);
            data.put("options", options);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("data", data);
            return result;

        } catch (Exception e) {
            log.error("sign failed", e);
            return errorResponse(-1, "sign error: " + e.getMessage());
        }
    }

    /**
     * POST /api/device/build-register
     * <p>
     * Request format: {body: {user, auth, device: {...}}}
     * Response format: {data: {url, device, options: {body, headers, method}}}
     */
    @SuppressWarnings("unchecked")
    @PostMapping(value = "/api/device/build-register",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> buildRegister(@RequestBody Map<String, Object> request) {
        try {
            Map<String, Object> regBody;
            if (request.containsKey("body") && request.get("body") instanceof Map) {
                regBody = (Map<String, Object>) request.get("body");
            } else {
                regBody = request;
            }

            Map<String, Object> device = (Map<String, Object>) regBody.get("device");
            if (device == null) {
                return errorResponse(-1, "device is required");
            }

            // Log: user/auth are ignored (local service doesn't need auth)
            Object user = regBody.get("user");
            Object auth = regBody.get("auth");

            Map<String, Object> regResult = deviceRegistrationService.buildRegisterRequest(device);

            // Response format matching sg.52dns.cc
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("url", regResult.get("url"));
            data.put("device", regResult.get("device"));
            data.put("options", regResult.get("options"));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("data", data);
            return result;

        } catch (Exception e) {
            log.error("build-register failed", e);
            return errorResponse(-1, "build-register error: " + e.getMessage());
        }
    }

    /**
     * POST /api/device/build-activate
     * <p>
     * Same format as build-register.
     */
    @SuppressWarnings("unchecked")
    @PostMapping(value = "/api/device/build-activate",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> buildActivate(@RequestBody Map<String, Object> request) {
        try {
            Map<String, Object> actBody;
            if (request.containsKey("body") && request.get("body") instanceof Map) {
                actBody = (Map<String, Object>) request.get("body");
            } else {
                actBody = request;
            }

            Map<String, Object> device = (Map<String, Object>) actBody.get("device");
            if (device == null) {
                return errorResponse(-1, "device is required");
            }

            Map<String, Object> actResult = deviceRegistrationService.buildActivateRequest(device);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("url", actResult.get("url"));
            data.put("options", actResult.get("options"));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("data", data);
            return result;

        } catch (Exception e) {
            log.error("build-activate failed", e);
            return errorResponse(-1, "build-activate error: " + e.getMessage());
        }
    }

    private static Map<String, Object> errorResponse(int code, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("data", Map.of("code", code, "message", message));
        return result;
    }
}