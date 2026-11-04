# Third-party notices

- MCEF: CCBlueX and CinemaMod Group, LGPL-2.1-or-later. The complete license is in
  `LICENSE` and the binary jar's `LICENSE_mcef-nova`. Preserve source copyright notices.
- Java-CEF: Marshall A. Greenblatt, Google Inc. and contributors, BSD-style license.
  The full notice is `java-cef/LICENSE.txt`, included in both binary and source jars at
  `META-INF/licenses/java-cef/LICENSE.txt`. Matching bindings are included in the source jar.
- OkHttp: Square and contributors, Apache-2.0; distributed as a separate Maven dependency.
- LWJGL: BSD-style; SLF4J: MIT; Guava: Apache-2.0; Apache Commons: Apache-2.0;
  JSpecify: Apache-2.0. These are host-provided compile-only dependencies, not bundled here.
- CEF native bundles are downloaded separately from the upstream CCBlueX mirrors. Preserve the
  Chromium/CEF third-party notices supplied inside those bundles when redistributing them.

## EGL implementation provenance (1.0.3)

The previous GPL-3.0-or-later LiquidBounce `EglUtils` implementation has been removed.
The replacement is newly implemented for this project under LGPL-2.1-or-later using the public
EGL specification and LWJGL API documentation, not by modifying the previous implementation.
It caches capabilities per display, loads EGL before native calls, validates attribute buffers,
and uses LWJGL's public JNI dispatch for the NULL context/buffer required by dma-buf import.
The generated EGL wrapper rejects these required NULL arguments when LWJGL checks are enabled.

References:
- https://javadoc.lwjgl.org/org/lwjgl/egl/EGL.html
- https://javadoc.lwjgl.org/org/lwjgl/egl/KHRImageBase.html
- https://registry.khronos.org/EGL/extensions/EXT/EGL_EXT_image_dma_buf_import.txt

Earlier git history still contains the original GPL file. The replacement does not relicense
historical versions or remove obligations associated with distributing those versions.
