package com.mengying.fqnovel.config;

import com.mengying.fqnovel.utils.Texts;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * FQ API 配置属性。
 * 仅负责配置绑定；运行时快照、校验和设备池启动继承由专用组件处理。
 */
@Component
@ConfigurationProperties(prefix = "fq.api")
public class FQApiProperties {

    private String baseUrl;
    private String userAgent;
    private String cookie;
    private Device device = new Device();
    private List<DeviceProfile> devicePool = new ArrayList<>();
    private int devicePoolSize = 3;
    private boolean devicePoolShuffleOnStartup = true;
    private String devicePoolStartupName;
    private boolean devicePoolProbeOnStartup = false;
    private int devicePoolProbeMaxAttempts = 3;
    private long deviceRotateCooldownMs = 30_000L;
    private boolean devicePoolAllowEmpty = true;
    private long devicePoolRiskCooldownMs = 12 * 60 * 60 * 1000L;
    private int registerKeyCacheMaxEntries = 32;
    private long registerKeyCacheTtlMs = 60 * 60 * 1000L;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = Texts.trimToNull(baseUrl);
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = Texts.trimToNull(userAgent);
    }

    public String getCookie() {
        return cookie;
    }

    public void setCookie(String cookie) {
        this.cookie = Texts.trimToNull(cookie);
    }

    public Device getDevice() {
        return device;
    }

    public void setDevice(Device device) {
        this.device = device == null ? new Device() : device;
    }

    public List<DeviceProfile> getDevicePool() {
        return devicePool;
    }

    public void setDevicePool(List<DeviceProfile> devicePool) {
        this.devicePool = devicePool == null ? new ArrayList<>() : devicePool;
    }

    public int getDevicePoolSize() {
        return devicePoolSize;
    }

    public void setDevicePoolSize(int devicePoolSize) {
        this.devicePoolSize = devicePoolSize;
    }

    public boolean isDevicePoolShuffleOnStartup() {
        return devicePoolShuffleOnStartup;
    }

    public void setDevicePoolShuffleOnStartup(boolean devicePoolShuffleOnStartup) {
        this.devicePoolShuffleOnStartup = devicePoolShuffleOnStartup;
    }

    public String getDevicePoolStartupName() {
        return devicePoolStartupName;
    }

    public void setDevicePoolStartupName(String devicePoolStartupName) {
        this.devicePoolStartupName = devicePoolStartupName;
    }

    public boolean isDevicePoolProbeOnStartup() {
        return devicePoolProbeOnStartup;
    }

    public void setDevicePoolProbeOnStartup(boolean devicePoolProbeOnStartup) {
        this.devicePoolProbeOnStartup = devicePoolProbeOnStartup;
    }

    public int getDevicePoolProbeMaxAttempts() {
        return devicePoolProbeMaxAttempts;
    }

    public void setDevicePoolProbeMaxAttempts(int devicePoolProbeMaxAttempts) {
        this.devicePoolProbeMaxAttempts = devicePoolProbeMaxAttempts;
    }

    public long getDeviceRotateCooldownMs() {
        return deviceRotateCooldownMs;
    }

    public void setDeviceRotateCooldownMs(long deviceRotateCooldownMs) {
        this.deviceRotateCooldownMs = deviceRotateCooldownMs;
    }

    public boolean isDevicePoolAllowEmpty() {
        return devicePoolAllowEmpty;
    }

    public void setDevicePoolAllowEmpty(boolean devicePoolAllowEmpty) {
        this.devicePoolAllowEmpty = devicePoolAllowEmpty;
    }

    public long getDevicePoolRiskCooldownMs() {
        return devicePoolRiskCooldownMs;
    }

    public void setDevicePoolRiskCooldownMs(long devicePoolRiskCooldownMs) {
        this.devicePoolRiskCooldownMs = devicePoolRiskCooldownMs;
    }

    public int getRegisterKeyCacheMaxEntries() {
        return registerKeyCacheMaxEntries;
    }

    public void setRegisterKeyCacheMaxEntries(int registerKeyCacheMaxEntries) {
        this.registerKeyCacheMaxEntries = registerKeyCacheMaxEntries;
    }

    public long getRegisterKeyCacheTtlMs() {
        return registerKeyCacheTtlMs;
    }

    public void setRegisterKeyCacheTtlMs(long registerKeyCacheTtlMs) {
        this.registerKeyCacheTtlMs = registerKeyCacheTtlMs;
    }

    public static final class RuntimeProfile {
        private final String userAgent;
        private final String cookie;
        private final Device device;

        private RuntimeProfile(String userAgent, String cookie, Device device) {
            this.userAgent = userAgent;
            this.cookie = cookie;
            this.device = device == null ? null : FQApiDeviceProfiles.copyDevice(device);
        }

        static RuntimeProfile of(String userAgent, String cookie, Device device) {
            return new RuntimeProfile(
                FQApiDeviceProfiles.normalizeNullable(userAgent),
                FQApiDeviceProfiles.normalizeNullable(cookie),
                device
            );
        }

        public String getUserAgent() {
            return userAgent;
        }

        public String getCookie() {
            return cookie;
        }

        public Device getDevice() {
            return this.device == null ? null : FQApiDeviceProfiles.copyDevice(this.device);
        }

        /**
         * 高频只读场景使用：返回运行时快照中的设备引用，避免重复拷贝分配。
         * 调用方必须只读，不得修改返回对象。
         */
        public Device getDeviceUnsafe() {
            return this.device;
        }
    }

    public static class DeviceProfile {
        private String name;
        private String userAgent;
        private String cookie;
        private Device device = new Device();

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getUserAgent() {
            return userAgent;
        }

        public void setUserAgent(String userAgent) {
            this.userAgent = userAgent;
        }

        public String getCookie() {
            return cookie;
        }

        public void setCookie(String cookie) {
            this.cookie = cookie;
        }

        public Device getDevice() {
            return device;
        }

        public void setDevice(Device device) {
            this.device = device;
        }
    }

    public static class Device {
        private String cdid;
        private String installId;
        private String deviceId;
        private String aid;
        private String versionCode;
        private String versionName;
        private String updateVersionCode;
        private String deviceType;
        private String deviceBrand;
        private String romVersion;
        private String resolution;
        private String dpi;
        private String hostAbi;
        private String osVersion;
        private String osApi;

        public String getCdid() {
            return cdid;
        }

        public void setCdid(String cdid) {
            this.cdid = cdid;
        }

        public String getInstallId() {
            return installId;
        }

        public void setInstallId(String installId) {
            this.installId = installId;
        }

        public String getDeviceId() {
            return deviceId;
        }

        public void setDeviceId(String deviceId) {
            this.deviceId = deviceId;
        }

        public String getAid() {
            return aid;
        }

        public void setAid(String aid) {
            this.aid = aid;
        }

        public String getVersionCode() {
            return versionCode;
        }

        public void setVersionCode(String versionCode) {
            this.versionCode = versionCode;
        }

        public String getVersionName() {
            return versionName;
        }

        public void setVersionName(String versionName) {
            this.versionName = versionName;
        }

        public String getUpdateVersionCode() {
            return updateVersionCode;
        }

        public void setUpdateVersionCode(String updateVersionCode) {
            this.updateVersionCode = updateVersionCode;
        }

        public String getDeviceType() {
            return deviceType;
        }

        public void setDeviceType(String deviceType) {
            this.deviceType = deviceType;
        }

        public String getDeviceBrand() {
            return deviceBrand;
        }

        public void setDeviceBrand(String deviceBrand) {
            this.deviceBrand = deviceBrand;
        }

        public String getRomVersion() {
            return romVersion;
        }

        public void setRomVersion(String romVersion) {
            this.romVersion = romVersion;
        }

        public String getResolution() {
            return resolution;
        }

        public void setResolution(String resolution) {
            this.resolution = resolution;
        }

        public String getDpi() {
            return dpi;
        }

        public void setDpi(String dpi) {
            this.dpi = dpi;
        }

        public String getHostAbi() {
            return hostAbi;
        }

        public void setHostAbi(String hostAbi) {
            this.hostAbi = hostAbi;
        }

        public String getOsVersion() {
            return osVersion;
        }

        public void setOsVersion(String osVersion) {
            this.osVersion = osVersion;
        }

        public String getOsApi() {
            return osApi;
        }

        public void setOsApi(String osApi) {
            this.osApi = osApi;
        }
    }
}
