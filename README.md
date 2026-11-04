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
git submodule update --init --recursive java-cef
./gradlew build
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
dependencies { implementation("com.github.FPSMasterTeam:mcef-nova:1.0.2") }
```

### CEF native bundles

At runtime mcef downloads the prebuilt CEF bundle that matches the pinned java-cef commit. The host
chooses the mirror (see `MCEFHost`). The FPSMaster OSS mirror is being shut down along with the rest of
the FPSMaster services. Mirror the bundles to the GitHub Releases of this repository, or rely on the
upstream CCBlueX mirrors.

Bump `mod_version` in `gradle.properties` for every change consumers must pick up. Maven caches a fixed
version, so reusing a version number hides updates.

Forked from CCBlueX/mcef (LGPL-2.1). See `LICENSE`.
