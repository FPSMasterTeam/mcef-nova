# Forking and distribution

1. Preserve LGPL-2.1-or-later notices, `LICENSE`, `THIRD_PARTY_NOTICES.md` and Java-CEF's BSD notice.
   Distribute corresponding library sources and satisfy LGPL obligations when bundling it.
2. Change `maven_group`, `archives_base_name` and `mod_version` in `gradle.properties`,
   and the project name in `settings.gradle`. Update host Maven coordinates and vendor paths.
3. Renaming `net.ccbluex.liquidbounce.mcef` requires updating imports throughout the library
   and hosts. Do not casually rename `org.cef` or native binding signatures: they must match JCEF.
   This is not a mod and has no modid or Azure client id.
4. Build with JDK 21:
   `./gradlew --no-daemon --max-workers=2 build test publishToMavenLocal`.
   Output targets Java 17. Cached builds can use `--offline`.
5. Vendor jar, sources jar and POM under the host's Maven directory, preserving group paths.
   Alternatively configure your own JitPack repository coordinates
   `com.github.<owner>:<repo>:<tag-or-commit>` and retain the JDK setting in `jitpack.yml`.

Native bundles must match the pinned Java-CEF commit. Change download mirrors in `MCEFSettings`
only to sources you can maintain, retain CEF/Chromium notices, and verify archive integrity and
platform coverage. There is no FPSMaster download endpoint to revive.

GitHub Packages publishing has been removed. Configure any future publication under your own
identity; this archive work created no new tag, Release or native assets.
Old git history includes the former GPL EGL helper. The new implementation does not relicense
historical distributions; assess their obligations separately.
