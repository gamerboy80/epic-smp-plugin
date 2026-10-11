# Development and customization

The project is now EpicSmpPlugin, targeting only Paper 26.2 with Java 25. Its entry point is
`com.epicsmp.EpicSmpPlugin`; `rootProject.name` matches the entry point class because `plugin.yml`
uses resource substitutions. Native Paper `BasicCommand` registers `/smp` and `/epicsmp`; do not
also declare them in `plugin.yml`.

`Settings` validates a snapshot, `Progression` handles domain transactions, and `YamlStateStore`
commits immutable `State`/`Profile` snapshots. `CombatTracker` tracks transient tags/cooldowns.
`GameplayListener` handles attribution, privacy and potion caps; `RuleListener` enforces items and escapes.
`LimitListener` enforces Lunge/mace cooldowns, inventory use limits, dimension gates and explosion
caps. Potion level caps preserve duration in one `GameplayListener` path.
`SmpItems` controls token-backed recipes and protects custom items; `WeaponAbilities` owns
specialty effects and modifier cleanup. Season-inspired restrictions live in the ordinary rule system.
`AdminTestCommands` exposes permission-checked playtest operations; saved progression mutations
still use `Progression` transactions. Event probes exercise rules without simulating native physics.
`AdminDashboard` delegates test buttons to those same commands. Its holders bind viewer, player
selections and roster pagination; clicks cannot transfer items and recheck permissions after
deferral. Configuration edits validate before atomic replacement and use the regular reload path.
The entry point applies transient attribute modifiers, fails closed on runtime storage errors, and owns
lifecycle cleanup. The plugin has no third-party runtime dependencies. Shadow produces the single
release JAR. Paper API, MockBukkit, Mockito, and static-analysis libraries are build/test only.

New gameplay rules should have configurable defaults, bounded validation, and meaningful behavioral
tests. Keep numerical balance separate from event handling. Never mutate profiles before
persistence succeeds. Custom-item tokens must redeem only once and preserve craft provenance.
Changes to saved fields require deliberate schema migration; invalid state must not reset silently.

Keep README, usage/design/release docs, canonical `.agents/skills/`, AGENTS.md and Java/CI versions
in sync. Preserve LICENSE and original template attribution. Do not edit generated CLAUDE.md or
`.claude/skills/`. Local and CI builds must pass unit/integration tests, Checkstyle and SpotBugs.
