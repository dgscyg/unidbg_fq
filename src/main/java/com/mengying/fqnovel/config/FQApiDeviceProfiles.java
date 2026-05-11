package com.mengying.fqnovel.config;

import com.mengying.fqnovel.utils.Texts;

import java.util.List;
import java.util.function.Consumer;

public final class FQApiDeviceProfiles {

    private static final String DEVICE_CONFIG_PREFIX = "fq.api.device";
    private static final String DEVICE_POOL_PREFIX = "fq.api.device-pool";

    private FQApiDeviceProfiles() {
    }

    static String normalizeNullable(String value) {
        return Texts.trimToNull(value);
    }

    public static FQApiProperties.Device copyDevice(FQApiProperties.Device source) {
        FQApiProperties.Device target = new FQApiProperties.Device();
        if (source == null) {
            return target;
        }

        copyIdentityFields(source, target);
        copyVersionFields(source, target);
        copyHardwareFields(source, target);
        copySystemFields(source, target);
        return target;
    }

    static FQApiProperties.Device mergeDevice(FQApiProperties.Device primary, FQApiProperties.Device fallback) {
        FQApiProperties.Device target = new FQApiProperties.Device();
        mergeIdentityFields(primary, fallback, target);
        mergeVersionFields(primary, fallback, target);
        mergeHardwareFields(primary, fallback, target);
        mergeSystemFields(primary, fallback, target);
        return target;
    }

    static void inheritRuntimeDefaults(FQApiProperties properties) {
        if (properties == null) {
            return;
        }

        FQApiProperties.DeviceProfile bootstrapProfile = resolveBootstrapProfile(properties);
        if (bootstrapProfile == null) {
            return;
        }

        properties.setUserAgent(Texts.defaultIfBlank(
            normalizeNullable(properties.getUserAgent()),
            normalizeNullable(bootstrapProfile.getUserAgent())
        ));
        properties.setCookie(Texts.defaultIfBlank(
            normalizeNullable(properties.getCookie()),
            normalizeNullable(bootstrapProfile.getCookie())
        ));
        properties.setDevice(mergeDevice(properties.getDevice(), bootstrapProfile.getDevice()));
    }

    static void validateRuntimeConfiguration(FQApiProperties properties) {
        if (properties == null) {
            throw new IllegalStateException("fq.api 配置未初始化");
        }
        requireTextValue(properties.getBaseUrl(), "fq.api.base-url");
        if (hasAnyRuntimeConfiguration(properties)) {
            requireTextValue(properties.getUserAgent(), "fq.api.user-agent");
            requireTextValue(properties.getCookie(), "fq.api.cookie");
            validateRequiredDevice(properties.getDevice(), DEVICE_CONFIG_PREFIX);
        } else if (!properties.isDevicePoolAllowEmpty()) {
            throw new IllegalStateException("缺少可用设备配置，且 fq.api.device-pool-allow-empty=false");
        }
        validateDevicePool(properties.getDevicePool());
    }

    static void validateRequiredDevice(FQApiProperties.Device device, String prefix) {
        if (device == null) {
            throw new IllegalStateException("缺少设备配置: " + prefix);
        }
        requirePositiveNumericDeviceValue(device.getInstallId(), prefix + ".install-id");
        requirePositiveNumericDeviceValue(device.getDeviceId(), prefix + ".device-id");
        requireDeviceValue(device.getAid(), prefix + ".aid");
        requireDeviceValue(device.getUpdateVersionCode(), prefix + ".update-version-code");
    }

    static boolean hasAnyRuntimeConfiguration(FQApiProperties properties) {
        if (properties == null) {
            return false;
        }
        return Texts.hasText(properties.getUserAgent())
            || Texts.hasText(properties.getCookie())
            || hasAnyDeviceValue(properties.getDevice());
    }

    static boolean hasRequiredRuntimeConfiguration(FQApiProperties properties) {
        return properties != null
            && Texts.hasText(properties.getUserAgent())
            && Texts.hasText(properties.getCookie())
            && hasRequiredDeviceFields(properties.getDevice());
    }

    static boolean hasRequiredDeviceFields(FQApiProperties.Device device) {
        return device != null
            && isPositiveNumericDeviceValue(device.getInstallId())
            && isPositiveNumericDeviceValue(device.getDeviceId())
            && Texts.hasText(device.getAid())
            && Texts.hasText(device.getUpdateVersionCode());
    }

    static FQApiProperties.DeviceProfile resolveBootstrapProfile(FQApiProperties properties) {
        List<FQApiProperties.DeviceProfile> devicePool = properties == null ? null : properties.getDevicePool();
        if (devicePool == null || devicePool.isEmpty()) {
            return null;
        }

        int limit = Math.max(1, Math.min(properties.getDevicePoolSize(), devicePool.size()));
        String startupName = normalizeNullable(properties.getDevicePoolStartupName());
        if (startupName != null) {
            for (int i = 0; i < limit; i++) {
                FQApiProperties.DeviceProfile profile = devicePool.get(i);
                if (startupName.equals(normalizeNullable(profile == null ? null : profile.getName()))) {
                    return profile;
                }
            }
        }

        return devicePool.get(0);
    }

    private static boolean hasAnyDeviceValue(FQApiProperties.Device device) {
        if (device == null) {
            return false;
        }
        return Texts.hasText(device.getCdid())
            || Texts.hasText(device.getInstallId())
            || Texts.hasText(device.getDeviceId())
            || Texts.hasText(device.getAid())
            || Texts.hasText(device.getVersionCode())
            || Texts.hasText(device.getVersionName())
            || Texts.hasText(device.getUpdateVersionCode())
            || Texts.hasText(device.getDeviceType())
            || Texts.hasText(device.getDeviceBrand())
            || Texts.hasText(device.getRomVersion())
            || Texts.hasText(device.getResolution())
            || Texts.hasText(device.getDpi())
            || Texts.hasText(device.getHostAbi())
            || Texts.hasText(device.getOsVersion())
            || Texts.hasText(device.getOsApi());
    }

    private static void validateDevicePool(List<FQApiProperties.DeviceProfile> devicePool) {
        if (devicePool == null || devicePool.isEmpty()) {
            return;
        }
        for (int i = 0; i < devicePool.size(); i++) {
            FQApiProperties.DeviceProfile profile = devicePool.get(i);
            String prefix = DEVICE_POOL_PREFIX + "[" + i + "]";
            validateDevicePoolEntry(profile, prefix);
        }
    }

    private static void validateDevicePoolEntry(FQApiProperties.DeviceProfile profile, String prefix) {
        if (profile == null) {
            throw new IllegalStateException("缺少设备池配置: " + prefix);
        }
        requireTextValue(profile.getUserAgent(), prefix + ".user-agent");
        requireTextValue(profile.getCookie(), prefix + ".cookie");
        FQApiProperties.Device profileDevice = profile.getDevice();
        if (profileDevice == null) {
            throw new IllegalStateException("缺少设备配置: " + prefix + ".device");
        }
        validateRequiredDevice(profileDevice, prefix + ".device");
    }

    private static void copyIdentityFields(FQApiProperties.Device source, FQApiProperties.Device target) {
        copyIfText(source.getCdid(), target::setCdid);
        copyIfText(source.getInstallId(), target::setInstallId);
        copyIfText(source.getDeviceId(), target::setDeviceId);
        copyIfText(source.getAid(), target::setAid);
    }

    private static void mergeIdentityFields(
        FQApiProperties.Device primary,
        FQApiProperties.Device fallback,
        FQApiProperties.Device target
    ) {
        mergeIfText(primary == null ? null : primary.getCdid(), fallback == null ? null : fallback.getCdid(), target::setCdid);
        mergeIfText(primary == null ? null : primary.getInstallId(), fallback == null ? null : fallback.getInstallId(), target::setInstallId);
        mergeIfText(primary == null ? null : primary.getDeviceId(), fallback == null ? null : fallback.getDeviceId(), target::setDeviceId);
        mergeIfText(primary == null ? null : primary.getAid(), fallback == null ? null : fallback.getAid(), target::setAid);
    }

    private static void copyVersionFields(FQApiProperties.Device source, FQApiProperties.Device target) {
        copyIfText(source.getVersionCode(), target::setVersionCode);
        copyIfText(source.getVersionName(), target::setVersionName);
        copyIfText(source.getUpdateVersionCode(), target::setUpdateVersionCode);
    }

    private static void mergeVersionFields(
        FQApiProperties.Device primary,
        FQApiProperties.Device fallback,
        FQApiProperties.Device target
    ) {
        mergeIfText(primary == null ? null : primary.getVersionCode(), fallback == null ? null : fallback.getVersionCode(), target::setVersionCode);
        mergeIfText(primary == null ? null : primary.getVersionName(), fallback == null ? null : fallback.getVersionName(), target::setVersionName);
        mergeIfText(
            primary == null ? null : primary.getUpdateVersionCode(),
            fallback == null ? null : fallback.getUpdateVersionCode(),
            target::setUpdateVersionCode
        );
    }

    private static void copyHardwareFields(FQApiProperties.Device source, FQApiProperties.Device target) {
        copyIfText(source.getDeviceType(), target::setDeviceType);
        copyIfText(source.getDeviceBrand(), target::setDeviceBrand);
        copyIfText(source.getResolution(), target::setResolution);
        copyIfText(source.getDpi(), target::setDpi);
        copyIfText(source.getHostAbi(), target::setHostAbi);
    }

    private static void mergeHardwareFields(
        FQApiProperties.Device primary,
        FQApiProperties.Device fallback,
        FQApiProperties.Device target
    ) {
        mergeIfText(primary == null ? null : primary.getDeviceType(), fallback == null ? null : fallback.getDeviceType(), target::setDeviceType);
        mergeIfText(primary == null ? null : primary.getDeviceBrand(), fallback == null ? null : fallback.getDeviceBrand(), target::setDeviceBrand);
        mergeIfText(primary == null ? null : primary.getResolution(), fallback == null ? null : fallback.getResolution(), target::setResolution);
        mergeIfText(primary == null ? null : primary.getDpi(), fallback == null ? null : fallback.getDpi(), target::setDpi);
        mergeIfText(primary == null ? null : primary.getHostAbi(), fallback == null ? null : fallback.getHostAbi(), target::setHostAbi);
    }

    private static void copySystemFields(FQApiProperties.Device source, FQApiProperties.Device target) {
        copyIfText(source.getRomVersion(), target::setRomVersion);
        copyIfText(source.getOsVersion(), target::setOsVersion);
        copyIfText(source.getOsApi(), target::setOsApi);
    }

    private static void mergeSystemFields(
        FQApiProperties.Device primary,
        FQApiProperties.Device fallback,
        FQApiProperties.Device target
    ) {
        mergeIfText(primary == null ? null : primary.getRomVersion(), fallback == null ? null : fallback.getRomVersion(), target::setRomVersion);
        mergeIfText(primary == null ? null : primary.getOsVersion(), fallback == null ? null : fallback.getOsVersion(), target::setOsVersion);
        mergeIfText(primary == null ? null : primary.getOsApi(), fallback == null ? null : fallback.getOsApi(), target::setOsApi);
    }

    private static void copyIfText(String value, Consumer<String> setter) {
        if (setter == null) {
            return;
        }
        String trimmed = normalizeNullable(value);
        if (trimmed == null) {
            return;
        }
        setter.accept(trimmed);
    }

    private static void mergeIfText(String primaryValue, String fallbackValue, Consumer<String> setter) {
        if (setter == null) {
            return;
        }
        String merged = Texts.defaultIfBlank(normalizeNullable(primaryValue), normalizeNullable(fallbackValue));
        if (merged != null) {
            setter.accept(merged);
        }
    }

    private static void requireDeviceValue(String value, String fieldName) {
        if (!Texts.hasText(value)) {
            throw new IllegalStateException("缺少设备配置字段: " + fieldName);
        }
    }

    private static void requirePositiveNumericDeviceValue(String value, String fieldName) {
        if (!isPositiveNumericDeviceValue(value)) {
            throw new IllegalStateException("缺少有效设备配置字段: " + fieldName);
        }
    }

    private static boolean isPositiveNumericDeviceValue(String value) {
        String normalized = Texts.trimToNull(value);
        if (normalized == null || !normalized.chars().allMatch(Character::isDigit)) {
            return false;
        }
        try {
            return Long.parseLong(normalized) > 0L;
        } catch (NumberFormatException ignore) {
            return false;
        }
    }

    private static void requireTextValue(String value, String fieldName) {
        if (!Texts.hasText(value)) {
            throw new IllegalStateException("缺少配置字段: " + fieldName);
        }
    }
}
