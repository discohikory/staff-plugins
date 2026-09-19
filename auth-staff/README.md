# 🔐 AuthStaff v1.0.0 — 2FA para staffs no premium

Después del login te da un **mapa con código QR** en el inventario: escanéalo con Google/Microsoft Authenticator y pon los 6 dígitos en el **menú interactivo** (`/auth menu`: clic izq +1, der −1, confirma) o en el chat. Al verificar, te lleva al **Lobby**.

## Requisitos
- Paper/Spigot 1.20+ · Java 17+ · (opcional) AuthMe
- Dar `authstaff.required` a los rangos staff (sin ese permiso no pide 2FA)

## Compilar
```bash
cd plugins/auth-staff
mvn package
```
Sale en `target/auth-staff-1.0.0.jar` → a la carpeta `plugins/` + reinicia.

## Comandos
| Comando | Para | Qué hace |
|---|---|---|
| `/auth <código>` | staff | Verifica los 6 dígitos |
| `/auth menu` | staff | Menú de **cabezas 0-9** (+1/−1, confirmar) |
| `/auth qr` | staff | Re-envía el mapa QR |
| `/auth reset <jugador>` | admin | Genera secreto nuevo |

## Integración
Con **DiscoLogin**: el QR sale **después del /login** (primera vez muestra QR + clave manual para la app). El mapa se re-entrega si lo pierdes y no se puede mover. Cabezas numeradas personalizables en `digit-heads` (Base64 de minecraft-heads.com).

## Config (`config.yml`)
Lobby (mundo/xyz), `teleport-on-success`, `freeze-until-verified`, `qr-slot`, `issuer`, mensajes.

## © Derechos de autor
© 2026 **Discohikorybrs - Daniel Esteban Vera Fernandez**. Todos los derechos reservados.
