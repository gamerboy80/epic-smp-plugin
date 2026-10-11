# Server owner's guide

Install on Paper 26.2 with Java 25. New players start at Strength 1 with one random specialty.
UUID profiles persist through death, reconnects and restarts. `/smp stats` shows current state.
Strength changes appear in chat; repeated modifier refreshes do not resend the same value.

## Strength and custom items

Earned Strength adds 1.5 HP of melee damage per level above 1 by default. Level 1 leaves baseline
damage unchanged, and level 5 adds 6 HP. It uses a plugin-owned attack-damage attribute modifier,
so vanilla Strength potions stack independently; the default potion cap permits Strength II.
Sword damage, attack cooldown, armor, other plugins' modifiers and damage caps still apply.

PvP kills gain one level and deaths lose one down to 1. Natural deaths lose one by default; set
`strength.natural-death-loss: 0` to retain levels on environmental deaths. A capped killer's
qualifying victim drops one Strength item if the victim had a level to lose. Both reward and
penalty are suppressed for the same pair in either direction for 600 seconds by default.

`/smp withdraw [amount]` withdraws one level by default, or the supplied positive amount, while
keeping at least 1. Free one storage slot per item first. Right-click a **Strength** ghast tear
in either hand to redeem one level. It can be traded and stored. A crafted contribution withdraws
as **Crafted Strength**, which can only be redeemed below level 3. **Weapon Reroll** books change
to a different specialty when right-clicked. Items keep their immutable names, lore and saved IDs;
renamed ordinary ghast tears are not accepted. Anvils, enchanting, smithing, grinding, brewing
and ingredient use cannot transform valid custom items. A periodic scan restores their presentation.

Items have unique persisted IDs and can redeem only once, even after restart. At a cap, during
pause, or outside active Survival/Adventure play, redemption keeps the item. Failed storage keeps
active state unchanged and pauses progression. A crash between the state commit and physical
inventory delivery can lose an item; retain backups for recovery. Never restore players.yml and
world/player inventories from different points in time: old backups can re-enable old tokens.

## Recipes

All three recipes come from the supplied Season Four wiki. Shapes are 3×3, read top to bottom.

| Output | Top row | Middle row | Bottom row |
| --- | --- | --- | --- |
| Crafted Strength ghast tear | End crystal, netherite ingot, end crystal | Nether star, enchanted golden apple, nether star | End crystal, netherite ingot, end crystal |
| Weapon Reroll book | Iron block, gold block, iron block | Gold block, diamond block, gold block | Iron block, gold block, iron block |
| Golden apple | Empty, gold ingot, empty | Gold ingot, apple, gold ingot | Empty, gold ingot, empty |

Toggle them independently under `recipes`. Custom Strength/reroll crafts require an ordinary click
with an empty cursor and issue one item at a time; shift/number-key/automated crafting is blocked.
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

Edit `plugins/EpicSmpPlugin/config.yml`, then `/smp admin reload`. Invalid snapshots are rejected and
the current rules remain active. Strength minimum is 1, maximum permits 1–10; passive/ultimate
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
| `item <player> <strength\|crafted\|reroll>` | Issue an authentic saved custom item |
| `ability <player>` | Activate under ordinary requirements and cooldown rules |
| `xp <player> <amount>` | Real XP through server event rules |
| `effect <player> <effect> <level> <seconds>` | Real persistent effect through drink/effect caps |
| `heal <player>` | Restore health, hunger and saturation; clear fire |

Both `epicsmp.admin` and `epicsmp.admin.test` are required. Test actions are logged and change real
accounts/items. Use dedicated test accounts; restoring timers does not reverse stats or item grants.
Native physics, shield interactions, offhand animation and other-plugin compatibility need playtests.

## Persistence

Schema 3 stores profiles, weapon specialties, crafted contributions, item IDs/provenance and pair
windows. Earlier schemas migrate while preserving Strength/statistics and clamping to current
limits. Each mutation saves before publishing active state. Atomic replacement keeps the previous
commit in `players.yml.bak`. Corrupt snapshots abort startup rather than silently reset accounts.
Filesystem writes can still be lost on power failure; maintain full consistent external backups.

`/smp admin pause` temporarily stops PvP/progression and ends ability effects. Pause resets on
restart; `rules.pvp: false` persists a PvP disable. A runtime storage failure requires disk repair and
restart before resuming.
