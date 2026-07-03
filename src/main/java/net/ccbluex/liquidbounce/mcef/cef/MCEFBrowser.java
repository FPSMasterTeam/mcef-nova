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

import net.ccbluex.liquidbounce.mcef.MCEF;
import net.ccbluex.liquidbounce.mcef.MCEFPlatform;
import net.ccbluex.liquidbounce.mcef.glfw.MCEFGlfwCursorHelper;
import net.ccbluex.liquidbounce.mcef.listeners.MCEFCursorChangeListener;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefBrowserOsr;
import org.cef.callback.CefDragData;
import org.cef.event.CefKeyEvent;
import org.cef.event.CefMouseEvent;
import org.cef.event.CefMouseWheelEvent;
import org.cef.handler.CefAcceleratedPaintInfo;
import org.cef.misc.CefCursorType;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryUtil;

import java.awt.*;
import java.nio.ByteBuffer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

/**
 * An instance of an "Off-screen rendered" Chromium web browser.
 * Complete with a renderer, keyboard and mouse inputs, optional
 * browser control shortcuts, cursor handling, drag & drop support.
 */
public class MCEFBrowser extends CefBrowserOsr {
    /**
     * The renderer for the browser.
     */
    private final MCEFRenderer renderer;
    /**
     * Stores information about drag & drop.
     */
    private final MCEFDragContext dragContext = new MCEFDragContext();
    /**
     * A listener that defines that happens when a cursor changes in the browser.
     * E.g. when you've hovered over a button, an input box, are selecting text, etc...
     * A default listener is created in the constructor that sets the cursor type to
     * the appropriate cursor based on the event.
     */
    private MCEFCursorChangeListener cursorChangeListener;
    /**
     * Used to track when a full repaint should occur.
     */
    private int lastWidth = 0, lastHeight = 0;
    /**
     * A bitset representing what mouse buttons are currently pressed.
     * CEF is a bit odd and implements mouse buttons as a part of modifier flags.
     */
    private int btnMask = 0;

    // Data relating to popups and graphics
    // Marked as protected in-case a mod wants to extend MCEFBrowser and override the repaint logic
    protected ByteBuffer popupGraphics;
    protected Rectangle popupSize;
    protected boolean showPopup = false;
    protected boolean popupDrawn = false;
    private long lastClickTime = 0;
    private int clicks;
    private int mouseButton;

    // CPU-side paint handoff. CEF delivers onPaint on the CEF message-loop thread (a dedicated
    // thread on Windows/Linux — see CefMessageLoopThread), which has no GL context. Paints are
    // composited into this CPU mirror of the page and uploaded on the render thread by
    // flushFrame() (driven by MCEF.update() once per game frame). Guarded by paintLock.
    private final Object paintLock = new Object();
    private ByteBuffer frameMirror;
    private int frameMirrorWidth = 0, frameMirrorHeight = 0;
    private Rectangle frameDirty;
    private boolean frameSizeChanged = false;
    private boolean popupNeedsUpload = false;
    private CefAcceleratedPaintInfo pendingAcceleratedInfo;
    private int pendingAcceleratedWidth = 0, pendingAcceleratedHeight = 0;
    private String lastDroppedPaintSignature;

    private final boolean isMacOs = MCEFPlatform.getPlatform().isMacOS();
    private final boolean isWindows = MCEFPlatform.getPlatform().isWindows();

    public MCEFBrowser(MCEFClient client, String url, boolean transparent, MCEFBrowserSettings browserSettings) {
        super(client.getHandle(), url, transparent, null, browserSettings);
        renderer = new MCEFRenderer(transparent);
        cursorChangeListener = (cefCursorID) -> setCursor(CefCursorType.fromId(cefCursorID));

        MCEF.INSTANCE.host().schedule(renderer::initialize);
        MCEF.INSTANCE.registerBrowser(this);
    }

    public MCEFRenderer getRenderer() {
        return renderer;
    }

    /**
     * Check if the browser's texture is ready for rendering.
     *
     * @return true if the texture is initialized and ready to be rendered
     */
    public boolean isTextureReady() {
        return renderer != null && renderer.isTextureReady();
    }

    public MCEFCursorChangeListener getCursorChangeListener() {
        return cursorChangeListener;
    }

    public void setCursorChangeListener(MCEFCursorChangeListener cursorChangeListener) {
        this.cursorChangeListener = cursorChangeListener;
    }

    public MCEFDragContext getDragContext() {
        return dragContext;
    }

    // Popups
    @Override
    public void onPopupShow(CefBrowser browser, boolean show) {
        super.onPopupShow(browser, show);
        synchronized (paintLock) {
            showPopup = show;
            if (!show) {
                // The mirror holds the true page content (CEF keeps the main buffer fully updated
                // underneath the popup), so restoring is just re-uploading the popup's area.
                if (popupSize != null) {
                    addFrameDirty(popupSize);
                }
                popupDrawn = false;
                popupNeedsUpload = false;
                popupGraphics = null;
                popupSize = null;
            }
        }
    }

    @Override
    public void onPopupSize(CefBrowser browser, Rectangle size) {
        super.onPopupSize(browser, size);
        synchronized (paintLock) {
            popupSize = size;
            popupGraphics = ByteBuffer.allocateDirect(size.width * size.height * 4);
        }
    }

    // Graphics.
    //
    // Runs on the CEF message-loop thread — NO GL here. The frame is composited into a CPU
    // mirror; the render thread uploads it in flushFrame().
    @Override
    public void onPaint(CefBrowser browser, boolean popup, Rectangle[] dirtyRects, ByteBuffer buffer, int width, int height) {
        // nothing to update
        if (dirtyRects.length == 0) {
            return;
        }

        synchronized (paintLock) {
            if (!popup) {
                // The copies below trust width/height; a malformed frame whose buffer is shorter
                // would read past the source and SIGBUS the JVM. Degrade to a dropped frame.
                if (!isPaintBufferUsable(false, buffer, width, height)) {
                    return;
                }
                if (frameMirror == null || frameMirrorWidth != width || frameMirrorHeight != height) {
                    frameMirror = ByteBuffer.allocateDirect(width * height * 4);
                    frameMirrorWidth = width;
                    frameMirrorHeight = height;
                    MemoryUtil.memCopy(
                            MemoryUtil.memAddress0(buffer),
                            MemoryUtil.memAddress0(frameMirror),
                            (long) width * height * 4);
                    frameSizeChanged = true;
                    frameDirty = new Rectangle(0, 0, width, height);
                } else {
                    for (Rectangle dirtyRect : dirtyRects) {
                        Rectangle clamped = clampRect(dirtyRect, width, height);
                        if (clamped == null) continue;
                        copyRect(buffer, frameMirror, width, clamped);
                        addFrameDirty(clamped);
                    }
                }
            } else {
                if (popupSize == null || popupGraphics == null) {
                    return;
                }
                if (!isPaintBufferUsable(true, buffer, popupSize.width, popupSize.height)) {
                    return;
                }
                // Popup paints arrive in popup-local coordinates with a popup-sized buffer.
                for (Rectangle dirtyRect : dirtyRects) {
                    Rectangle clamped = clampRect(dirtyRect, popupSize.width, popupSize.height);
                    if (clamped == null) continue;
                    copyRect(buffer, popupGraphics, popupSize.width, clamped);
                }
                popupDrawn = true;
                popupNeedsUpload = true;
            }
        }
        super.onPaint(browser, popup, dirtyRects, buffer, width, height);
    }

    @Override
    public void onAcceleratedPaint(CefBrowser browser, boolean popup, Rectangle[] dirtyRects,
                                   CefAcceleratedPaintInfo info) {
        // nothing to update
        if (dirtyRects.length == 0) {
            return;
        }

        // refuse 1x1 rectangles
        if (info.width <= 1 || info.height <= 1 ||
                dirtyRects[0].width <= 1 || dirtyRects[0].height <= 1) {
            return;
        }

        var width = info.width;
        var height = info.height;

        if (lastWidth != width || lastHeight != height) {
            var rect = dirtyRects[0];

            if (rect.width == width && rect.height == height && rect.x == 0 && rect.y == 0) {
                lastWidth = width;
                lastHeight = height;
            } else {
                // Likely an outdated paint-call
                return;
            }
        }

        if (!popup) {
            // Defer the GL import to the render thread; only the newest frame matters.
            synchronized (paintLock) {
                pendingAcceleratedInfo = info;
                pendingAcceleratedWidth = width;
                pendingAcceleratedHeight = height;
            }
        } else {
            MCEF.INSTANCE.LOGGER.warn("Accelerated paint for popups is not supported in MCEF.");
        }

        super.onAcceleratedPaint(browser, popup, dirtyRects, info);
    }

    /**
     * Upload any pending browser frame to the GPU. Called on the render thread once per game
     * frame via {@link MCEF#update()}.
     */
    public final void flushFrame() {
        CefAcceleratedPaintInfo acceleratedInfo;
        int acceleratedWidth, acceleratedHeight;
        synchronized (paintLock) {
            acceleratedInfo = pendingAcceleratedInfo;
            acceleratedWidth = pendingAcceleratedWidth;
            acceleratedHeight = pendingAcceleratedHeight;
            pendingAcceleratedInfo = null;
        }
        if (acceleratedInfo != null) {
            applyAcceleratedFrame(acceleratedInfo, acceleratedWidth, acceleratedHeight);
        }

        // The lock is held across the upload: a CEF paint arriving mid-upload waits instead of
        // mutating the mirror underneath the GL call. That back-pressure is bounded by one upload.
        synchronized (paintLock) {
            if (frameMirror != null && frameDirty != null) {
                uploadFrame(frameMirror, frameMirrorWidth, frameMirrorHeight, frameDirty, frameSizeChanged);
                frameDirty = null;
                frameSizeChanged = false;
                if (showPopup && popupDrawn) {
                    // The main upload may have overwritten the popup's area; re-composite it.
                    popupNeedsUpload = true;
                }
            }
            if (showPopup && popupDrawn && popupNeedsUpload && popupGraphics != null && popupSize != null) {
                uploadPopup(popupGraphics, popupSize, frameMirrorWidth, frameMirrorHeight);
                popupNeedsUpload = false;
            }
        }
    }

    /**
     * Render-thread hook: land a dirty region of the CPU frame mirror in the GPU texture. The
     * default routes through the raw-GL {@link MCEFRenderer}; hosts with their own GPU
     * abstraction can override (buffer position/limit must be left untouched).
     *
     * @param frame      full BGRA frame mirror ({@code width * height * 4} bytes)
     * @param dirty      the region needing upload
     * @param fullUpload true when the frame size changed (the texture must be (re)created)
     */
    protected void uploadFrame(ByteBuffer frame, int width, int height, Rectangle dirty, boolean fullUpload) {
        if (fullUpload || renderer.getTextureId() == 0) {
            renderer.onPaint(frame, width, height);
            resetUnpackState();
            return;
        }
        glBindTexture(GL_TEXTURE_2D, renderer.getTextureId());
        glPixelStorei(GL_UNPACK_ROW_LENGTH, width);
        glPixelStorei(GL_UNPACK_SKIP_PIXELS, dirty.x);
        glPixelStorei(GL_UNPACK_SKIP_ROWS, dirty.y);
        renderer.onPaint(frame, dirty.x, dirty.y, dirty.width, dirty.height);
        resetUnpackState();
    }

    /**
     * Render-thread hook: composite the popup (dropdown) overlay over the frame texture.
     */
    protected void uploadPopup(ByteBuffer popup, Rectangle popupRect, int frameWidth, int frameHeight) {
        if (renderer.getTextureId() == 0) {
            return;
        }
        glBindTexture(GL_TEXTURE_2D, renderer.getTextureId());
        glPixelStorei(GL_UNPACK_ROW_LENGTH, popupRect.width);
        glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
        glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
        renderer.onPaint(popup, popupRect.x, popupRect.y, popupRect.width, popupRect.height);
        resetUnpackState();
    }

    /**
     * Render-thread hook: import a pending accelerated (shared-texture) frame.
     */
    protected void applyAcceleratedFrame(CefAcceleratedPaintInfo info, int width, int height) {
        renderer.onAcceleratedPaint(info, width, height);
    }

    /**
     * Queue an accelerated frame for import on the render thread with an explicit texture size,
     * bypassing this class's size validation. For subclasses that reinterpret CEF's reported
     * paint size (CEF sometimes reports stale sizes around resizes).
     */
    protected final void queueAcceleratedFrame(CefAcceleratedPaintInfo info, int width, int height) {
        lastWidth = width;
        lastHeight = height;
        synchronized (paintLock) {
            pendingAcceleratedInfo = info;
            pendingAcceleratedWidth = width;
            pendingAcceleratedHeight = height;
        }
    }

    private static void resetUnpackState() {
        // Leave the pixel-store state the way Minecraft expects it, instead of letting our
        // row-length/skip values poison the next texture upload that runs on this context.
        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
        glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
    }

    /** Grow the pending dirty union. Caller holds paintLock. */
    private void addFrameDirty(Rectangle rect) {
        Rectangle clamped = clampRect(rect, frameMirrorWidth, frameMirrorHeight);
        if (clamped == null) {
            return;
        }
        if (frameDirty == null) {
            frameDirty = new Rectangle(clamped);
        } else {
            frameDirty = frameDirty.union(clamped);
        }
    }

    private static Rectangle clampRect(Rectangle rect, int width, int height) {
        int x = Math.max(0, rect.x);
        int y = Math.max(0, rect.y);
        int maxX = Math.min(width, rect.x + rect.width);
        int maxY = Math.min(height, rect.y + rect.height);
        if (maxX <= x || maxY <= y) {
            return null;
        }
        return new Rectangle(x, y, maxX - x, maxY - y);
    }

    /** Row-wise copy of {@code rect} between two full-frame BGRA buffers of row width {@code rowWidth}. */
    private static void copyRect(ByteBuffer src, ByteBuffer dst, int rowWidth, Rectangle rect) {
        long srcAddr = MemoryUtil.memAddress0(src);
        long dstAddr = MemoryUtil.memAddress0(dst);
        long rowBytes = (long) rect.width * 4;
        for (int row = 0; row < rect.height; row++) {
            long offset = ((long) (rect.y + row) * rowWidth + rect.x) * 4;
            MemoryUtil.memCopy(srcAddr + offset, dstAddr + offset, rowBytes);
        }
    }

    private boolean isPaintBufferUsable(boolean popup, ByteBuffer buffer, int width, int height) {
        if (width <= 0 || height <= 0 || buffer == null || !buffer.isDirect()
                || buffer.capacity() < (long) width * height * 4) {
            String signature = popup + ":" + width + ":" + height + ":"
                    + (buffer == null ? -1 : buffer.capacity());
            if (!signature.equals(lastDroppedPaintSignature)) {
                lastDroppedPaintSignature = signature;
                MCEF.INSTANCE.LOGGER.warn(
                        "Dropped browser paint to avoid out-of-bounds copy: popup={}, size={}x{}, bufferBytes={}",
                        popup, width, height, buffer == null ? -1 : buffer.capacity());
            }
            return false;
        }
        return true;
    }

    public void resize(int width, int height) {
        browser_rect_.setBounds(0, 0, width, height);
        wasResized(width, height);
    }

    // Inputs
    public void sendKeyPress(int keyCode, long scanCode, int modifiers) {
        if (modifiers == GLFW_MOD_CONTROL && keyCode == GLFW_KEY_R) {
            reload();
            return;
        }

        CefKeyEvent e = new CefKeyEvent(CefKeyEvent.KEY_PRESS, keyCode, (char) keyCode, modifiers);
        e.scancode = scanCode;
        sendKeyEvent(e);
    }

    public void sendKeyRelease(int keyCode, long scanCode, int modifiers) {
        if (modifiers == GLFW_MOD_CONTROL && keyCode == GLFW_KEY_R) {
            return;
        }

        CefKeyEvent e = new CefKeyEvent(CefKeyEvent.KEY_RELEASE, keyCode, (char) keyCode, modifiers);
        e.scancode = scanCode;
        sendKeyEvent(e);
    }

    public void sendKeyTyped(char c, int modifiers) {
        if (modifiers == GLFW_MOD_CONTROL && (int) c == GLFW_KEY_R) {
            return;
        }

        CefKeyEvent e = new CefKeyEvent(CefKeyEvent.KEY_TYPE, c, c, modifiers);
        sendKeyEvent(e);
    }

    public void sendMouseMove(int mouseX, int mouseY) {
        sendMouseEvent(new CefMouseEvent(CefMouseEvent.MOUSE_MOVED, mouseX, mouseY, clicks, mouseButton,
                dragContext.getVirtualModifiers(btnMask)));

        if (dragContext.isDragging()) {
            this.dragTargetDragOver(new Point(mouseX, mouseY), 0, dragContext.getMask());
        }
    }

    public void sendMousePress(int mouseX, int mouseY, int button) {
        button = swapButton(button);

        if (button == 0) {
            btnMask |= CefMouseEvent.BUTTON1_MASK;
        } else if (button == 1) {
            btnMask |= CefMouseEvent.BUTTON2_MASK;
        } else if (button == 2) {
            btnMask |= CefMouseEvent.BUTTON3_MASK;
        }

        // Double click handling
        var time = System.currentTimeMillis();
        clicks = time - lastClickTime < 500 ? 2 : 1;

        sendMouseEvent(new CefMouseEvent(GLFW_PRESS, mouseX, mouseY, clicks, button, btnMask));

        this.lastClickTime = time;
        this.mouseButton = button;
    }

    // TODO: it may be necessary to add modifiers here
    public void sendMouseRelease(int mouseX, int mouseY, int button) {
        button = swapButton(button);

        if (button == 0 && (btnMask & CefMouseEvent.BUTTON1_MASK) != 0) {
            btnMask ^= CefMouseEvent.BUTTON1_MASK;
        } else if (button == 1 && (btnMask & CefMouseEvent.BUTTON2_MASK) != 0) {
            btnMask ^= CefMouseEvent.BUTTON2_MASK;
        } else if (button == 2 && (btnMask & CefMouseEvent.BUTTON3_MASK) != 0) {
            btnMask ^= CefMouseEvent.BUTTON3_MASK;
        }

        // drag & drop
        if (dragContext.isDragging()) {
            if (button == 0) {
                finishDragging(mouseX, mouseY);
            }
        }

        sendMouseEvent(new CefMouseEvent(GLFW_RELEASE, mouseX, mouseY, clicks, button, btnMask));
        this.mouseButton = 0;
    }

    public void sendMouseWheel(int mouseX, int mouseY, double amount) {
        // macOS generally has a slow scroll speed that feels more natural with their magic mice / trackpads
        if (!isMacOs) {
            // This removes the feeling of "smooth scroll"
            if (amount < 0) {
                amount = Math.floor(amount);
            } else {
                amount = Math.ceil(amount);
            }

            // This feels about equivalent to chromium with smooth scrolling disabled -ds58
            amount = amount * 3;
        }

        var event = new CefMouseWheelEvent(CefMouseWheelEvent.WHEEL_UNIT_SCROLL, mouseX, mouseY, amount, 0);
        sendMouseWheelEvent(event);
    }

    public void clear() {
        invalidate();
    }

    // Drag & drop
    @Override
    public boolean startDragging(CefBrowser browser, CefDragData dragData, int mask, int x, int y) {
        dragContext.startDragging(dragData, mask);
        this.dragTargetDragEnter(dragContext.getDragData(), new Point(x, y), btnMask, dragContext.getMask());
        // Indicates to CEF to not handle the drag event natively
        // reason: native drag handling doesn't work with off-screen rendering
        return false;
    }

    @Override
    public void updateDragCursor(CefBrowser browser, int operation) {
        if (dragContext.updateCursor(operation)) {
            // If the cursor to display for the drag event changes, then update the cursor
            this.onCursorChange(this, dragContext.getVirtualCursor(dragContext.getActualCursor()));
        }

        super.updateDragCursor(browser, operation);
    }

    // Expose drag & drop functions
    public void startDragging(CefDragData dragData, int mask, int x, int y) {
        // Overload since the JCEF method requires a browser, which then goes unused
        startDragging(dragData, mask, x, y);
    }

    public void finishDragging(int x, int y) {
        dragTargetDrop(new Point(x, y), btnMask);
        dragTargetDragLeave();
        dragContext.stopDragging();
        this.onCursorChange(this, dragContext.getActualCursor());
    }

    public void cancelDrag() {
        dragTargetDragLeave();
        dragContext.stopDragging();
        this.onCursorChange(this, dragContext.getActualCursor());
    }

    // Closing
    public void close() {
        MCEF.INSTANCE.unregisterBrowser(this);
        renderer.close();
        cursorChangeListener.onCursorChange(0);
        super.close(true);
    }

    @Override
    protected void finalize() throws Throwable {
        MCEF.INSTANCE.host().schedule(renderer::close);
        super.finalize();
    }

    // Cursor handling
    @Override
    public boolean onCursorChange(CefBrowser browser, int cursorType) {
        cursorType = dragContext.getVirtualCursor(cursorType);
        cursorChangeListener.onCursorChange(cursorType);
        return super.onCursorChange(browser, cursorType);
    }

    public void setCursor(CefCursorType cursorType) {
        // We do not want to change the cursor state since Minecraft does this for us.
        if (cursorType == CefCursorType.NONE) return;

        // Cursor changes arrive on the CEF message-loop thread; GLFW calls are main-thread-only.
        MCEF.INSTANCE.host().schedule(() -> {
            var windowHandle = MCEF.INSTANCE.host().windowHandle();
            GLFW.glfwSetCursor(windowHandle, MCEFGlfwCursorHelper.getGLFWCursorHandle(cursorType));
        });
    }

    /**
     * For some reason, middle and right are swapped in MC
     *
     * @param button the button to swap
     * @return the swapped button
     */
    private int swapButton(int button) {
        if (button == 1) {
            return 2;
        } else if (button == 2) {
            return 1;
        }

        return button;
    }

}
