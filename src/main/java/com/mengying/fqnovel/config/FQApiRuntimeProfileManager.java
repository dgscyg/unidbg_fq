package com.mengying.fqnovel.config;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * FQ API 运行时 profile 管理。
 * 统一负责启动继承、校验和运行时原子切换。
 */
@Component
public class FQApiRuntimeProfileManager {

    private final FQApiProperties fqApiProperties;
    private volatile FQApiProperties.RuntimeProfile runtimeProfile;

    public FQApiRuntimeProfileManager(FQApiProperties fqApiProperties) {
        this.fqApiProperties = fqApiProperties;
    }

    @PostConstruct
    public synchronized void initRuntimeProfile() {
        FQApiDeviceProfiles.inheritRuntimeDefaults(fqApiProperties);
        FQApiDeviceProfiles.validateRuntimeConfiguration(fqApiProperties);
        refreshRuntimeProfileLocked();
    }

    public FQApiProperties.RuntimeProfile getRuntimeProfile() {
        FQApiProperties.RuntimeProfile snapshot = runtimeProfile;
        if (snapshot != null) {
            return snapshot;
        }
        synchronized (this) {
            if (runtimeProfile == null) {
                FQApiDeviceProfiles.inheritRuntimeDefaults(fqApiProperties);
                FQApiDeviceProfiles.validateRuntimeConfiguration(fqApiProperties);
                refreshRuntimeProfileLocked();
            }
            return runtimeProfile;
        }
    }

    public boolean hasRuntimeProfile() {
        return FQApiDeviceProfiles.hasRequiredRuntimeConfiguration(fqApiProperties);
    }

    public synchronized void applyRuntimeProfile(String userAgent, String cookie, FQApiProperties.Device device) {
        String normalizedUserAgent = FQApiDeviceProfiles.normalizeNullable(userAgent);
        if (normalizedUserAgent != null) {
            fqApiProperties.setUserAgent(normalizedUserAgent);
        }
        String normalizedCookie = FQApiDeviceProfiles.normalizeNullable(cookie);
        if (normalizedCookie != null) {
            fqApiProperties.setCookie(normalizedCookie);
        }

        FQApiProperties.Device runtimeDevice = FQApiDeviceProfiles.copyDevice(deviceOrCurrent(device));
        FQApiDeviceProfiles.validateRequiredDevice(runtimeDevice, "fq.api.device");
        if (device != null) {
            fqApiProperties.setDevice(runtimeDevice);
        }

        refreshRuntimeProfileLocked();
    }

    public synchronized void clearRuntimeProfile() {
        fqApiProperties.setUserAgent(null);
        fqApiProperties.setCookie(null);
        fqApiProperties.setDevice(null);
        refreshRuntimeProfileLocked();
    }

    private void refreshRuntimeProfileLocked() {
        FQApiProperties.Device runtimeDevice = FQApiDeviceProfiles.hasRequiredDeviceFields(fqApiProperties.getDevice())
            ? fqApiProperties.getDevice()
            : null;
        this.runtimeProfile = FQApiProperties.RuntimeProfile.of(
            fqApiProperties.getUserAgent(),
            fqApiProperties.getCookie(),
            runtimeDevice
        );
    }

    private FQApiProperties.Device deviceOrCurrent(FQApiProperties.Device preferred) {
        return preferred == null ? fqApiProperties.getDevice() : preferred;
    }
}
