# ⏱️ Staff-Playtime v1.0.0 — modo jugador por tiempo

Los staffs tienen **2h30m** para jugar como normales: se les quitan los grupos de staff y quedan con el rango jugador. Al terminar, todo se restaura solo.

## Comandos (alias `/splaytime`)
| Comando | Para | Qué hace |
|---|---|---|
| `/stplaytime 30m` | staff | Entra en modo jugador 30 min (gasta 1 de 3 usos) |
| `/stplaytime pause` | staff | Pausa (cuenta como salida avisando la pausa) |
| `/stplaytime despause` | staff | Reanuda (cuenta como entrada) |
| `/stplaytime uses reset <nick>` | superior a Manager | Devuelve los 3 usos |
| `/stplaytime time reset <nick>` | superior a Manager | Devuelve las 2h30m |
| `/stplaytime` | staff | Ver tiempo y usos restantes |

Si cierran el MC sin pausar, se **auto-pausa** y avisa en `#salida-turno`.

## Requisitos y compilación
- Paper 1.20+ · Java 17+ · **LuckPerms**
```bash
cd plugins/staff-playtime
mvn package
```
→ `target/staff-playtime-1.0.0.jar` a `plugins/` + reinicia.

## Config (`config.yml`)
`total-minutes`, `max-uses`, `staff-groups` (se quitan), `player-group` (se da), token/guild/IDs de `entrada-turno` y `salida-turno`, mensajes. Permisos: `staffplaytime.use`, `staffplaytime.admin`.

## © Derechos de autor
© 2026 **Discohikorybrs - Daniel Esteban Vera Fernandez**. Todos los derechos reservados.
