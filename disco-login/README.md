# 🔑 DiscoLogin v1.0.0 — registro y login estilo nLogin

`/register`, `/login`, `/changepassword`, sesiones por IP, auto-login premium (Mojang), límite por IP, kick por tiempo, ceguera + freeze. SQLite incluido (sin MySQL).

## Compilar
```bash
cd plugins/disco-login
mvn package
```
→ `target/disco-login-1.0.0.jar` a `plugins/` + reinicia.

## Flujo de primer ingreso
Pregunta clicable: **¿Tienes MC comprado?** → premium se verifica y entra directo, no premium va a `/register`. El login vale en todos los servidores (canal `discologin:main`, instala el plugin en cada uno).

## Orden con AuthStaff
Primero `/login`, luego el 2FA (`/auth`) arranca solo. `/premium` solo acepta staff con **MC comprado** (verificado con Mojang).

## © Derechos de autor
© 2026 **Discohikorybrs - Daniel Esteban Vera Fernandez**. Todos los derechos reservados.
