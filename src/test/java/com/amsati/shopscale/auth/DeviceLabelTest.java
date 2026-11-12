package com.amsati.shopscale.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DeviceLabelTest {
    @Test
    void labelsCommonDevices() {
        assertEquals("Chrome on Android", AuthService.deviceLabel("Mozilla/5.0 (Linux; Android 14; SM-A546B) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36"));
        assertEquals("Safari on iOS", AuthService.deviceLabel("Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 Version/17.5 Mobile/15E148 Safari/604.1"));
        assertEquals("Edge on Windows", AuthService.deviceLabel("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126.0 Safari/537.36 Edg/126.0"));
        assertEquals("Unknown device", AuthService.deviceLabel(null));
    }
}
