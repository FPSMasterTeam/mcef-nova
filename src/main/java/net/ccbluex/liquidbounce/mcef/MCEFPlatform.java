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

package net.ccbluex.liquidbounce.mcef;

import java.util.Locale;

public enum MCEFPlatform {

    LINUX_AMD64,
    LINUX_ARM64,
    WINDOWS_AMD64,
    WINDOWS_ARM64,
    MACOS_AMD64,
    MACOS_ARM64;

    /**
     * Operating-system family. Replaces {@code net.minecraft.util.Util.OS} so this library has no
     * Minecraft dependency.
     */
    private enum HostOs {
        WINDOWS, OSX, LINUX, UNKNOWN
    }

    private static HostOs detectOs() {
        var name = System.getProperty("os.name", "").toLowerCase(Locale.ENGLISH);
        if (name.contains("win")) {
            return HostOs.WINDOWS;
        } else if (name.contains("mac") || name.contains("darwin") || name.contains("osx")) {
            return HostOs.OSX;
        } else if (name.contains("nix") || name.contains("nux") || name.contains("aix") || name.contains("bsd")) {
            return HostOs.LINUX;
        }
        return HostOs.UNKNOWN;
    }

    public String getNormalizedName() {
        return name().toLowerCase(Locale.ENGLISH);
    }

    public boolean isLinux() {
        return switch (this) {
            case LINUX_AMD64, LINUX_ARM64 -> true;
            default -> false;
        };
    }

    public boolean isWindows() {
        return switch (this) {
            case WINDOWS_AMD64, WINDOWS_ARM64 -> true;
            default -> false;
        };
    }

    public boolean isMacOS() {
        return switch (this) {
            case MACOS_AMD64, MACOS_ARM64 -> true;
            default -> false;
        };
    }

    private static MCEFPlatform platformInstance;

    public static MCEFPlatform getPlatform() {
        if (platformInstance != null) {
            return platformInstance;
        }

        var operatingSystem = detectOs();
        var osArch = System.getProperty("os.arch").toLowerCase(Locale.ENGLISH);

        MCEF.INSTANCE.getLogger().info("Operating system: {}", operatingSystem);
        MCEF.INSTANCE.getLogger().info("Architecture: {}", osArch);

        var isAMD64 = osArch.contains("amd64") || osArch.contains("x86_64");
        var isArm = osArch.contains("aarch64") || osArch.contains("arm64");

        platformInstance = switch (operatingSystem) {
            case WINDOWS -> isAMD64 ? WINDOWS_AMD64 : isArm ? WINDOWS_ARM64 : null;
            case OSX -> isAMD64 ? MACOS_AMD64 : isArm ? MACOS_ARM64 : null;
            case LINUX -> isAMD64 ? LINUX_AMD64 : isArm ? LINUX_ARM64 : null;
            default -> throw new IllegalStateException("Unsupported platform: " + operatingSystem + " " + osArch);
        };

        return platformInstance;
    }

    public boolean isSystemCompatible() {
        var operatingSystem = detectOs();
        var osVersion = System.getProperty("os.version");
        MCEF.INSTANCE.getLogger().info("OS version: {}", osVersion);

        return switch (operatingSystem) {
            case WINDOWS -> checkWindowsCompatibility();
            case OSX -> checkMacOSCompatibility(osVersion);
            case LINUX -> true; // Assume Linux compatibility
            default -> false; // Unsupported OS
        };
    }

    /**
     * Windows 10+ only, without spawning PowerShell/wmic (CurseForge forbids shipping
     * mods that invoke shell interpreters at runtime).
     */
    private static boolean checkWindowsCompatibility() {
        var osName = System.getProperty("os.name", "").toLowerCase(Locale.ENGLISH);
        var osVersion = System.getProperty("os.version", "");
        MCEF.INSTANCE.getLogger().info("Windows os.name={}, os.version={}", osName, osVersion);

        if (osName.contains("windows 10") || osName.contains("windows 11")) {
            return true;
        }

        // Modern JDKs typically report "10.0" for Windows 10/11.
        try {
            var major = osVersion.split("\\.")[0];
            return Integer.parseInt(major) >= 10;
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            MCEF.INSTANCE.getLogger().warn("Could not parse Windows os.version; assuming compatible");
            return true;
        }
    }

    private static boolean checkMacOSCompatibility(String version) {
        if (version == null) {
            return false;
        }

        try {
            var parts = version.split("\\.");
            var majorVersion = Integer.parseInt(parts[0]);
            var minorVersion = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;

            return majorVersion > 10 || (majorVersion == 10 && minorVersion >= 15);
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            return false;
        }
    }

    public String[] requiredLibraries() {
        return switch (this) {
            case WINDOWS_AMD64, WINDOWS_ARM64 -> new String[]{
                    "d3dcompiler_47.dll",
                    "libGLESv2.dll",
                    "libEGL.dll",
                    "chrome_elf.dll",
                    "libcef.dll",
                    "jcef.dll"
            };
            case MACOS_AMD64, MACOS_ARM64 -> new String[]{
                    "libjcef.dylib"
            };
            case LINUX_AMD64, LINUX_ARM64 -> new String[]{
                    "libcef.so",
                    "libjcef.so"
            };
        };
    }
}
