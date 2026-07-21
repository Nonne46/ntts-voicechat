# NTTS for Minecraft

[Русская версия](README.md)

NTTS turns Minecraft chat messages into speech through [/N/TTS](https://ntts.fdev.team/) and plays them using [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat).

The project supports Fabric and Forge. NTTS is installed on the server, while Simple Voice Chat is required on both the server and clients.

## Features

- Global or proximity-based speech playback.
- Fixed or random voice assignment.
- Player-selectable voices and effects.
- Server-wide default voice and effect.
- Operator commands for runtime and player management.

## Supported versions

- 1.19.2
- 1.20.1
- 1.21.1
- 1.21.4
- 1.21.5
- 1.21.8
- 1.21.11
- 26.1.2
- 26.2

Separate Fabric and Forge builds for every version are available under [Releases](https://github.com/Nonne46/ntts-voicechat/releases).

## Installation

1. Install the matching Fabric or Forge loader.
2. Install Simple Voice Chat on the server and client.
3. Place the matching NTTS jar in the server's `mods/` directory.
4. Start the server to create `config/ntts.properties`.
5. Configure the token, then restart the server or run `/ntts reload`.

Tokens are available from the [/N/TTS Boosty page](https://boosty.to/ntts). Prefer the `NTTS_TOKEN` environment variable over storing a token in the configuration file.

## Configuration

```properties
enabled=true
token=
mode=global
voice_mode=static
default_speaker=narrator_d3
effect=
allow_player_effects=true
max_text_length=300
```

- `mode=global` — every player hears generated speech.
- `mode=local` — speech is audible near the sender.
- `voice_mode=static` — use player selections and the default voice.
- `voice_mode=random` — assign voices randomly until NTTS is reloaded.

## Commands

Players can use:

```text
/set_speaker <speaker>
/set_effect <effect|none>
```

Main operator commands:

```text
/ntts help
/ntts status
/ntts enable
/ntts disable
/ntts reload
/ntts get <key>
/ntts set <key> <value>
/ntts reset <key|all>
/ntts test
/ntts say <message>
/ntts player ...
/ntts queue clear
```

Run `/ntts help` for the complete command syntax.

## Building

Install JDK 21 and JDK 25 or 26 for the complete build matrix:

```bash
scripts/build.sh                 # every loader and version
scripts/build.sh fabric          # every Fabric version
scripts/build.sh forge 1.20.1    # Forge for Minecraft 1.20.1
```

Built jars are written to `dist/`.

## License

[MIT](LICENSE) © 2026 Nonne
