package com.mengying.fqnovel.service;

import com.mengying.fqnovel.config.UnidbgProperties;
import com.mengying.fqnovel.utils.ProcessLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Service("fqEncryptWorker")
public class FQEncryptServiceWorker {

    private static final Logger log = LoggerFactory.getLogger(FQEncryptServiceWorker.class);

    private static final AtomicLong RESET_EPOCH = new AtomicLong(0L);
    private static final AtomicBoolean SOFT_UPSTREAM_RESET_ARMED = new AtomicBoolean(true);
    private static long lastResetRequestAtMs = 0L;
    private static long lastUpstreamEmptyResetRequestAtMs = 0L;
    private static volatile long RESET_COOLDOWN_MS = 2000L;
    private static volatile long UPSTREAM_EMPTY_RESET_COOLDOWN_MS = 8000L;
    private final FQEncryptService signer;
    private long localResetEpoch = 0L;

    @Autowired
    public FQEncryptServiceWorker(UnidbgProperties unidbgProperties) {
        UnidbgProperties properties = Objects.requireNonNull(unidbgProperties, "unidbgProperties must not be null");
        RESET_COOLDOWN_MS = Math.max(0L, properties.getResetCooldownMs());
        UPSTREAM_EMPTY_RESET_COOLDOWN_MS = Math.max(0L, properties.getUpstreamEmptyResetCooldownMs());
        this.signer = new FQEncryptService(properties);
    }

    public static synchronized long requestGlobalReset(String reason) {
        if (ProcessLifecycle.isShuttingDown()) {
            return RESET_EPOCH.get();
        }

        long now = System.currentTimeMillis();
        boolean softUpstreamReset = isSoftUpstreamReset(reason);
        if (isWithinCooldown(lastResetRequestAtMs, now, RESET_COOLDOWN_MS)) {
            return RESET_EPOCH.get();
        }
        if (softUpstreamReset
            && isWithinCooldown(lastUpstreamEmptyResetRequestAtMs, now, UPSTREAM_EMPTY_RESET_COOLDOWN_MS)) {
            return RESET_EPOCH.get();
        }
        if (softUpstreamReset && !isEscalatedReset(reason) && !SOFT_UPSTREAM_RESET_ARMED.get()) {
            return RESET_EPOCH.get();
        }

        lastResetRequestAtMs = now;
        if (softUpstreamReset) {
            lastUpstreamEmptyResetRequestAtMs = now;
            SOFT_UPSTREAM_RESET_ARMED.set(false);
        }

        long epoch = RESET_EPOCH.incrementAndGet();
        log.warn("请求重置签名服务: epoch={}, reason={}", epoch, reason);
        return epoch;
    }

    private static boolean isWithinCooldown(long lastAtMs, long nowMs, long cooldownMs) {
        return cooldownMs > 0 && lastAtMs > 0 && nowMs - lastAtMs < cooldownMs;
    }

    private static boolean isSoftUpstreamReset(String reason) {
        return reason != null
            && (reason.contains(UpstreamSignedRequestService.REASON_UPSTREAM_EMPTY)
            || reason.contains(UpstreamSignedRequestService.REASON_CHAPTER_EMPTY_OR_SHORT));
    }

    private static boolean isEscalatedReset(String reason) {
        return reason != null
            && reason.startsWith("AUTO_SELF_HEAL:");
    }

    public static void recordUpstreamSuccess() {
        SOFT_UPSTREAM_RESET_ARMED.set(true);
    }

    /**
     * 同步生成FQ签名headers (重载方法，支持Map格式的headers)
     *
     * @param url 请求的URL
     * @param headerMap 请求头的Map
     * @return 包含签名信息的签名头
     */
    public synchronized Map<String, String> generateSignatureHeadersSync(String url, Map<String, String> headerMap) {
        ensureResetUpToDate();
        return signer.generateSignatureHeaders(url, headerMap);
    }

    private void ensureResetUpToDate() {
        if (ProcessLifecycle.isShuttingDown()) {
            return;
        }
        long epoch = RESET_EPOCH.get();
        if (epoch == localResetEpoch) {
            return;
        }
        signer.reset("RESET_EPOCH:" + epoch);
        localResetEpoch = epoch;
    }

    @PreDestroy
    public void destroy() {
        try {
            signer.destroy();
        } catch (Exception e) {
            log.warn("销毁签名服务时发生异常", e);
        }
    }
}
