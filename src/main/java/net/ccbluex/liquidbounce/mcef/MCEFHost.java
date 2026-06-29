/*
 * MCEF (Minecraft Chromium Embedded Framework)
 * Copyright (C) 2025 CCBlueX
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 */

package net.ccbluex.liquidbounce.mcef;

/**
 * The (small) bridge MCEF needs back into the host game. This is the entire surface that used to be
 * served by {@code Minecraft.getInstance()}; pulling it behind an interface is what lets this
 * library compile and ship without any {@code net.minecraft} dependency, so a single build can be
 * shared across every Minecraft version. The host mod supplies an implementation once at startup
 * via {@link MCEF#setHost(MCEFHost)}.
 */
public interface MCEFHost {

    /**
     * Run a task on the client/render thread (the thread that owns the OpenGL context). MCEF uses
     * this to create and dispose browser textures safely. Equivalent to {@code Minecraft#schedule}.
     */
    void schedule(Runnable task);

    /**
     * The GLFW window handle of the game window, used when setting the OS cursor for the browser.
     * Equivalent to {@code Minecraft.getInstance().getWindow().handle()}.
     */
    long windowHandle();

    /**
     * Request the game to stop. Only used on macOS, where CEF drives termination. May be a no-op
     * if the host has no graceful-shutdown hook.
     */
    default void stopGame() {
        // optional
    }
}
