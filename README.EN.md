# NTTS Plugin

[Русская версия](README.md)

Java integration between [/N/TTS](https://ntts.fdev.team/) and [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat). The server reads chat messages and plays their generated speech globally or near the sender.

Release jars are built for both **Fabric** and **Forge**. Simple Voice Chat is required on the client and server.

## Configuration

On first start the mod creates `config/ntts.properties`:

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

A token is available through the [/N/TTS Boosty page](https://boosty.to/ntts). Set it in the file or, preferably, through the `NTTS_TOKEN` environment variable. NTTS remains safely disabled when no token is configured. Supported modes:

- `global` — every connected player hears each generated message.
- `local` — generated speech comes from the sender with a 16-block range.

Voice selection modes:

- `static` — use each player's saved selection, falling back to `default_speaker`.
- `random` — assign each player a random available voice until the next reload, falling back to `default_speaker` when the speaker list is unavailable.

Players select a voice with `/set_speaker <speakerID>` and an optional NTTS effect with `/set_effect <effect|none>`. Both commands provide fuzzy-ranked suggestions while typing: speaker matching uses the voice name, speaker ID, and their space/underscore-separated parts. Selections are stored under `config/` in `ntts_speaker_data.json` and `ntts_effect_data.json`. The optional config-level `effect` is the fallback for players without a selection.

`allow_player_effects=false` disables player effect selection and applies only the configured fallback `effect`. Saved player choices are retained for use if the setting is enabled again. NTTS speech is opt-out while the runtime is enabled: every eligible chat message is synthesized, while listener mute and volume remain controlled by Simple Voice Chat.

Operators can use:

- `/ntts status` — safe provider, queue, limit, and usage diagnostics (never the token).
- `/ntts enable` and `/ntts disable` — temporary runtime control.
- `/ntts reload` — reload the file and `NTTS_TOKEN`.
- `/ntts get <key>` — read a safe configured value.
- `/ntts set <key> <value>` — persist and reload a safe configured value. Tokens cannot be read or changed through commands.
- `/ntts reset <key|all>` — restore safe configuration defaults without replacing the token.
- `/ntts test` — queue a fixed test phrase using the executing player's voice.
- `/ntts say <message>` — queue an operator-only spoken announcement (must be run by a player).
- `/ntts player inspect <player>` — show the player's effective voice, effect, override, and lock state.
- `/ntts player voice set <targets> <speaker>` — assign an override to selected online players.
- `/ntts player voice reset <targets>` — clear selected players' overrides and personal voice choices.
- `/ntts player voice lock|unlock <targets>` — control whether selected players can change their voice.
- `/ntts queue clear` — discard pending synthesis requests.

Configuration keys and applicable values use fuzzy-ranked completion in `/ntts get`, `/ntts set`, and `/ntts reset`. Player selectors are supported for administration, bulk operations are capped at 100 targets, actions notify affected players, and changes are audited in the server log. Overrides and locks persist in `config/ntts_player_admin.json`.

Synthesis runs through a bounded asynchronous queue with a memory-bounded audio cache. Token metadata is used to enforce per-request character limits, request pacing, quota, and expiration locally. Provider rate limits are treated as normal backpressure: affected audio is dropped without disabling NTTS.

## Supported Minecraft versions

Supported versions are defined by [`versions/supported.txt`](versions/supported.txt). Each entry has a matching properties file under [`versions/`](versions/), containing its Java, Fabric, Forge, and dependency versions.

Current targets:

- 1.19.2
- 1.20.1
- 1.21.1
- 1.21.4
- 1.21.5
- 1.21.8
- 1.21.11
- 26.1.2
- 26.2

For the complete local matrix, make JDK 21 and JDK 25 (or 26) available. `scripts/build.sh` selects a compatible runtime for each Gradle generation; `JAVA_HOME_21_X64` and `JAVA_HOME_25_X64` can be used when the JDKs are outside standard locations. Both arguments default to `all`:

```bash
scripts/build.sh                         # Fabric + Forge, every version
scripts/build.sh fabric                  # Fabric, every version
scripts/build.sh forge 1.20.1            # Forge 1.20.1
scripts/build.sh 1.21.11                 # Fabric + Forge 1.21.11
scripts/build.sh all all 1.1.0           # release version override
```

The resulting loader-specific jars are written to `dist/`.

### Adding a supported version

1. Add `versions/<minecraft-version>.properties`.
2. Add that version to `versions/supported.txt`.
3. Run `scripts/build.sh` and address any Minecraft API changes.

No branch per Minecraft version is required. Profiles through 1.21.8 use the `doggyman` generation, 1.21.11 uses `ping_9/`, and 26.x uses `unlimited_damage/`. The build script selects the correct generation automatically.

## CI/CD

[`.github/workflows/build-and-release.yml`](.github/workflows/build-and-release.yml) builds the complete version matrix for pushes and pull requests and uploads the jars as workflow artifacts.

Pushing a version tag creates a GitHub Release containing every Fabric and Forge jar:

```bash
git tag v1.1.0
git push origin v1.1.0
```

The tag controls the version embedded in the release jars, so `gradle.properties` does not need a release-only version commit.

## License

This project is licensed under the [MIT License](LICENSE).
