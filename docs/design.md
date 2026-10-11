# Research and gameplay design

Research checked on 2026-10-11. The supplied [Season Four page](https://strength-smp.fandom.com/wiki/Season_4)
was retrieved through Fandom's public MediaWiki API because the normal page blocked automated access.
The wiki is community documentation; some weapon pages now combine multiple seasons, and some
activation/cooldown values are unspecified. This implementation makes the choices below explicit.

The season overview supports a floor of 1, cap of 5, crafting up to 3, capped-killer item drops,
passives at 2 and ultimates at 5. It supplies the Strength, reroll and cheaper golden-apple recipes.
The [Strength page](https://strength-smp.fandom.com/wiki/Strength) describes the top damage bonus as
vanilla Strength II. We use flat attribute damage: `1.5 × (level − 1)`, giving +6 HP at level 5.
Vanilla potion attributes remain independent. Kills/deaths change levels by one; pair windows
suppress farming transactions in either direction across restarts. Craft provenance remains in
profiles and item tokens so withdrawal cannot turn crafted levels into unrestricted items.

## Weapon behavior

| Specialty and source | Implemented passive | Implemented ultimate |
| --- | --- | --- |
| [Sword](https://strength-smp.fandom.com/wiki/Sword) | Third consecutive accepted hit and later hits use critical-equivalent damage; misses, target changes and 3s gaps reset combo | Five-hit combo plus two equipped swords permits 15s of doubled attack speed; offhand swings accompany accepted hits |
| [Axe](https://strength-smp.fandom.com/wiki/Axe) | Every fifth accepted critical hit stuns for 1s | Double critical-hit damage for 5s; 120s cooldown |
| [Bow](https://strength-smp.fandom.com/wiki/Bow) | Every third accepted arrow hit does 2× damage | Every tenth accepted hit does 3× damage when eligible and ready; 60s cooldown |
| [Crossbow S4](https://strength-smp.fandom.com/wiki/Crossbow_S4) | 33% chance to pull a hit player; every third hit reveals nearby living entities | Every tenth accepted hit does 4× damage when eligible and ready |
| [Trident S4](https://strength-smp.fandom.com/wiki/Trident_S4) | Lightning visual plus configurable bonus damage on hit, once per 2s | 5s forward wave movement and nearby knockback; 60s cooldown |
| [Shield](https://strength-smp.fandom.com/wiki/Shield) | 25% incoming damage reduction for 5s after a shield-disable event | 15s damage immunity, with a dedicated toggle |

The overview says passives unlock at 2, while other pages say 3 or immediately; the overview governs
our default. The live Axe page lists doubled critical damage, whereas older indexed text describes a
charged attack; this implementation follows the live page. Its duration is a 5s balance choice.
Sword/Shield cooldowns of 90/120s, Crossbow's 60s cooldown and 5s public glowing reveal are tunable
balance/implementation choices where the wiki is incomplete. All ultimate cooldowns share one player
key, preventing reroll/specialty swapping from refreshing them. Passive hit counts advance only on
accepted positive player damage. Ranged attacks retain their launch specialty and proc only once.

A vanilla Paper server cannot reproduce a private client mod's dual-wield model or wave rendering.
The Sword ultimate uses native attack-speed modifiers and offhand animations without modifying
items; Trident uses velocity and visual lightning without environmental lightning damage/fire.
Damage passes through the normal PvP/explosion caps. Shield immunity uses damage cancellation,
not a persistent invulnerability flag. Death, logout, pause, reload, demotion below the requirement
and disable release transient ability effects; owned modifiers are cleaned up.

## Season enforcement and management

Independent rule switches handle pearl/totem/enchanted-apple use, enchanted maces,
netherite armor, harmful effects, combat storage restocking, lava placement and TNT minecarts.
Fire Aspect/Flame default to zero enchantment caps. Existing crystal/bed/anchor and combat
elytra/Riptide restrictions compose with these settings. Banned-use items remain available as
recipe ingredients. Armor is unequipped into storage, dropping overflow rather than deleting it.
Social rules such as consent, naked kills, AFK killing, streams and client exploits require staff.

Other configurable controls follow patterns documented by the authors of
[Legendary SMP Utilities](https://modrinth.com/plugin/legendary-smp-utilities) and
[SMPUtils+](https://modrinth.com/plugin/smputils+): item/weapon cooldowns, dimension gates,
enchantment/effect/inventory caps and explosion limits. These defaults are configurable balance
choices, not a universal SMP standard.

Automatic spawn/grace immunity defaults off and clears saved grace when disabled. The earned
Shield ultimate is separately controlled. Invisible killer names are removed from public death
messages, including tracked environmental deaths; progression notices show only the receiving
player's Strength. Admin inspection still identifies accounts.

Implementation uses [Paper basic commands](https://docs.papermc.io/paper/dev/command-api/misc/basic-command/),
[EntityLungeEvent](https://jd.papermc.io/paper/26.2/io/papermc/paper/event/entity/EntityLungeEvent.html)
and [PlayerShieldDisableEvent](https://jd.papermc.io/paper/26.2/io/papermc/paper/event/player/PlayerShieldDisableEvent.html).
[Paper's setup guide](https://docs.papermc.io/paper/getting-started/) documents the Java requirement.
