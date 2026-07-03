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
import net.ccbluex.liquidbounce.mcef.utils.EglUtils;
import org.cef.handler.CefAcceleratedPaintInfo;
import org.cef.handler.CefAcceleratedPaintInfoLinux;
import org.cef.handler.CefAcceleratedPaintInfoWin;
import org.jspecify.annotations.NullMarked;
import org.lwjgl.egl.EGL14;
import org.lwjgl.egl.EXTImageDMABufImport;
import org.lwjgl.egl.KHRImageBase;
import org.lwjgl.opengl.EXTEGLImageStorage;
import org.lwjgl.system.MemoryStack;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;

import static org.lwjgl.opengl.EXTMemoryObject.*;
import static org.lwjgl.opengl.EXTMemoryObjectWin32.GL_HANDLE_TYPE_D3D11_IMAGE_EXT;
import static org.lwjgl.opengl.EXTMemoryObjectWin32.glImportMemoryWin32HandleEXT;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_BGRA;
import static org.lwjgl.opengl.GL12.GL_UNSIGNED_INT_8_8_8_8_REV;
import static org.lwjgl.opengl.GL30.GL_RGBA8;
// (GL_RGBA8 is provided by GL30 in LWJGL 3)

/**
 * Off-screen browser renderer.
 *
 * <p>This is the version-agnostic core of MCEF: it takes the pixels CEF produces — either a CPU
 * {@link ByteBuffer} via {@code onPaint} or a GPU shared-texture handle via
 * {@code onAcceleratedPaint} — and lands them in a plain OpenGL texture using raw LWJGL GL/EGL
 * calls. It deliberately holds <b>no</b> reference to any {@code net.minecraft} or
 * {@code com.mojang.blaze3d} type. The single value it exposes across the boundary is an OpenGL
 * texture id ({@link #getTextureId()}); wrapping that id into a Minecraft texture / draw call is
 * the responsibility of the host mod, and is the only part that differs between Minecraft versions.
 */
@NullMarked
public class MCEFRenderer implements Closeable {

    private final boolean transparent;

    // The CPU (onPaint) texture and the GPU (onAcceleratedPaint) shared texture. Both are plain
    // OpenGL texture ids; 0 means "not allocated".
    private int textureId = 0;
    private int sharedTextureId = 0;

    private int textureWidth = 0;
    private int textureHeight = 0;

    private boolean isBGRA = false;
    private boolean unpainted = true;
    private boolean isAccelerated = false;

    protected MCEFRenderer(boolean transparent) {
        this.transparent = transparent;
    }

    /**
     * Kept for API compatibility. The CPU texture is created lazily on the first {@link #onPaint},
     * and the accelerated texture on the first {@code onAcceleratedPaint}, so there is nothing to
     * eagerly allocate here.
     */
    public void initialize() {
        // no-op
    }

    /**
     * Returns the OpenGL texture id currently backing the browser. If accelerated rendering is
     * active this is the imported shared texture, otherwise the CPU-uploaded texture. Returns 0
     * when no frame has been produced yet.
     */
    public int getTextureId() {
        return isAccelerated ? sharedTextureId : textureId;
    }

    /**
     * Whether a usable texture exists yet.
     */
    public boolean isTextureReady() {
        return getTextureId() != 0;
    }

    /**
     * Whether the renderer has produced no frame since the last (re)initialization.
     */
    public boolean isUnpainted() {
        if (getTextureId() == 0) {
            return false;
        }
        return unpainted;
    }

    public int getTextureWidth() {
        return textureWidth;
    }

    public int getTextureHeight() {
        return textureHeight;
    }

    public boolean isTransparent() {
        return transparent;
    }

    /**
     * True when CEF is delivering frames via {@code onAcceleratedPaint} (zero-copy shared texture)
     * rather than {@code onPaint} (CPU buffer).
     */
    public boolean isAccelerated() {
        return isAccelerated;
    }

    /**
     * True when the backing texture is in BGRA order (accelerated shared textures generally are);
     * the host must pick the matching sampling pipeline.
     */
    public boolean isBGRA() {
        return isBGRA;
    }

    // ------------------------------------------------------------------------------------------
    // Accelerated paint (GPU shared texture import). Platform-specific, Minecraft-version-agnostic.
    // ------------------------------------------------------------------------------------------

    /**
     * Import CEF's shared texture into a FRESH OpenGL texture and return its id (0 on failure).
     *
     * <p>Unlike the legacy path, this does NOT delete any previous texture or store the id — the
     * caller ({@link MCEFBrowser}) owns the cross-thread texture lifecycle (the imported texture is
     * handed to the render thread behind a GL fence and freed once no longer displayed). Must run on
     * a thread with a current GL context whose object space is shared with the render thread.
     */
    protected int importAcceleratedTexture(CefAcceleratedPaintInfo info, int width, int height) {
        // if/instanceof rather than a type switch, to keep this compilable at Java 17 (the floor for
        // MC 1.20.1) so one build serves every version.
        if (info instanceof CefAcceleratedPaintInfoWin winInfo) {
            return importAcceleratedWindows(winInfo, width, height);
        } else if (info instanceof CefAcceleratedPaintInfoLinux linuxInfo) {
            return importAcceleratedLinux(linuxInfo, width, height);
        } else {
            MCEF.INSTANCE.LOGGER.warn("Unsupported CefAcceleratedPaintInfo type: {}", info.getClass().getName());
            return 0;
        }
    }

    /**
     * Windows: CEF provides a D3D11 texture handle; import it into a fresh OpenGL texture via
     * {@code glImportMemoryWin32HandleEXT}.
     */
    private int importAcceleratedWindows(CefAcceleratedPaintInfoWin info, int width, int height) {
        if (info.shared_texture_handle == 0) {
            MCEF.INSTANCE.LOGGER.warn("Accelerated paint shared texture handle is invalid.");
            return 0;
        }

        // Textures imported from external memory are immutable, so allocate a new one each frame.
        var newTextureId = glGenTextures();

        var memoryObject = glCreateMemoryObjectsEXT();
        if (memoryObject == 0) {
            MCEF.INSTANCE.LOGGER.error("Failed to create memory object for shared texture.");
            glDeleteTextures(newTextureId);
            return 0;
        }

        // CEF emits CEF_COLOR_TYPE_BGRA_8888: 4 bytes per pixel; the mem object size requires x2.
        var size = (long) width * height * 4 * 2;

        glImportMemoryWin32HandleEXT(memoryObject,
                size,
                GL_HANDLE_TYPE_D3D11_IMAGE_EXT,
                info.shared_texture_handle
        );

        int error = glGetError();
        if (error != GL_NO_ERROR) {
            MCEF.INSTANCE.LOGGER.error("glImportMemoryWin32HandleEXT failed with error: {}", error);
            glDeleteTextures(newTextureId);
            glDeleteMemoryObjectsEXT(memoryObject);
            return 0;
        }

        glBindTexture(GL_TEXTURE_2D, newTextureId);
        glTexStorageMem2DEXT(
                GL_TEXTURE_2D,
                1,
                GL_RGBA8,
                width,
                height,
                memoryObject,
                0
        );

        // The texture keeps its own reference to the imported storage, so the memory object can go now.
        glDeleteMemoryObjectsEXT(memoryObject);

        textureWidth = width;
        textureHeight = height;
        isAccelerated = true;
        unpainted = false;
        isBGRA = true;

        glBindTexture(GL_TEXTURE_2D, 0);
        return newTextureId;
    }

    /**
     * Linux: CEF provides dmabuf planes; import them via EGL into a fresh OpenGL texture.
     */
    private int importAcceleratedLinux(CefAcceleratedPaintInfoLinux info, int width, int height) {
        if (!info.hasDmaBufPlanes()) {
            MCEF.INSTANCE.LOGGER.warn("Accelerated paint info has no dmabuf planes on Linux.");
            return 0;
        }

        var display = EglUtils.getDisplay();
        if (display == EGL14.EGL_NO_DISPLAY) {
            MCEF.INSTANCE.LOGGER.error("EGL display is not available for dmabuf import.");
            return 0;
        }

        if (EGL14.eglGetCurrentContext() == EGL14.EGL_NO_CONTEXT) {
            MCEF.INSTANCE.LOGGER.warn("No current EGL context available for dmabuf import.");
            return 0;
        }

        var drmFormat = switch (info.format) {
            case CefConstants.CEF_COLOR_TYPE_RGBA_8888 -> CefConstants.DRM_FORMAT_ABGR8888;
            case CefConstants.CEF_COLOR_TYPE_BGRA_8888 -> CefConstants.DRM_FORMAT_ARGB8888;
            default -> 0;
        };

        if (drmFormat == 0) {
            MCEF.INSTANCE.LOGGER.error("Unsupported accelerated paint format: {}", info.format);
            return 0;
        }

        var planeCount = Math.min(info.plane_count, CefConstants.DMA_BUF_PLANE_FD_ATTRS.length);
        planeCount = Math.min(planeCount, info.plane_fds.length);
        planeCount = Math.min(planeCount, info.plane_strides.length);
        planeCount = Math.min(planeCount, info.plane_offsets.length);
        if (planeCount <= 0) {
            MCEF.INSTANCE.LOGGER.warn("No dmabuf planes available for accelerated paint.");
            return 0;
        }

        var eglCapabilities = EglUtils.getCapabilities();
        var useModifiers = eglCapabilities.EGL_EXT_image_dma_buf_import_modifiers;
        var modifier = info.modifier;

        var planeAttribInts = useModifiers ? 10 : 6;
        var attribCapacity = 6 + (planeCount * planeAttribInts) + 1;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            var attribs = stack.mallocInt(attribCapacity);
            attribs.put(EGL14.EGL_WIDTH).put(width);
            attribs.put(EGL14.EGL_HEIGHT).put(height);
            attribs.put(EXTImageDMABufImport.EGL_LINUX_DRM_FOURCC_EXT).put(drmFormat);

            for (int i = 0; i < planeCount; i++) {
                long offset = info.plane_offsets[i];
                if (offset > Integer.MAX_VALUE) {
                    MCEF.INSTANCE.LOGGER.error("dmabuf plane offset too large for EGL attributes: {}", offset);
                    return 0;
                }

                attribs.put(CefConstants.DMA_BUF_PLANE_FD_ATTRS[i]).put(info.plane_fds[i]);
                attribs.put(CefConstants.DMA_BUF_PLANE_OFFSET_ATTRS[i]).put((int) offset);
                attribs.put(CefConstants.DMA_BUF_PLANE_PITCH_ATTRS[i]).put(info.plane_strides[i]);

                if (useModifiers) {
                    int modifierLo = (int) (modifier & 0xffffffffL);
                    int modifierHi = (int) ((modifier >>> 32) & 0xffffffffL);
                    attribs.put(CefConstants.DMA_BUF_PLANE_MODIFIER_LO_ATTRS[i]).put(modifierLo);
                    attribs.put(CefConstants.DMA_BUF_PLANE_MODIFIER_HI_ATTRS[i]).put(modifierHi);
                }
            }

            attribs.put(EGL14.EGL_NONE);
            attribs.flip();

            var eglImage = EglUtils.eglCreateImageKHR(
                    display,
                    EGL14.EGL_NO_CONTEXT,
                    EXTImageDMABufImport.EGL_LINUX_DMA_BUF_EXT,
                    0L,
                    attribs
            );

            if (eglImage == 0) {
                var eglError = EGL14.eglGetError();
                MCEF.INSTANCE.LOGGER.error(
                        "eglCreateImageKHR failed for dmabuf import. eglGetError=0x{}",
                        Integer.toHexString(eglError)
                );
                return 0;
            }

            var newTextureId = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, newTextureId);
            EXTEGLImageStorage.glEGLImageTargetTexStorageEXT(GL_TEXTURE_2D, eglImage, (IntBuffer) null);
            KHRImageBase.eglDestroyImageKHR(display, eglImage);

            var error = glGetError();
            if (error != GL_NO_ERROR) {
                MCEF.INSTANCE.LOGGER.error("glEGLImageTargetTexStorageEXT failed with error: {}", error);
                glDeleteTextures(newTextureId);
                return 0;
            }

            textureWidth = width;
            textureHeight = height;
            isAccelerated = true;
            unpainted = false;
            isBGRA = info.format != CefConstants.CEF_COLOR_TYPE_BGRA_8888;

            glBindTexture(GL_TEXTURE_2D, 0);
            return newTextureId;
        }
    }

    // ------------------------------------------------------------------------------------------
    // CPU paint (ByteBuffer upload).
    // ------------------------------------------------------------------------------------------

    protected void onPaint(ByteBuffer buffer, int width, int height) {
        // (Re)create the texture if it does not exist or the size changed.
        if (textureId == 0 || textureWidth != width || textureHeight != height) {
            if (textureId != 0) {
                glDeleteTextures(textureId);
            }
            textureId = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, textureId);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

            textureWidth = width;
            textureHeight = height;
        }

        if (transparent) {
            glEnable(GL_BLEND);
        }

        glBindTexture(GL_TEXTURE_2D, textureId);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, width);
        glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
        glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);

        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0,
                GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, buffer);

        isAccelerated = false;
        isBGRA = false;
        unpainted = false;
    }

    protected void onPaint(ByteBuffer buffer, int x, int y, int width, int height) {
        if (textureId == 0) {
            return;
        }
        glBindTexture(GL_TEXTURE_2D, textureId);
        glTexSubImage2D(GL_TEXTURE_2D, 0, x, y, width, height, GL_BGRA,
                GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
    }

    @Override
    public void close() {
        deleteTexture(textureId);
        textureId = 0;
        deleteTexture(sharedTextureId);
        sharedTextureId = 0;
        isAccelerated = false;
        unpainted = true;
    }

    private static void deleteTexture(int id) {
        if (id != 0) {
            glDeleteTextures(id);
        }
    }
}
