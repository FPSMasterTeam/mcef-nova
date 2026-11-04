# Architecture

mcef-nova is a Java 17 library built with JDK 21. It separates CEF/JCEF and OpenGL
texture handling from Minecraft-version-specific draw APIs.

## Flow

The host installs `MCEFHost` (render-thread scheduling, GLFW handle and optional shutdown).
`MCEFDownloadManager` and `download/` provide matching native resources. `CefHelper` and
the CEF app/client/browser classes manage initialization, message pumping and browser lifecycle.
`MCEFRenderer` accepts CPU pixels or platform shared textures and exposes an OpenGL texture ID.
The host wraps that texture in its own game rendering pipeline and forwards browser input.

- `java-cef/`: vendored Java bindings; sources and BSD notice accompany distributable jars.
- `MCEFAccelerationSupport`: platform capability checks before enabling shared textures.
- `utils/EglUtils`: Linux EGL initialization, display capabilities and dma-buf image import.
- `src/test/`: attribute validation only, not native context or GPU integration.

## Extension points and ownership

Keep Minecraft calls in the host. Graphics resources must be created/disposed on the correct
context thread. `MCEF.update()` and message-pump mode must match host integration.
Browser creation disables shared textures when capability detection fails; it does not
recreate an accelerated browser after a runtime import failure. Native bundle distribution
and browser lifecycle testing remain host responsibilities.
