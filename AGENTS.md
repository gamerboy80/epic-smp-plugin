This repository is **EpicSmpPlugin**, a Paper 26.2 Minecraft plugin combining Strength SMP Season Four
progression, weapon abilities, crafting and server combat management.

## Architecture

**Entry point:** `com.epicsmp.EpicSmpPlugin extends JavaPlugin`. `plugin.yml` expands
`${PACKAGE}.${NAME}` from Gradle group and rootProject.name; the project name must match this class.
Java toolchain and CI use Java 25. Paper API is pinned to `26.2.build.132-stable`.

**Commands:** Native Paper `BasicCommand` registers `/smp` and `/epicsmp` programmatically.
Do not duplicate these in `plugin.yml`; permission declarations belong there.

**Progression:** Immutable `Profile` and `State` snapshots, `Progression` transactions, and
`YamlStateStore` atomic replacement/previous-commit backup. Changes are saved before active state
changes. Corrupt data aborts startup; runtime storage failure pauses PvP. UUIDs identify accounts,
including offline players. Schema 4 retains weapon specialties, crafted contributions and
registrations needed to convert existing item bundles; earlier snapshots migrate. Strength floor is 1.

**Rules:** `Settings` validates independent YAML snapshots. `GameplayListener` handles potion caps,
PvP attribution, grace, deaths, combat logging and spawn entry. `RuleListener` handles mace
restrictions, consumable/projectile cooldowns, explosive items, totems, elytra and escape commands.
`LimitListener` handles shared spear Lunge/mace timing, explosion caps, carried-item acquisition/use
limits, dimension gates, tipped arrows and periodic effect normalization. Potion level caps retain durations in `GameplayListener`. `AdminTestCommands` provides explicit real
playtest operations through `/smp admin test`; both admin and test permissions are required.
`AdminDashboard` provides `/smp admin menu`, uses owner-bound inventory holders, cancels transfers,
rechecks permissions on deferred clicks and validates/persists rule toggles before activation.
`CombatTracker` stores transient tags/cooldowns; persistent pair windows prevent farming across
restarts. Melee Strength uses only a plugin-owned namespaced additive attribute modifier, independent
of vanilla potion effects. `WeaponAbilities` owns transient combo/ultimate effects and attack-speed
modifiers. `SmpItems` protects custom ghast tears/rerolls, registers native recipes and validates identical kind/format tags.
Craft provenance survives trading, withdrawal and uncredited environmental deaths; credited PvP
losses become regular Strength rewards. Item use, crafting and withdrawal support creative testing while preserving caps and
storage-failure protection. Inventory clicks, drags, pickups, hopper
transfers and ground merges remain native. Existing ID bundles convert once by retiring their
registrations before replacing the physical stack. Never bypass item-acquisition cancellations. Individual rules live in the existing rule
listeners and configuration; avoid a separate aggregate season mode.

**Vanish:** Staff vanish was extracted to the standalone `vanish-plugin` project. EpicSmpPlugin no
longer registers `/vanish`, no longer requires PacketEvents, and no longer applies vanish-specific
combat or progression exclusions. Do not reintroduce the old Vanish* classes here.

**Packaging:** `shadowJar` is the sole JAR output; `jar` is disabled and `assemble` depends on
Shadow. No runtime libraries are shaded. The original template's cw-commons, Jackson, Hibernate
Validator and CommandAPI dependencies were removed because native Paper APIs cover these needs.
Paper, MockBukkit, Mockito and static-analysis dependencies remain build/test only. The release JAR
contains no shaded runtime libraries.

**Versioning:** no `-Pver` gives `yyMMdd-HHmm-SNAPSHOT`; `-Pver=vX.Y.Z-RC-N` gives
`X.Y.Z-RC-N-SNAPSHOT`; `-Pver=vX.Y.Z` gives stable `X.Y.Z`. `release` strips stable JAR filenames.

**Tests:** `test` exercises progression, config, tags and listener behavior. `integrationTest`
exercises real YAML persistence and the plugin under Paper 26.2 MockBukkit. Use JUnit temporary
directories for generated files. `build` includes unit tests, Checkstyle and SpotBugs;
run `integrationTest` additionally. Plugin support is Paper only, not Spigot or Folia.

## Agent instructions

1. Canonical skills live in `.agents/skills/`; `.claude/skills/` is a generated mirror.
2. `CLAUDE.md` is a generated copy of this file.
3. Do not edit/create `CLAUDE.md` or `.claude/skills/` files. Claude hooks synchronize mirrors.
4. For every Gradle invocation on Windows agent terminals, use the bounded log-redirected
   procedure in `.agents/skills/run-plugin/SKILL.md`; never foreground `gradlew.bat`.
5. Preserve existing user changes, LICENSE and attribution. Keep documentation and configuration
   synchronized with behavior. Do not reintroduce previously removed features without an explicit
   new request.
6. Describe current features positively. Do not mention removed or excluded features in product
   copy, commands, menus, logs, documentation or release summaries unless the user explicitly asks
   for that comparison or history. Remove obsolete references when scope changes; do not add
   absence claims or disclaimers about features that were removed.
