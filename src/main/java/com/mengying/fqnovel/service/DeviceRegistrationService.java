package com.mengying.fqnovel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mengying.fqnovel.crypto.TTEncrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Device Registration Service - ported from fq_device_register.py.
 * <p>
 * Handles the TT-encrypted device registration/activation flow for Fanqie Novel.
 * The encrypt path uses the custom TTEncrypt algorithm (gzip + custom block cipher).
 */
public class DeviceRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(DeviceRegistrationService.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final TTEncrypt ttEncrypt = new TTEncrypt();

    // App info for Fanqie Novel (API)
    private static final String[] API_APP_INFO = {
        "1967",                              // aid
        "68132",                             // version_code
        "googleplay",                        // channel
        "com.dragon.read.oversea.gp",        // package
        "6.8.1.32",                         // app_version
        "oppo_1967_64",                     // channel_detail
        "番茄免费小说",                       // display_name
        "novelapp",                         // app_name
    };

    // App info for Toutiao (TOU)
    private static final String[] TOU_APP_INFO = {
        "13",                                // aid
        "130700",                           // version_code
        "vivo_13_64",                       // channel
        "com.ss.android.article.news",      // package
        "13.7.0",                           // app_version
        "vivo_13_64",                       // channel_detail
        "今日头条",                          // display_name
        "news_article",                     // app_name
    };

    // Registration URL
    private static final String REGISTER_URL = "https://log5-applog.fqnovel.com/service/2/device_register/";

    /**
     * Build the device registration request for Fanqie Novel (API type).
     * Returns the encrypted binary body and headers needed for the registration POST.
     *
     * @param device the device info map (from book source JS)
     * @return map with {url, body_base64, x_ss_stub, headers} ready for the JS java.ajax call
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> buildRegisterRequest(Map<String, Object> device) throws Exception {
        return buildRegisterRequestForType(device, "API");
    }

    /**
     * Build the device register request for a specific type (API or TOU).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> buildRegisterRequestForType(Map<String, Object> device, String type) throws Exception {
        // Build version string from version code
        String versionCode = device.getOrDefault("version", "68132").toString();
        String versionStr = formatVersion(versionCode);

        // Select app info
        String[] appInfo = "TOU".equals(type) ? TOU_APP_INFO : API_APP_INFO;

        // Build register payload
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("magic_tag", "ss_app_log");
        payload.put("header", buildHeaderMap(device, appInfo, versionStr, "register"));
        payload.put("_gen_time", System.currentTimeMillis());

        Map<String, Object> deviceSection = new LinkedHashMap<>();
        deviceSection.put("oaid", device.getOrDefault("oaid", ""));
        deviceSection.put("openudid", device.getOrDefault("openudid", ""));
        deviceSection.put("device_brand", device.getOrDefault("device_brand", "Xiaomi"));
        deviceSection.put("device_model", device.getOrDefault("device_model", "2201123C"));
        deviceSection.put("os_api", device.getOrDefault("os_api", "33"));
        deviceSection.put("os_version", device.getOrDefault("os_version", "13"));
        deviceSection.put("rom_version", device.getOrDefault("rom_version", "V417IR+release-keys"));
        deviceSection.put("version", appInfo[1]);
        deviceSection.put("version_code", appInfo[1]);
        deviceSection.put("version_str", versionStr);
        deviceSection.put("aid", appInfo[0]);
        deviceSection.put("channel", appInfo[5]);
        deviceSection.put("display_name", appInfo[6]);
        deviceSection.put("package", appInfo[3]);
        deviceSection.put("app_name", appInfo[7]);
        deviceSection.put("update_version_code", appInfo[1]);
        deviceSection.put("manifest_version_code", appInfo[1]);
        deviceSection.put("app_version", appInfo[4]);
        deviceSection.put("app_version_minor", appInfo[4]);
        deviceSection.put("sdk_version", "3.7.0-rc.25-fanqie-xiaoshuo-opt");
        deviceSection.put("sdk_target_version", "29");
        deviceSection.put("git_hash", "5b6a0d3");
        deviceSection.put("sdk_flavor", "china");
        deviceSection.put("guest_mode", 0);
        deviceSection.put("is_system_app", 0);
        deviceSection.put("pre_installed_channel", "");
        deviceSection.put("not_request_sender", 0);

        payload.put("device", deviceSection);

        // Serialize to JSON
        String jsonPayload = mapper.writeValueAsString(payload);

        // TT Encrypt
        byte[] encrypted = ttEncrypt.encrypt(jsonPayload);

        // MD5 of encrypted body -> x-ss-stub header
        String xSsStub = md5Hex(encrypted);

        // Base64 encode encrypted body
        String bodyBase64 = Base64.getEncoder().encodeToString(encrypted);

        // Build response matching sg.52dns.cc format
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("url", REGISTER_URL + "?" + buildUrlParams(appInfo, device));
        result.put("device", device);

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("body", bodyBase64);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/octet-stream;tt-data=a");
        headers.put("x-ss-stub", xSsStub);
        headers.put("log-encode-type", "gzip");
        headers.put("x-ss-req-ticket", String.valueOf(System.currentTimeMillis()));
        headers.put("x-vc-bdturing-sdk-version", "3.7.2.cn");
        options.put("headers", headers);
        options.put("method", "POST");

        result.put("options", options);
        return result;
    }

    /**
     * Build the device activation request.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> buildActivateRequest(Map<String, Object> device) throws Exception {
        // Activate uses the same encrypted payload format as register
        return buildRegisterRequest(device);
    }

    // ============ Helpers ============

    private Map<String, Object> buildHeaderMap(Map<String, Object> device, String[] appInfo,
                                                String versionStr, String action) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("aid", appInfo[0]);
        header.put("version_code", appInfo[1]);
        header.put("channel", appInfo[2]);
        header.put("package", appInfo[3]);
        header.put("app_version", appInfo[4]);
        header.put("update_version_code", appInfo[1]);
        header.put("manifest_version_code", appInfo[1]);
        header.put("app_version_minor", appInfo[4]);
        header.put("sdk_version", "3.7.0-rc.25-fanqie-xiaoshuo-opt");
        header.put("sdk_target_version", "29");
        header.put("git_hash", "5b6a0d3");
        header.put("sdk_flavor", "china");
        header.put("guest_mode", 0);
        header.put("is_system_app", 0);
        header.put("pre_installed_channel", "");
        header.put("not_request_sender", 0);
        header.put("display_name", appInfo[6]);
        header.put("version", appInfo[1]);
        header.put("version_str", versionStr);
        header.put("app_name", appInfo[7]);
        header.put("action", action);
        return header;
    }

    private String buildUrlParams(String[] appInfo, Map<String, Object> device) {
        StringBuilder sb = new StringBuilder();
        sb.append("aid=").append(appInfo[0]);
        sb.append("&version_code=").append(appInfo[1]);
        sb.append("&channel=").append(appInfo[2]);
        sb.append("&package=").append(appInfo[3]);
        sb.append("&_rticket=").append(System.currentTimeMillis());
        sb.append("&use_store_region_cookie=1");
        sb.append("&okhttp_version=4.2.137.76-fanqie");
        sb.append("&tt_data=a");
        return sb.toString();
    }

    private static String formatVersion(String versionCode) {
        // "68132" -> "6.8.1.3.2" or similar
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < versionCode.length(); i++) {
            if (i > 0) sb.append('.');
            sb.append(versionCode.charAt(i));
        }
        return sb.toString();
    }

    private static String md5Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02X", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("MD5 failed", e);
        }
    }
}