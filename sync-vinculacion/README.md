# 🔗 Sync-Vinculación v1.0.0 — MC ↔ Discord + promote/demote

- `/stafflinkdiscord <tu-id>` vincula tu MC con tu Discord del staff (nick + MD de confirmación).
- `/promote <nick>` y `/demote <nick>` abren un **menú decorado en juego** con todos los rangos (marca el actual): eliges el destino y se aplica en **LuckPerms + Discord** (rol + nick `{rango} {mc}` + MD), y se publica en **#sincronizacion-discord**.

## Requisitos
- Paper 1.20+ · Java 17+ · **LuckPerms** · bot de Discord con permiso Gestionar roles/nicks (rol del bot arriba de los de staff)

## Compilar
```bash
cd plugins/sync-vinculacion
mvn package
```
→ `target/sync-vinculacion-1.0.0.jar` a `plugins/` + reinicia.

## Config (`config.yml`)
1. `discord.token`, `discord.guild-id`, `nickname-format`
2. `ladder` de menor a mayor con `group` (LuckPerms), `display` y `discord-role-id` (IDs con modo desarrollador)

## © Derechos de autor
© 2026 **Discohikorybrs - Daniel Esteban Vera Fernandez**. Todos los derechos reservados.
