# Server owner's guide

Install on Paper 26.2 with Java 25 and the official PacketEvents 2.14.0+ Spigot/Paper server JAR. New players start at Strength 1 with one random specialty.
UUID profiles persist through death, reconnects and restarts. `/smp stats` shows current state.
Strength changes appear in chat; repeated modifier refreshes do not resend the same value.

## Strength and custom items

Earned Strength adds 1.5 HP of melee damage per level above 1 by default. Level 1 leaves baseline
damage unchanged, and level 5 adds 6 HP. It uses a plugin-owned attack-damage attribute modifier,
so vanilla Strength potions stack independently; the default potion cap permits Strength II.
Sword damage, attack cooldown, armor, other plugins' modifiers and damage caps still apply.

PvP kills gain one level and deaths lose one down to 1. Natural deaths lose one by default; set
`strength.natural-death-loss: 0` to retain levels on uncredited environmental deaths. Each lost
level drops as an authentic Strength item at the death location. PvP killers below the cap gain
Strength directly; capped killers receive the victim's lost levels as physical items there. Both
reward and penalty are suppressed for the same pair in either direction for 600 seconds by default.
Combat-tagged environmental deaths can still be credited to the last player when
`combat.credit-tagged-deaths` is enabled. Lost crafted levels mature into regular Strength on
credited player kills, including tagged environmental deaths: killers below the cap gain regular
levels instantly, and capped killers receive regular Strength items. Uncredited environmental
deaths keep the Crafted Strength tag, as do withdrawals. Repeat-pair protection also applies to
this conversion.
Strength items spawn separately from ordinary inventory drops, including when `keepInventory` is on.

`/smp withdraw [amount]` withdraws one level by default, or the supplied positive amount, while
keeping at least 1. Free enough inventory capacity first; matching **Strength** items stack, and
**Crafted Strength** stacks separately. Both use identical plugin kind/format tags. Minecraft handles
pickup, merging, splitting, shift-clicks, hotbar swaps, double-click collection, dragging, drops and
hopper transfers. Cancelled pickups leave the item on the ground.

Right-click a **Strength** ghast tear in either hand to redeem one level. It can be traded and stored.
A crafted contribution withdraws as **Crafted Strength**, which can only be redeemed below level 3.
**Weapon Reroll** books change to a different specialty when right-clicked. Names and lore are protected;
renamed ordinary ghast tears are not accepted. Anvils, enchanting, smithing, grinding, brewing and
ingredient use cannot transform valid custom items. A periodic scan restores their presentation.

Existing registered bundles convert to tagged stacks on pickup, when opening physical inventories,
or during the player inventory scan. Their registrations retire before conversion, preserving
quantity and craft provenance. Invalid or already-converted bundles stay unusable. Read-only
container snapshots do not consume registrations. Back up progression and world/player inventories
together before upgrading or restoring data.

Item use, crafting and withdrawal support creative mode and vanished staff for testing. Strength
caps still apply. At a cap, while dead, or during a progression pause, redemption keeps the item.
Failed storage keeps active state unchanged and pauses progression. Item use saves progression
before consuming one physical item. A crash between a saved transaction and the inventory change
can leave the physical item and progression out of sync; retain consistent backups for recovery.

## Recipes

All three recipes come from the supplied Season Four wiki. Shapes are 3×3, read top to bottom.

| Output | Top row | Middle row | Bottom row |
| --- | --- | --- | --- |
| Crafted Strength ghast tear | End crystal, netherite ingot, end crystal | Nether star, enchanted golden apple, nether star | End crystal, netherite ingot, end crystal |
| Weapon Reroll book | Iron block, gold block, iron block | Gold block, diamond block, gold block | Iron block, gold block, iron block |
| Golden apple | Empty, gold ingot, empty | Gold ingot, apple, gold ingot | Empty, gold ingot, empty |

Toggle them independently under `recipes`. Custom Strength/reroll recipes support native bulk
and number-key crafting and automated crafters; their output tags preserve the item kind.
The ordinary vanilla golden-apple recipe also remains available. Banned-use items can still be
stored and consumed as ordinary recipe ingredients.

## Abilities

Passives require Strength 2; ultimates require 5. Run `/smp ability` with the specialty weapon held.
Sword also needs a five-hit combo and a sword in each hand. Bow/Crossbow ultimates trigger on their
tenth accepted hit automatically. See [the ability table and adaptations](design.md#weapon-behavior).

`abilities.enabled` disables the ability system. Thresholds, cooldowns, Trident damage and Crossbow
pull chance are configurable. The shared ultimate cooldown survives reconnects within the process;
server restart clears runtime cooldowns. Combat tags and equipment rules continue to apply.
The Shield ultimate's `abilities.shield-invulnerability` switch is independent of automatic immunity.

## Configuration and protections

Edit `plugins/EpicSmpPlugin/config.yml`, then run `/smp reload` (or `/smp admin reload`) or click
**Reload configuration** in the admin dashboard. A success message confirms the new settings are
active. Invalid snapshots are rejected and the current rules remain active. Strength minimum is 1,
maximum permits 1–10; passive/ultimate
thresholds may exceed the cap to intentionally leave abilities inaccessible.

| Section | Controls |
| --- | --- |
| `strength` | Initial/max levels, death losses, flat bonus, crafted ceiling |
| `combat` | Immunity toggle, tag/pair/grace timers, spawn radius, excluded worlds, logout/escape rules |
| `rules` | Item restrictions, PvP, mace enchants, explosive items, totems and incoming PvP cap |
| `limits` | Spear Lunge/mace timing, explosion cap, carried-item/enchant/potion caps, dimensions/arrows |
| `cooldowns` | Material-based consumable and projectile cooldowns in seconds |
| `abilities` | Enable/thresholds/ultimate cooldowns and ability balance |
| `recipes` | Individual recipe toggles |
| `display` | Anonymous invisible-killer death messages |

Automatic immunity is **off by default**. Toggle it in the dashboard or with
`/smp admin immunity on|off`. When enabled, the defaults grant new players 300s and respawns 20s of incoming
damage immunity, prevent protected outgoing PvP, and make a 32-block horizontal spawn cylinder safe.
Disabling immediately stops both checks and clears all saved grace; re-enabling does not restore
expired/cleared protection. Timers/radius can be set separately to zero. Repeat-pair farming limits
suppress progression only, not damage. They remain active regardless of immunity.

`display.hide-invisible-killers: true` replaces an invisible killer's death message with
`VictimName was slain.` It recognizes entity invisibility and potion invisibility, direct/projectile
attribution and the current tracked attacker. Setting false preserves normal death messages;
silent death messages stay silent. It changes public death text, not admin logs or account records.

The default individual switches block pearl, totem, enchanted-apple and TNT-minecart use,
equipped netherite armor and harmful effects. Mace enchants are controlled by the configured list;
Fire Aspect and Flame default to zero caps. Storage restocking and lava placement are blocked
while tagged. Each restriction is independently editable under `rules`, `combat` or `limits` and
has an admin dashboard button where applicable. `epicsmp.bypass` exempts equipment restrictions
and defaults false even for operators. It never bypasses item authenticity, protection or a pause.
Remove enchant/effect map entries for unlimited values; zero blocks an effect/enchantment.

Default timing: pearls 15s when allowed, wind charges 3s, apples 10s, enchanted apples 60s when
allowed, chorus fruit 20s, spear Lunge 3s and successful mace hits 5s. Accepted uses charge cooldowns;
misses/cancelled uses do not. Spear and mace delays share their keys across materials/hands, retaining
ordinary spear jabs. Tagged players cannot start Riptide, fly elytra, run configured escape commands,
enter protected spawn or change worlds. Shutdown disconnects are not penalized; tagged voluntary
logout causes a real death with the world's inventory gamerules and usual attribution.

Inventory limits count storage, armor, offhand and cursor; ground pickups over a cap are cancelled.
Existing excess items are kept but cannot be used while over the cap. Nested container contents
are not counted. Default carried limits are one totem and four enchanted apples. Potion caps apply
to persistent effects from all sources without changing durations; instant effects are separate.
Explosion and PvP caps each default to 24 HP final damage; zero disables a cap. Nether, End and tipped
arrows have independent season switches. Creative/Spectator and excluded worlds do not progress.

## Administration and testing

`/smp admin menu` opens a six-row owner-bound inventory dashboard. Choose primary/attacker and
secondary/victim from the online roster. Buttons inspect stats, persist rules, and invoke real tests.
Transfers and drags are cancelled; permissions are checked again when deferred actions execute.
Reload pending file edits before using a dashboard toggle, since it writes the active snapshot.

`/smp admin weapon <player> sword|axe|bow|crossbow|trident|shield` saves a specialty.
`/smp admin set <player> strength <value>` edits known online/offline accounts.
`/smp admin test help` lists the permission-checked real playtest toolbox:

| Test action | Effect |
| --- | --- |
| `status <player>` | Inspect Strength, specialty, health/attack attributes, tags/grace and cooldowns |
| `tag <attacker> <victim> <seconds>` / `untag <player>` | Set/clear real tags; logout restrictions apply |
| `protect <player> <seconds>` | Save grace while immunity is enabled; zero clears it |
| `cooldown <player> <material\|SPEAR_LUNGE\|MACE_HIT> <seconds>` | Replace one real cooldown; zero clears |
| `clear <player>` | Clear tags, plugin cooldowns and native item cooldowns |
| `pairreset <player> <other>` | Persistently clear a farming window |
| `hit <attacker> <victim> <damageHP>` | Real damage through events; can kill/drop items |
| `simulatekill <attacker> <victim>` | Saved death progression without physical death; qualifying overflow items drop |
| `lunge <player> <power>` | Lunge event probe, charging accepted cooldowns without movement |
| `give <player> <mace\|spear\|apple\|totem\|speed>` | Grant ordinary test equipment/items |
| `item <player> <strength\|crafted\|reroll>` | Give a tagged custom item |
| `ability <player>` | Activate under ordinary requirements and cooldown rules |
| `xp <player> <amount>` | Real XP through server event rules |
| `effect <player> <effect> <level> <seconds>` | Real persistent effect through drink/effect caps |
| `heal <player>` | Restore health, hunger and saturation; clear fire |

Both `epicsmp.admin` and `epicsmp.admin.test` are required. Test actions are logged and change real
accounts/items. Use dedicated test accounts; restoring timers does not reverse stats or item grants.
Native physics, shield interactions, offhand animation and other-plugin compatibility need playtests.

## Persistence

Schema 4 stores profiles, weapon specialties, crafted contributions, pending legacy item
registrations and pair windows. Earlier schemas migrate while preserving Strength/statistics and clamping to current
limits. Each mutation saves before publishing active state. Atomic replacement keeps the previous
commit in `players.yml.bak`. Corrupt snapshots abort startup rather than silently reset accounts.
Filesystem writes can still be lost on power failure; maintain full consistent external backups.

`/smp admin pause` temporarily stops PvP/progression and ends ability effects. Pause resets on
restart; `rules.pvp: false` persists a PvP disable. A runtime storage failure requires disk repair and
restart before resuming.


## Staff vanish

`/vanish` (alias `/v`) toggles your visibility. `/vanish on`, `/vanish off` and `/vanish status`
set or inspect it. `/vanish <player>` toggles another online staff member; explicit
`/vanish on|off|toggle|status <player>` forms are also supported. `/vanish help` shows the syntax.

| Permission | Default | Purpose |
| --- | --- | --- |
| `epicsmp.vanish` | op | Toggle yourself |
| `epicsmp.vanish.auto` | false | Auto-vanish once on login for each connection; also permits self-toggling |
| `epicsmp.vanish.see` | op | See vanished players in-world/in tab and receive private staff notices/chat |
| `epicsmp.vanish.others` | op | Manage other permitted online staff and list vanished players |
| `epicsmp.vanish.inspect` | op | Silently inspect containers while vanished |

For LuckPerms, give the automatic join permission with
`/lp user <name> permission set epicsmp.vanish.auto true`. Each new connection applies it once; a player can
use `/vanish off` for the rest of that session. Respawning, changing worlds or game mode, and
reloading the plugin preserve that choice. Ordinary toggles are saved atomically to `vanish.yml`
before visibility changes and resume on reconnect. Permission updates are checked every second;
revoking both self-vanish permissions restores visibility. Corrupt state rejects startup and a
failed state write rejects the toggle. Flight/collision/sound/sleep/spawning baselines also travel
with the player's persistent data, so a saved vanished player can recover their original properties
following a crash.

Status appears privately in chat and an aqua action bar. Fake leave/join announcements are controlled
by `vanish.fake-join-quit`; real vanished joins/quits, deaths and advancement broadcasts are silent.
Vanished players start in private chat on every vanish entry. Messages use an aqua `[Vanish]`
label and reach other vanished players, staff with `epicsmp.vanish.see`, and console, within the
chat event's audience. `/vanish chat public` lets you speak in public; `/vanish chat private` returns
to private chat. `/vanish chat` toggles and `/vanish chat status` checks your mode.
`vanish.default-chat-public` changes the entry default. Speech uses system chat packets so a hidden
signing session cannot break client chat validation.
Staff see the normal appearance of players they are allowed to see. Global display names, tab names
and Bukkit scoreboards remain owned by their existing plugins.

Per-viewer PacketEvents filtering covers tab entries, entity spawns/updates/equipment/effects/sounds,
passenger/leash links, command suggestions, scoreboard team membership and score rows. It recognizes
names and UUIDs in rendered text, component metadata and rows split across team prefix/entry/suffix.
Cached originals are reprojected on vanish/permission changes and restored on reveal; scoreboard
team moves, row/objective deletion and reconnect cleanup update that cache. System messages that
identify an unseen vanished player are suppressed for that viewer. Public `/list`, server-list counts
and samples, and Query responses also respect vanish. Packet decoding or replay failures cancel the affected
packet, disconnect that viewer to clear stale client data and log a diagnostic.

Vanished staff can attack players/mobs, place/break blocks, interact with the world and drop items
by default. Incoming damage, hunger loss, XP pickup, collision, gliding, physical triggers, raids
and vibrations are prevented independently. Staff do not influence natural spawning or sleep
requirements. Combat-tagged entry is refused by default. Combat progression and weapon abilities
are suspended for hidden staff by default, including rewards from their attacks. Explicit Strength
item use, crafting and withdrawal remain available for testing. Forced deaths
retain their inventory and levels. Each behavior has its own switch under `vanish` in `config.yml`.

Item pickup starts disabled. `/vanish pickup` toggles it for your current session, including Strength
items; `vanish.default-item-pickup` sets the default. `/vanish options` lists clickable switches; click `[ON]` or `[OFF]` to toggle that setting.
`/vanish option <name> <on|off|toggle>` persists and applies a change with `epicsmp.admin`.
Both `option` and `options` complete setting names and supported values with Tab. For example,
`/vanish option prevent-attacking on` blocks attacks, and
`/vanish option prevent-item-drop on` blocks dropping. Config-file edits apply with `/smp reload`.

`/vanish interactions [on|off|toggle|status]` provides an admin shortcut. `on` allows attacking,
breaking/placing, world/entity interactions, dropping, projectiles, buckets, consumption, mounting,
beds, inventory access, physical triggers, vibrations, raids, XP pickup and gliding. It also enables
item pickup and normal container access. `off` enables those restrictions, disables pickup and uses
silent read-only container inspection. `toggle` switches from restricted/mixed settings to `on`,
then from `on` to `off`; omitting the argument also toggles. Changes persist globally in one config
transaction and reset session pickup overrides. Visibility, chat audience, incoming-damage protection
and flight keep their own settings. The options list includes clickable group enable/disable buttons.

`vanish.silent-block-actions` suppresses nearby server-sent block break/place sounds and break
debris; hidden players' break animations are filtered. Actual block changes and damage remain visible.
Clients may still predict local block sounds; server packet filtering cannot remove those.
`vanish.prevent-mob-targeting` clears hostile targets, and `vanish.prevent-mob-looking` masks passive
player-looking goals near hidden staff while allowing mobs to look at visible players. Brain-based
mobs and other plugins' custom AI require a server playtest with the installed plugins.

`vanish.grant-flight`, `vanish.client-night-vision` and `vanish.action-bar` configure staff
conveniences. Night vision is an infinite client-only effect sent on entry or a new player entity,
without periodic remove/reapply flashes. Ordinary potion effects continue on the server and their
current state is restored on reveal. Previous movement and entity properties are restored on reveal,
disconnect or plugin shutdown. Changing game mode respects native creative and spectator flight.

Right-click a container while vanished with `epicsmp.vanish.inspect`, or look at it within six
blocks and use `/vanish inspect`. This opens a private, read-only snapshot, with no real chest
animation, sound, spectator-mode switch or inventory transfer. Clicks, hotbar keys, shift-clicks and
drags are cancelled. Snapshot items are cloned and never written back. The admin dashboard remains
available to staff observers.

Optional integrations:

- Essentials receives a hidden-state claim and recovers its previous hidden value afterward.
- Dynmap receives a plugin-owned invisibility assertion; releasing it respects other map hiders.
- PlaceholderAPI exposes `%epicsmp_visible_player_count%` and `%epicsmp_visible_players%`.
  These always exclude vanished players, even if the scoreboard evaluates them in a subject's
  context. Use them instead of an unfiltered server-online count in public scoreboards.
- Other plugins can read `vanished` metadata and `plugin.vanish().isVanished(uuid)` /
  `canSee(viewerUuid, subjectUuid)`. They must also honor Bukkit's `Player.canSee` for other hiders.

Vanilla map player arrows are anonymous, so they are conservatively suppressed for ordinary viewers
while any hidden staff member is online. Named marker text is filtered too; existing marker lists are
reprojected immediately and restored when visibility permits. Numeric totals, aliases, custom map
pixels, Discord/web exports and plugins sending packets silently require their own visibility-aware
integration; they cannot be attributed to a player by a generic packet filter. Audit those features
with your actual plugin set using an ordinary account alongside a staff account.

Vanish playtest checklist: join with the automatic permission; toggle and reconnect; grant/revoke
viewer permissions; teleport across worlds and respawn; check tab, `/list`, tab suggestions, server
ping and Query; test scoreboard team prefixes, fake entries and display-name rows before/after toggles;
verify pressure plates/sculk/containers, pickup/drop switches, private/public chat and block sounds;
confirm combat-tagged entry is refused and staff hits create no tags or Strength by default; check the public scoreboard count and any external maps/exports.
