# mcef-nova

A **Minecraft-version-agnostic** fork of [CCBlueX/mcef](https://github.com/CCBlueX/mcef)
(Minecraft Chromium Embedded Framework), maintained for FPSMaster Nova.

## What is different from upstream MCEF

Upstream MCEF ships a **separate artifact per Minecraft version** (`mcef:x.y.z-1.21.11`,
`-1.20.1`, …) because its renderer is wired directly into Minecraft's rendering pipeline
(`GpuTexture` / `TextureSetup` / `GlStateManager` / `Identifier`), which changes between versions.

This fork removes **all** of that coupling. The native layer — JCEF download/load, the Chromium
process, platform detection, GPU-accelerated shared-texture import (Win D3D11 / Linux dmabuf) — is
identical across Minecraft versions and is kept as-is. The only thing that used to be
version-specific, the renderer, has been reduced to its irreducible core: it lands CEF's pixels in a
plain OpenGL texture (via raw LWJGL `GL`/`EGL` calls) and exposes a single value across the
boundary:

```java
int glTextureId = browser.getRenderer().getTextureId();
```

Wrapping that texture id into a Minecraft draw call is the **host mod's** job, and is the only part
that differs per Minecraft version. As a result, **one build of this library serves every
Minecraft version.**

This library has **no `net.minecraft` or `com.mojang.blaze3d` dependency** and is published as a
plain Java library (not a Fabric mod). LWJGL, SLF4J, Guava and the Apache Commons libraries are
declared `compileOnly` because Minecraft already provides them at runtime.

## Host integration

The handful of things MCEF used to get from `Minecraft.getInstance()` are now provided by the host
through a small SPI (`MCEFHost`). Install it once during mod init, before creating any browser:

```java
MCEF.INSTANCE.setHost(new MCEFHost() {
    @Override public void schedule(Runnable task) {
        Minecraft.getInstance().execute(task);             // run on the render thread
    }
    @Override public long windowHandle() {
        return Minecraft.getInstance().getWindow().getWindow(); // GLFW window handle
    }
    @Override public void stopGame() {
        Minecraft.getInstance().stop();                    // macOS termination only; optional
    }
});
```

(Method names above are illustrative — use whatever the targeted Minecraft version maps them to;
that is the per-version glue Stonecutter handles in Nova.)

## Rendering, host side

```java
MCEFRenderer r = browser.getRenderer();
if (r.isTextureReady() && !r.isUnpainted()) {
    int texId    = r.getTextureId();   // a plain GL texture
    boolean bgra = r.isBGRA();          // pick the matching sampling pipeline
    // 1.21.5+: wrap texId in a GpuTexture/TextureSetup and submit to the GuiRenderState
    // 1.20.x : RenderSystem.bindTexture(texId) + an immediate-mode textured quad
}
```

## Building

```
# JDK 21; produces Java 17 bytecode. java-cef sources are already vendored.
./gradlew build test publishToMavenLocal
```

## Distribution

This library is **open source (LGPL-2.1)**. It was previously published as a private artifact on
GitHub Packages; that channel is retired and should not be relied on.

The supported ways to consume it are:

- **Vendored copy (what FPSMaster-Nova does).** Build the tag you need, then copy the jar, the sources
  jar and the pom into the host repo under `vendor/maven/com/github/FPSMasterTeam/mcef-nova/<version>/`
  and declare `maven { url = uri("${rootDir}/vendor/maven") }`. This needs no network access or
  credentials and keeps working after this repository is archived.
- **JitPack.** `maven("https://jitpack.io")` plus
  `implementation("com.github.FPSMasterTeam:mcef-nova:<tag>")`. JitPack builds the tag on first use
  (`jitpack.yml` pins JDK 21).
- **Local iteration.** `./gradlew publishToMavenLocal` here, `mavenLocal()` in the host.

```kotlin
dependencies { implementation("com.github.FPSMasterTeam:mcef-nova:1.0.3") }
```

### CEF native bundles

At runtime mcef downloads the prebuilt CEF bundle matching the pinned java-cef commit.
Defaults are the public LiquidBounce and CCBlueX resource mirrors in `MCEFSettings`;
there is no FPSMaster download source. Fully offline first launches still require a separately
provided native bundle. No native release assets are published by this build.

### EGL provenance and Linux limitations

Version 1.0.3 replaces the former GPL LiquidBounce EGL helper with a new LGPL-2.1-or-later
implementation based on the public EGL / LWJGL APIs. It caches capabilities per display and uses
public LWJGL JNI dispatch for the NULL context/buffer required by dma-buf import. See
`THIRD_PARTY_NOTICES.md` for references. Both jars include Java-CEF's BSD license; the source jar
also includes its Java bindings.

Linux hardware acceleration has **not been verified on a real Linux machine**. If initialization
or capability detection fails (including missing native EGL linkage), browser settings disable
shared textures and CEF uses software `onPaint` rendering. `WEBKIT_DISABLE_DMABUF_RENDERER=1` also
forces that software path. This is a creation-time fallback, not live recovery: if an already
accelerated browser later fails to import a dma-buf frame, it cannot switch itself to software.
The host must recreate that browser with shared textures disabled. Automatic runtime recreation
is not implemented. macOS and Windows do not call the Linux EGL helper.

After downloading build dependencies, `./gradlew --offline build test` reuses those caches.
A clean machine still needs the public Gradle/Maven dependencies on its first build.

Bump `mod_version` in `gradle.properties` for every change consumers must pick up. Maven caches a fixed
version, so reusing a version number hides updates.

Forked from CCBlueX/mcef (LGPL-2.1). See `LICENSE`.
