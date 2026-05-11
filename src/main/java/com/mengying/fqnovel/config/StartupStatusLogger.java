package com.mengying.fqnovel.config;

import com.mengying.fqnovel.service.FQDeviceRotationService;
import com.mengying.fqnovel.service.FQRegisterKeyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class StartupStatusLogger {

    private static final Logger log = LoggerFactory.getLogger(StartupStatusLogger.class);

    private final FQApiRuntimeProfileManager runtimeProfileManager;
    private final FQDeviceRotationService deviceRotationService;
    private final FQRegisterKeyService registerKeyService;

    public StartupStatusLogger(
        FQApiRuntimeProfileManager runtimeProfileManager,
        FQDeviceRotationService deviceRotationService,
        FQRegisterKeyService registerKeyService
    ) {
        this.runtimeProfileManager = runtimeProfileManager;
        this.deviceRotationService = deviceRotationService;
        this.registerKeyService = registerKeyService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        String poolName = deviceRotationService.getCurrentProfileName();
        String deviceId = currentRuntimeDeviceId();
        int totalProfiles = deviceRotationService.totalProfileCount();
        int availableProfiles = deviceRotationService.availableProfileCount();

        if (deviceRotationService.hasActiveRuntimeProfile()) {
            log.info("设备配置：总数={}, 可用={}, 当前={}, ID={}", totalProfiles, availableProfiles, poolName, deviceId);
        } else {
            log.warn("设备配置：总数={}, 可用={}, 当前无活动设备，服务以降级态启动", totalProfiles, availableProfiles);
            return;
        }

        // 预热：启动时预先获取 registerkey
        try {
            log.info("预热 registerkey...");
            registerKeyService.refreshRegisterKey();
            Map<String, Object> cache = registerKeyService.getCacheStatus();
            Long keyver = (Long) cache.get("currentKeyver");
            log.info("预热完成：keyver={}", keyver);
        } catch (Exception e) {
            log.warn("预热失败（将在首次请求时重试）: {}", e.getMessage());
        }
    }

    private String currentRuntimeDeviceId() {
        FQApiProperties.RuntimeProfile runtimeProfile = runtimeProfileManager.getRuntimeProfile();
        FQApiProperties.Device device = runtimeProfile == null ? null : runtimeProfile.getDeviceUnsafe();
        if (device == null) {
            return null;
        }
        return device.getDeviceId();
    }

}
