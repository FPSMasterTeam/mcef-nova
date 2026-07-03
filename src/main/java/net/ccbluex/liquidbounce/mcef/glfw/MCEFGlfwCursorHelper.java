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

package net.ccbluex.liquidbounce.mcef.glfw;

import org.cef.misc.CefCursorType;
import org.lwjgl.glfw.GLFW;

import java.util.EnumMap;
import java.util.Map;

public class MCEFGlfwCursorHelper {

    private static final Map<CefCursorType, Long> CEF_TO_GLFW_CURSORS = new EnumMap<>(CefCursorType.class);

    /**
     * Helper method to get a GLFW cursor handle for the given {@link CefCursorType} cursor type.
     * <p>
     * Many {@link CefCursorType} values have no GLFW standard-cursor equivalent and carry a
     * {@code glfwId} of {@code 0} (e.g. WAIT, HELP, CELL, GRAB...), and some map to shapes that a
     * given GLFW build may not support. Calling {@link GLFW#glfwCreateStandardCursor} with such a
     * shape makes GLFW spam {@code Invalid standard cursor 0x...} errors on every cursor change.
     * <p>
     * In those cases we fall back to the {@code NULL} (0) handle, which {@link GLFW#glfwSetCursor}
     * interprets as the default arrow cursor. The fallback (like every other result) is cached, so
     * we never re-attempt creation or re-log the error for a given cursor type.
     */
    public static long getGLFWCursorHandle(CefCursorType cursorType) {
        if (CEF_TO_GLFW_CURSORS.containsKey(cursorType)) {
            return CEF_TO_GLFW_CURSORS.get(cursorType);
        }

        long glfwCursorHandle = 0L;
        if (cursorType.glfwId != 0) {
            // Returns 0 (NULL) if GLFW doesn't support this standard-cursor shape; we cache that
            // 0 the same way, so glfwSetCursor falls back to the default arrow.
            glfwCursorHandle = GLFW.glfwCreateStandardCursor(cursorType.glfwId);
        }

        CEF_TO_GLFW_CURSORS.put(cursorType, glfwCursorHandle);
        return glfwCursorHandle;
    }

}
