# Builds and releases

EpicSmpPlugin supports **Paper 26.2 with Java 25**. The pinned compile API is
`io.papermc.paper:paper-api:26.2.build.132-stable`. Spigot and Folia are unsupported.

```bash
./gradlew clean build integrationTest --no-daemon --console=plain
./gradlew -Pver="v1.0.0" release --no-daemon --console=plain
```

Snapshots produce `build/libs/EpicSmpPlugin-<version>.jar`. A stable release produces
`build/libs/EpicSmpPlugin.jar`. A stable version requires an explicit `vX.Y.Z` property;
this does not publish a GitHub release by itself.

- Without `-Pver`, version is `yyMMdd-HHmm-SNAPSHOT` in UTC.
- `-Pver=vX.Y.Z-RC-N` produces `X.Y.Z-RC-N-SNAPSHOT`.
- `-Pver=vX.Y.Z` produces `X.Y.Z`.

`build` includes unit tests, Checkstyle and SpotBugs. Run `integrationTest` separately or include
it in the same invocation. CI builds on Linux and Windows with Java 25 and exercises storage/plugin
integration on Linux. Existing tag workflows prepare draft releases and optional Discord
notifications only when their repository variables/secrets are configured.

Install the official PacketEvents 2.14.0+ Spigot/Paper plugin alongside each release.
Its Maven compile API is not a standalone server plugin. Optional PlaceholderAPI, Essentials and
Dynmap hooks activate when installed.

Before shipping, inspect processed `plugin.yml`, verify that only one plugin JAR is installed,
and run the server playtest checklist in [usage.md](usage.md). Build success and MockBukkit tests
are not a claim of full client/server testing with a particular server's other plugins.

Windows agent terminals must use the deadline/log-redirection procedure in
[run-plugin](../.agents/skills/run-plugin/SKILL.md) for every Gradle invocation.
