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

Forked from CCBlueX/mcef (LGPL-2.1). See `LICENSE`.
