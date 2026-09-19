# 🔑 DiscoLogin v1.0.0 — registro y login estilo nLogin

`/register`, `/login`, `/changepassword`, sesiones por IP, auto-login premium (Mojang), límite por IP, kick por tiempo, ceguera + freeze. SQLite incluido (sin MySQL).

## Compilar
```bash
cd plugins/disco-login
mvn package
```
→ `target/disco-login-1.0.0.jar` a `plugins/` + reinicia.

## Orden con AuthStaff
Primero `/login`, luego el 2FA (`/auth`). AuthStaff arranca su flujo solo a los 2s.

## © Derechos de autor
© 2026 **Discohikorybrs - Daniel Esteban Vera Fernandez**. Todos los derechos reservados.
