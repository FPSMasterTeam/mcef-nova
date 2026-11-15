# Changelog

## 1.0.3 (final; maintenance ends on 2026-12-31)

- Replace the GPL EGL helper with a new LGPL implementation based on public EGL/LWJGL APIs.
- Cache capabilities per display and accept the NULL context/buffer required by dma-buf import.
- Disable accelerated browser creation when capability detection or native linkage fails.
- Include Java-CEF notices and matching Java binding sources in distributable jars.
- Retire GitHub Packages publishing; document vendored/offline integration and Linux limitations.
- Add archive, architecture, forking and known-issue documentation; no new tag or remote artifact published.

## 1.0.2 (2026-10-17)

### Changed
- Zero-copy accelerated paint (`shared_texture_enabled`) is now only enabled when the CEF message
  loop runs on the render thread. With the dedicated `CefMessageLoopThread`, CEF's accelerated OSR
  delivered exactly one `onAcceleratedPaint` and then stalled; browsers in that mode now use the CPU
  paint path.
- New system property `-Dmcef.pumpOnRenderThread=true` (Windows/Linux): initialise CEF on the render
  thread and pump it once per frame from `MCEF.update()`, the same model macOS already uses. Hosts
  that want zero-copy must set it before `MCEF.initialize()`. Without it, behaviour is unchanged
  (dedicated CEF thread, CPU paint).
- `MCEF.update()` pumps the message loop whenever there is no dedicated CEF thread, rather than only
  on macOS.
- Windows version detection reads `os.name` / `os.version` instead of running PowerShell or WMIC
  (CurseForge rejects mods that start shell interpreters).

### Removed
- `MCEFGlContext` (the hidden shared GL context used to import accelerated frames on the CEF thread).
  It only existed for the dedicated-thread zero-copy path, which stalls; nothing references it.

## 1.0.1

- External message pump clamp tightened to ~8 ms; `--enable-gpu-rasterization` and
  `--enable-zero-copy` switches.
- Zero-copy support for hosts drawing a raw GL id with a plain shader (MC < 1.21.5) via
  `MCEFRenderer.accelSwizzleBgra`.
- Published to GitHub Packages.

## 1.0.0

- Minecraft-version-agnostic fork of CCBlueX/mcef with a host SPI (`MCEFHost`).
- CEF moved off the render thread on Windows/Linux (dedicated message-loop thread, CPU paint
  handoff).
