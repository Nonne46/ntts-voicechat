# NTTS для Minecraft

[English version](README.EN.md)

NTTS озвучивает сообщения игрового чата через [/N/TTS](https://ntts.fdev.team/) и воспроизводит их с помощью [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat).

Проект поддерживает Fabric и Forge. NTTS устанавливается на сервер, а Simple Voice Chat должен быть установлен на сервере и у игроков.

## Возможности

- Глобальное или локальное воспроизведение речи.
- Стандартное или случайное начальное назначение голоса.
- Персональные голоса и эффекты для игроков.
- Серверный голос и эффект по умолчанию.
- Команды для управления модом и настройками игроков.

## Поддерживаемые версии

- 1.19.2
- 1.20.1
- 1.21.1
- 1.21.4
- 1.21.5
- 1.21.8
- 1.21.11
- 26.1.2
- 26.2

Для каждой версии доступны отдельные сборки Fabric и Forge на странице [Releases](https://github.com/Nonne46/ntts-voicechat/releases).

## Установка

1. Установите подходящий загрузчик Fabric или Forge.
2. Установите Simple Voice Chat на сервер и клиент.
3. Поместите соответствующий JAR-файл NTTS в серверный каталог `mods/`.
4. Запустите сервер, чтобы создать `config/ntts.properties`.
5. Укажите токен и перезапустите сервер либо выполните `/ntts reload`.

Токен можно получить на [странице /N/TTS в Boosty](https://boosty.to/ntts). Рекомендуется передавать его через переменную окружения `NTTS_TOKEN`, а не хранить в файле конфигурации.

## Настройка

```properties
enabled=true
token=
mode=global
voice_mode=random
default_speaker=narrator_d3
effect=
allow_player_effects=true
max_text_length=300
```

- `mode=global` — речь слышат все игроки.
- `mode=local` — речь слышна рядом с отправителем.
- `voice_mode=static` — игроки без настройки получают голос по умолчанию.
- `voice_mode=random` — игроки без настройки получают случайный голос до перезагрузки NTTS.

Голос, выбранный командой `/set_speaker`, всегда имеет приоритет над начальным назначением.

## Команды

Игроки могут использовать:

```text
/set_speaker <speaker>
/set_effect <effect|none>
```

Основные команды оператора:

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

Полный список и синтаксис доступны через `/ntts help`.

## Сборка

Для полной сборки установите JDK 21 и JDK 25 или 26:

```bash
scripts/build.sh                 # все загрузчики и версии
scripts/build.sh fabric          # все версии Fabric
scripts/build.sh forge 1.20.1    # Forge для Minecraft 1.20.1
```

Готовые файлы появятся в каталоге `dist/`.

## Лицензия

[MIT](LICENSE) © 2026 Nonne
