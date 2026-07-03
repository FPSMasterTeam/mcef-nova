/*
 * MCEF (Minecraft Chromium Embedded Framework)
 * Copyright (C) 2025 CCBlueX
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 */

package net.ccbluex.liquidbounce.mcef.cef;

import net.ccbluex.liquidbounce.mcef.MCEF;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

/**
 * A second OpenGL context that SHARES resources with Minecraft's context, owned by the CEF
 * message-loop thread (Windows/Linux only).
 *
 * <p>Why it exists: CEF's zero-copy accelerated paint hands us a GPU shared-texture handle that is
 * only valid for the duration of the {@code onAcceleratedPaint} callback. That callback fires on
 * the CEF message-loop thread — which, unlike the old render-thread pump, has no GL context — so the
 * handle must be imported into a GL texture right there, on that thread. This context makes that
 * possible: a hidden GLFW window created with Minecraft's window as its share-group, made current on
 * the CEF thread. Textures imported here are visible in Minecraft's context (shared object space);
 * a GL fence orders the render thread's sampling after the import completes.
 *
 * <p>The window is created on the render thread (GLFW window creation must run on the main thread)
 * and made current on the CEF thread. It is never made current on the render thread, so Minecraft's
 * own context stays put.
 */
public final class MCEFGlContext {

    private static volatile MCEFGlContext instance;

    private final long window;
    private volatile boolean madeCurrent = false;
    private GLCapabilities capabilities;

    private MCEFGlContext(long window) {
        this.window = window;
    }

    /**
     * Create the shared context. MUST be called on the render thread (the GLFW main thread), with a
     * current Minecraft GL context, passing Minecraft's GLFW window handle as the share window.
     * Returns true on success; on any failure the caller should leave zero-copy disabled and fall
     * back to the CPU paint path.
     */
    public static synchronized boolean create(long minecraftWindow) {
        if (instance != null) {
            return true;
        }
        if (minecraftWindow == 0L) {
            MCEF.INSTANCE.getLogger().warn("No Minecraft window handle; zero-copy GPU accel disabled");
            return false;
        }
        try {
            // A shared context needs its own (hidden, unfocused, non-resizable) GLFW window. Creating
            // it does NOT change the current context, so Minecraft's context stays current on this
            // (render) thread.
            GLFW.glfwDefaultWindowHints();
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
            // GLFW derives the new context's version from the CURRENT hints (not the share window), so
            // request a 3.2 core context to guarantee GL fences (glFenceSync) and match Minecraft. The
            // external-memory import extensions are exposed by the driver regardless of the core version.
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
            long shared = GLFW.glfwCreateWindow(1, 1, "MCEF-CEF-GL", 0L, minecraftWindow);
            GLFW.glfwDefaultWindowHints();
            if (shared == 0L) {
                MCEF.INSTANCE.getLogger().warn("glfwCreateWindow (shared CEF GL context) failed; zero-copy disabled");
                return false;
            }
            instance = new MCEFGlContext(shared);
            MCEF.INSTANCE.getLogger().info("Created shared GL context for zero-copy CEF accelerated paint");
            return true;
        } catch (Throwable t) {
            MCEF.INSTANCE.getLogger().warn("Failed to create shared CEF GL context; zero-copy disabled", t);
            return false;
        }
    }

    public static MCEFGlContext get() {
        return instance;
    }

    public static boolean isAvailable() {
        return instance != null;
    }

    /**
     * Make this context current on the calling thread (the CEF message-loop thread) and load its GL
     * function table. Idempotent: only binds + loads capabilities once. Returns false if binding
     * failed (caller should skip the accelerated import for this frame).
     */
    public boolean ensureCurrentOnCefThread() {
        if (madeCurrent) {
            return true;
        }
        synchronized (this) {
            if (madeCurrent) {
                return true;
            }
            try {
                GLFW.glfwMakeContextCurrent(window);
                // Per-thread GL capabilities for the CEF thread; independent of the render thread's.
                capabilities = GL.createCapabilities();
                madeCurrent = true;
                MCEF.INSTANCE.getLogger().info("CEF thread bound shared GL context for accelerated paint");
                return true;
            } catch (Throwable t) {
                MCEF.INSTANCE.getLogger().error("Failed to bind shared GL context on CEF thread", t);
                return false;
            }
        }
    }

    /** Destroy the shared window. Call on the render thread during shutdown. */
    public static synchronized void destroy() {
        if (instance == null) {
            return;
        }
        try {
            GLFW.glfwDestroyWindow(instance.window);
        } catch (Throwable ignored) {
            // best-effort
        }
        instance = null;
    }
}
