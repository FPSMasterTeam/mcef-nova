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
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

/**
 * This class mostly just interacts with org.cef.* for internal use in {@link MCEF}
 */
public final class CefHelper {

    private CefHelper() {
    }

    private static boolean initialized;
    private static CefApp cefAppInstance;
    private static CefClient cefClientInstance;
    private static CefMessageLoopThread messageLoopThread;

    private static void setUnixExecutable(File file) {
        Set<PosixFilePermission> perms = EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
        );

        try {
            Files.setPosixFilePermissions(file.toPath(), perms);
        } catch (IOException e) {
            MCEF.INSTANCE.getLogger().error("Failed to set file permissions for " + file.getPath(), e);
        }
    }

    public static boolean init() {
        var platform = MCEFPlatform.getPlatform();
        var natives = platform.requiredLibraries();
        var settings = MCEF.INSTANCE.getSettings();
        var platformDirectory = MCEF.INSTANCE.getResourceManager().getPlatformDirectory();

        // Ensure binaries are executable
        if (platform.isLinux()) {
            var jcefHelperFile = new File(platformDirectory, "jcef_helper");
            setUnixExecutable(jcefHelperFile);
        } else if (platform.isMacOS()) {
            var jcefHelperFile = new File(platformDirectory, "jcef_app.app/Contents/Frameworks/jcef Helper.app/Contents/MacOS/jcef Helper");
            var jcefHelperGPUFile = new File(platformDirectory, "jcef_app.app/Contents/Frameworks/jcef Helper (GPU).app/Contents/MacOS/jcef Helper (GPU)");
            var jcefHelperPluginFile = new File(platformDirectory, "jcef_app.app/Contents/Frameworks/jcef Helper (Plugin).app/Contents/MacOS/jcef Helper (Plugin)");
            var jcefHelperRendererFile = new File(platformDirectory, "jcef_app.app/Contents/Frameworks/jcef Helper (Renderer).app/Contents/MacOS/jcef Helper (Renderer)");
            setUnixExecutable(jcefHelperFile);
            setUnixExecutable(jcefHelperGPUFile);
            setUnixExecutable(jcefHelperPluginFile);
            setUnixExecutable(jcefHelperRendererFile);
        }

        if (platform.isLinux()) {
            var switches = settings.getCefSwitches();
            if (switches.stream().noneMatch(s -> s.startsWith("--use-angle"))) {
                switches.add("--use-angle=gl");
            }

            if (switches.stream().noneMatch(s -> s.startsWith("--ozone-platform"))) {
                var ozonePlatform = "x11";
                // wayland ozone platform has issues with clipboard copy and paste ON WAYLAND(???)
                // var ozonePlatform = System.getenv("WAYLAND_DISPLAY") != null ? "wayland" : "x11";
                switches.add("--ozone-platform=" + ozonePlatform);
            }
        }

        var cefSwitches = settings.getCefSwitches().toArray(new String[0]);

        for (var nativeLibrary : natives) {
            var nativeFile = new File(platformDirectory, nativeLibrary);

            if (!nativeFile.exists()) {
                MCEF.INSTANCE.getLogger().error("Missing native library: " + nativeFile.getPath());
                throw new RuntimeException("Missing native library: " + nativeFile.getPath());
            }
        }

        System.setProperty("jcef.path", platformDirectory.getAbsolutePath());

        var cefSettings = new CefSettings();
        cefSettings.windowless_rendering_enabled = true;
        cefSettings.background_color = cefSettings.new ColorType(0, 255, 255, 255);
        cefSettings.cache_path = settings.getCacheDirectory() != null ? settings.getCacheDirectory().getAbsolutePath() : null;
        // Set the user agent if there's one defined in MCEFSettings
        if (settings.getUserAgent() != null) {
            cefSettings.user_agent = settings.getUserAgent();
        } else {
            // If there is no custom defined user agent, set a user agent product.
            // Work around for Google sign-in "This browser or app may not be secure."
            cefSettings.user_agent_product = "MCEF/2";
        }

        if (platform.isMacOS()) {
            // macOS: CEF work is marshalled onto the AppKit main thread by the native layer, and
            // the game pumps N_DoMessageLoopWork once per frame from MCEF.update(). Initialize
            // inline (on the render thread), exactly as before.
            if (!CefApp.startup(cefSwitches)) {
                return false;
            }
            cefAppInstance = CefApp.getInstance(cefSwitches, cefSettings);
            cefClientInstance = cefAppInstance.createClient();
            return initialized = true;
        }

        // Windows/Linux: run the whole CEF browser-process-UI-thread lifecycle on a dedicated
        // thread. CefInitialize binds the UI thread to whichever thread calls it, so startup,
        // CefApp construction and client creation (which triggers N_Initialize) must all happen
        // there — and the scheduler must be installed FIRST, because CEF starts requesting pump
        // work during initialization already.
        messageLoopThread = new CefMessageLoopThread();
        CefApp.setMessagePumpScheduler(messageLoopThread);
        var pumpThread = messageLoopThread;
        Boolean ok = pumpThread.invokeAndWait(() -> {
            if (!CefApp.startup(cefSwitches)) {
                return false;
            }
            cefAppInstance = CefApp.getInstance(cefSwitches, cefSettings);
            cefClientInstance = cefAppInstance.createClient();
            pumpThread.attachPumpTarget(cefAppInstance);
            return true;
        });
        if (ok == null || !ok) {
            CefApp.setMessagePumpScheduler(null);
            messageLoopThread.stop();
            messageLoopThread = null;
            return false;
        }

        return initialized = true;
    }

    public static void shutdown() {
        if (isInitialized()) {
            initialized = false;

            Runnable dispose = () -> {
                try {
                    cefClientInstance.dispose();
                } catch (Exception e) {
                    MCEF.INSTANCE.getLogger().error("Failed to dispose CefClient", e);
                }

                try {
                    cefAppInstance.dispose();
                } catch (Exception e) {
                    MCEF.INSTANCE.getLogger().error("Failed to dispose CefApp", e);
                }
            };

            if (messageLoopThread != null) {
                // N_Shutdown must run on the thread that ran CefInitialize. Bounded wait: this is
                // called from a JVM shutdown hook and must not wedge process exit.
                messageLoopThread.invokeAndWait(() -> {
                    dispose.run();
                    return null;
                }, 5000);
                messageLoopThread.stop();
                messageLoopThread = null;
            } else {
                dispose.run();
            }
        }
    }

    /**
     * The dedicated CEF UI thread, or null on macOS (where the render thread pumps per-frame).
     */
    public static CefMessageLoopThread getMessageLoopThread() {
        return messageLoopThread;
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static CefApp getCefApp() {
        return cefAppInstance;
    }

    public static CefClient getCefClient() {
        return cefClientInstance;
    }
}
