# Nexora HP

A client-side Fabric mod for Minecraft 26.1 that shows your health and can
automatically use a healing item when your HP drops below a configurable
threshold.

Everything is local-player-only and simulates real keyboard/mouse input
(hotbar-slot key presses and a right-click) rather than sending packets or
manipulating inventory/game state directly — it works the same in
singleplayer and multiplayer.

## Features

- `/showhp` — prints your current and max HP to chat.
- Auto-heal: when your HP drops below a configurable percentage, the mod
  switches to a configured hotbar slot and simulates a right-click to use
  the item, then switches back to whatever slot you had selected.
- Configurable cooldown so it won't spam-click the item.
- HUD indicator (position configurable) showing live HP%, heal-ready state,
  and cooldown countdown.
- Optional sound notification when a heal fires.
- In-game settings screen via `/nexora`, or through
  [ModMenu](https://modrinth.com/mod/modmenu) if installed.

## Requirements

- Minecraft 26.1
- Fabric Loader 0.19.0+
- Fabric API
- [ModMenu](https://modrinth.com/mod/modmenu) 18.0.0-beta.1+ and its
  dependency, Text Placeholder API (both optional — only needed for the
  mods-list config screen button; `/nexora` always works without them)

## Building

Minecraft 26.1 ships unobfuscated, so no Yarn/official mappings step is
needed — Loom compiles directly against the game jar.

```
./gradlew build
```

The output jar will be at `build/libs/nexora-hp-<version>.jar`. Copy it into
your `mods` folder.

## Configuration

Settings are stored in `config/nexora-hp.properties` and can be edited
in-game via `/nexora`:

| Setting | Description |
|---|---|
| Auto-Heal | Master on/off toggle |
| Heal Below % | HP percentage that triggers a heal attempt |
| Heal Item Slot | Hotbar slot (1-9) holding the heal item |
| Cooldown | Item's ability cooldown in seconds (mod waits cooldown + 0.5s before trying again) |
| Heal Sound | Toggle the notification sound on heal |
| HUD Position | Which screen corner the indicator is drawn in |

## License

MIT
