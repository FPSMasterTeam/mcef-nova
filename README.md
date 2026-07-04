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

## Distribution (private, via GitHub Packages)

This library is **closed-source** and distributed as a private Maven artifact on GitHub Packages at
`https://maven.pkg.github.com/FPSMasterTeam/mcef-nova` under the coordinate
`com.github.FPSMasterTeam:mcef-nova:<version>`. Both publishing here and consuming it elsewhere
require a GitHub token — GitHub Packages authenticates even for reads.

### Personal Access Token (one-time)

1. Go to **GitHub → Settings → Developer settings → Personal access tokens → Tokens (classic)** and
   **Generate new token (classic)**.
2. Select scopes:
   - `read:packages` — to *consume* the artifact (what most developers need).
   - `write:packages` — additionally, to *publish* from your machine.
3. Copy the token (starts with `ghp_…`).

### Configure your machine

Add the credentials to your **global** Gradle properties (`~/.gradle/gradle.properties`) so they
stay out of any repo:

```properties
gpr.user=<your-github-username>
gpr.key=<your-ghp_-token>
```

Both this repo's `build.gradle` (publishing) and the host mod's `build.gradle.kts` (consuming) read
`gpr.user` / `gpr.key`, falling back to the `GITHUB_ACTOR` / `GITHUB_TOKEN` environment variables
that GitHub Actions injects automatically. So CI needs no manual token — only `packages: read`
(consume) or `packages: write` (publish) permission in the workflow.

### Publishing

- **Release** (recommended for a real version): publish a GitHub Release; `.github/workflows/
  maven-publish.yml` builds and publishes that `mod_version` to GitHub Packages.
- **Snapshot**: a push to `master` publishes `<mod_version>-SNAPSHOT` via `maven-snapshot.yml`.
- **Locally**: `./gradlew publish` (needs `gpr.*` with `write:packages`).

Bump `mod_version` in `gradle.properties` for every change consumers must pick up — Maven caches a
fixed version, so reusing one hides updates.

### Consuming

In the host project, add the repository (with the same credential fallback) and depend on the
coordinate — see `FPSMaster-Nova/build.gradle.kts`:

```kotlin
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/FPSMasterTeam/mcef-nova")
        credentials {
            username = (project.findProperty("gpr.user") as String?) ?: System.getenv("GITHUB_ACTOR")
            password = (project.findProperty("gpr.key") as String?) ?: System.getenv("GITHUB_TOKEN")
        }
    }
}
dependencies { implementation("com.github.FPSMasterTeam:mcef-nova:1.0.1") }
```

For CI in a **different** repo to read this package, grant that repo access under
**mcef-nova → Package settings → Manage Actions access → Add repository (Read)**, or use a PAT secret.

Forked from CCBlueX/mcef (LGPL-2.1). See `LICENSE`.
