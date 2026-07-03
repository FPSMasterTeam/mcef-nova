/*
 * MCEF (Minecraft Chromium Embedded Framework)
 * Copyright (C) 2025 CCBlueX
 * Copyright (C) 2023 CinemaMod Group
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
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301
 * USA
 */

package net.ccbluex.liquidbounce.mcef.cef;

import org.cef.CefBrowserSettings;

public class MCEFBrowserSettings extends CefBrowserSettings {
    public MCEFBrowserSettings(int frameRate, boolean sharedTextureEnabled) {
        super();
        this.windowless_frame_rate = frameRate;
        // Zero-copy GPU acceleration (shared_texture_enabled) delivers frames via onAcceleratedPaint,
        // whose shared_texture_handle is only valid FOR THE DURATION OF THE CALLBACK. On Windows/Linux
        // that callback runs on the dedicated CEF message-loop thread (CefMessageLoopThread), which has
        // no GL context of its own — so the import must happen on a GL context bound to THAT thread.
        //
        // When a shared GL context is available (MCEFGlContext: a hidden GLFW window sharing Minecraft's
        // context, made current on the CEF thread), the handle is imported inside the callback there and
        // the resulting texture — visible in Minecraft's context via object sharing — is handed to the
        // render thread with a GL fence. If that context couldn't be created, we must NOT enable zero
        // copy (a deferred cross-thread import uses a recycled handle -> GL_OUT_OF_MEMORY, black webview);
        // fall back to the CPU onPaint path instead.
        //
        // macOS has no message-loop thread (CEF is pumped on the render thread), so onAcceleratedPaint
        // imports synchronously with a live handle and needs no shared context.
        boolean canZeroCopy = CefHelper.getMessageLoopThread() == null || MCEFGlContext.isAvailable();
        this.shared_texture_enabled = sharedTextureEnabled && canZeroCopy;
    }
}
