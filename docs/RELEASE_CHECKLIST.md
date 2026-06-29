# MCEF Release Checklist
- Bump mcef_version in parent gradle.properties
- Make a commit with the version name
- Make a tag with the version name
- Publish to maven repo with `./gradlew publishMaven`
- Update [mcef-fabric-example-mod](https://github.com/CinemaMod/mcef-fabric-example-mod) using the newly published maven artifact and make sure things work as expected
- Publish to CurseForge with `./gradlew publishCurgeforge`
- Manually upload the new release to Modrinth (download the jars from CF and reupload)
- Announce the new release on Discord
