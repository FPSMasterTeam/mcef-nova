package net.ccbluex.liquidbounce.mcef.utils;

import org.lwjgl.egl.EGL;
import org.lwjgl.egl.EGL14;
import org.lwjgl.egl.EGLCapabilities;
import org.lwjgl.egl.EXTImageDMABufImport;
import org.lwjgl.egl.KHRImageBase;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryUtil;

import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * EGL adapter implemented for mcef-nova from the EGL and LWJGL public APIs.
 * Copyright (C) 2026 FPSMaster Team. SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * <p>Displays belong to the host window system: this class never terminates them or destroys EGL.
 * Call on a thread with the host's graphics context current.
 */
public final class EglUtils {
    private static final Map<Long, EGLCapabilities> displays = new HashMap<>();

    private EglUtils() {
    }

    /** Returns an initialized display, or EGL_NO_DISPLAY when the driver cannot initialize one. */
    public static synchronized long getDisplay() {
        // EGL may be configured for explicit initialization. Load it before any native EGL call.
        try {
            EGL.getCapabilities();
        } catch (IllegalStateException notLoaded) {
            EGL.create();
        }
        long current = EGL14.eglGetCurrentDisplay();
        long candidate = current != EGL14.EGL_NO_DISPLAY
                ? current : EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (candidate == EGL14.EGL_NO_DISPLAY) return candidate;
        if (!displays.containsKey(candidate)) {
            if (!EGL14.eglInitialize(candidate, (IntBuffer) null, (IntBuffer) null)) {
                return EGL14.EGL_NO_DISPLAY;
            }
            // Publish only a complete display/capability pair; never reuse another display's flags.
            displays.put(candidate, EGL.createDisplayCapabilities(candidate));
        }
        return candidate;
    }

    public static synchronized EGLCapabilities getCapabilities() {
        long display = getDisplay();
        EGLCapabilities capabilities = displays.get(display);
        if (capabilities == null) {
            throw new IllegalStateException("No initialized EGL display is available");
        }
        return capabilities;
    }

    /**
     * Imports an EGL image. EXT_image_dma_buf_import requires both EGL_NO_CONTEXT and a NULL
     * EGLClientBuffer; its plane descriptors are passed exclusively in the attribute list.
     */
    public static long eglCreateImageKHR(long display, long context, int target, long buffer, IntBuffer attributes) {
        validateAttributes(attributes);
        if (display == EGL14.EGL_NO_DISPLAY) return KHRImageBase.EGL_NO_IMAGE_KHR;
        if (target == EXTImageDMABufImport.EGL_LINUX_DMA_BUF_EXT
                && (context != EGL14.EGL_NO_CONTEXT || buffer != 0L)) {
            throw new IllegalArgumentException("dma-buf import requires EGL_NO_CONTEXT and a NULL buffer");
        }
        if (EGL.getCapabilities().eglCreateImageKHR == 0L) return KHRImageBase.EGL_NO_IMAGE_KHR;
        // LWJGL's EGL wrapper rejects NULL context/buffer even though dma-buf requires both.
        return JNI.callPPPPP(display, context, target, buffer, MemoryUtil.memAddress(attributes),
                EGL.getCapabilities().eglCreateImageKHR);
    }

    static void validateAttributes(IntBuffer attributes) {
        if (!attributes.isDirect() || attributes.remaining() == 0 || attributes.remaining() % 2 != 1
                || attributes.get(attributes.limit() - 1) != EGL14.EGL_NONE) {
            throw new IllegalArgumentException("EGL attributes must be direct key/value pairs terminated by EGL_NONE");
        }
    }
}
