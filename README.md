# EpicSmpPlugin

A **Paper 26.2 / Java 25** Strength SMP plugin with Season Four weapon specialties, crafting and
configurable combat management.

- Strength starts at **1**, ranges from **1–5**, and adds **1.5 melee damage per level above 1**.
  Earned Strength uses an owned attribute modifier. Vanilla Strength potions stack independently.
- PvP kills grant Strength directly to killers below the cap. Capped killers receive the victim's
  lost Strength as physical items at the death location. Uncredited mob and environmental deaths
  also drop the Strength lost there.
- `/smp withdraw [amount]` exchanges excess Strength for named **ghast tears**. Right-click to
  redeem or trade them. Identical tagged items stack, split, drag and transfer through hoppers
  using native inventory behavior; custom items cannot be renamed,
  enchanted, brewed or consumed in other recipes. Crafted Strength works up to level 3 and keeps
  that provenance through withdrawal and trading. Credited PvP deaths turn lost crafted levels
  into regular Strength rewards.
- Six random, persistent specialties: Sword, Axe, Bow, Crossbow, Trident and Shield. Passives
  unlock at Strength 2 and ultimates at 5. Craft a Weapon Reroll to change specialty.
- Season Four crafting recipes for Strength, rerolls and four-ingot golden apples.
- Combat tags, logout deaths, persistent repeat-pair farming limits, item cooldowns, spear Lunge
  and mace timing, enchant/effect/inventory caps, explosion limits and dimension switches.
- Automatic immunity is **off by default**; `/smp admin immunity on|off` controls spawn safety
  and join/respawn grace. The Shield specialty's earned ultimate has its own toggle.
- Strength changes appear in chat. Invisible killers receive anonymous death messages by default,
  controlled by `display.hide-invisible-killers`.
- Staff vanish with PacketEvents filtering, automatic join permissions, private status, silent
  container inspection, private/public chat modes, configurable world interactions, persistent state
  and viewer-specific scoreboard protection.
- Six-row admin inventory dashboard, player selectors, persistent rule toggles and a playtest toolbox.
- Atomic UUID-based YAML snapshots, previous-commit backups, offline admin edits and validated reloads.

## Install and commands

Build with `./gradlew build integrationTest`, or copy `build/libs/EpicSmpPlugin.jar` from a stable
release to the server's `plugins/` directory alongside
[PacketEvents 2.14.0 or newer](https://github.com/retrooper/packetevents/releases), then restart.
Use the official Spigot/Paper PacketEvents server JAR. PlaceholderAPI, Essentials and Dynmap
integrations activate when those plugins are installed.

| Command | Purpose |
| --- | --- |
| `/vanish` / `/v` | Toggle your staff vanish |
| `/vanish on` / `off` / `status` | Set or inspect vanish state |
| `/vanish on <player>` / `off <player>` | Manage another online staff member |
| `/vanish list` / `pickup` / `inspect` | Staff roster, item pickup and silent container snapshot |
| `/vanish chat private` / `public` | Choose your vanished chat audience |
| `/vanish options` / `option <name> <on\|off\|toggle>` | Clickable settings and tab-completed switches |
| `/vanish interactions [on\|off\|toggle\|status]` | Toggle world/inventory interactions together |
| `/smp stats [player or UUID]` | Strength, specialty and kill/death statistics |
| `/smp rules` | Inspect current restrictions |
| `/smp withdraw [amount]` | Withdraw levels while keeping at least 1 |
| `/smp ability` | Activate your held specialty's ultimate |
| `/smp reload` | Validate and apply edited `config.yml` (admin only) |
| `/smp admin menu` | Inventory dashboard and player test controls |
| `/smp admin set <player> strength <value>` | Save an online/offline account edit |
| `/smp admin weapon <player> <specialty>` | Assign a specialty for testing |
| `/smp admin immunity <on\|off>` | Persist the automatic immunity switch |
| `/smp admin pause` / `resume` | Temporarily pause PvP and progression |
| `/smp admin test help` | Playtest tags, items, abilities, damage, XP, potions and progression |

`/epicsmp` aliases `/smp`. `epicsmp.use` defaults true. `epicsmp.admin` defaults op and includes
`epicsmp.admin.test`. `epicsmp.bypass` defaults **false even for operators**; it bypasses equipment,
season and cooldown restrictions, but never progression persistence, immunity or a PvP pause.

`epicsmp.vanish.auto` defaults false and vanishes its holder once on login for each connection.
`epicsmp.vanish`, `.vanish.see`, `.vanish.others` and `.vanish.inspect` default op.
Read [the vanish guide](docs/usage.md#staff-vanish) for privacy behavior and integrations.

## Documentation and verification

- [Research and design](docs/design.md)
- [Server owner's guide and recipes](docs/usage.md)
- [Development](docs/customization.md)
- [Build and release](docs/releases.md)

`build` runs unit tests, Checkstyle and SpotBugs. `integrationTest` uses Paper 26.2 MockBukkit and
real YAML persistence. Verify native movement, shield timing and combat alongside your other plugins
on a real Paper server before opening a season. Windows agent builds use the bounded procedure in
[run-plugin](.agents/skills/run-plugin/SKILL.md).

Derived from the repository's GPL-3.0 template. Preserve the existing LICENSE and attribution.
