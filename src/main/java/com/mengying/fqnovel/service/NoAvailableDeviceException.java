package com.mengying.fqnovel.service;

/**
 * 当前没有可用设备时抛出的统一异常。
 */
public class NoAvailableDeviceException extends IllegalStateException {

    public static final String DEFAULT_MESSAGE = "当前没有可用设备，请先运行补池脚本写入 PostgreSQL 设备池";

    public NoAvailableDeviceException() {
        super(DEFAULT_MESSAGE);
    }

    public NoAvailableDeviceException(String message) {
        super(message == null || message.isBlank() ? DEFAULT_MESSAGE : message);
    }

    public static boolean isNoAvailableDeviceMessage(String message) {
        return message != null && message.contains(DEFAULT_MESSAGE);
    }
}
