# Known issues in the final version

Final library version: 1.0.3. Maintenance ends on 2026-12-31.

- Linux EGL/dma-buf acceleration has not been tested on real Linux hardware in this work.
  Tests validate attribute buffers, not drivers, context sharing or frame import.
- Capability/native-linkage failure disables shared textures at browser creation. An already
  accelerated browser cannot automatically switch to CPU after a runtime import failure;
  the host must recreate it with shared textures disabled.
- A fully offline first launch needs a separately supplied matching native CEF bundle.
  Default public mirrors may fail or disappear; no native release assets were published here.
- Zero-copy requires render-thread pumping. The dedicated CEF message-loop thread uses CPU
  paint because its accelerated path previously stalled after one frame.
- Native browser lifetime, platform GPU compatibility and host integration still require
  real-client testing. This library's build success is not a platform acceptance result.
- First clean builds require public Gradle/Maven dependencies; `--offline` needs cached artifacts.
  Current Gradle emits deprecation warnings concerning future Gradle 10 compatibility.
- Historical commits contain a GPL EGL helper; the replacement does not change old licenses.
