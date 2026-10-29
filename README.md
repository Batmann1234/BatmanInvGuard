# BatmanInv

Plugin para Paper/Purpur 1.21.x. Detecta el **comportamiento** típico de mods de inventario:

| Check | Qué detecta |
|-------|-------------|
| **FastHotbar** | Cambios de hotbar demasiado rápidos o instantáneos (scroll, teclas 1-9, tecla F) |
| **InvMove** | Mover ítems del inventario/hotbar mientras el jugador camina o corre |
| **FastMouse** | Mouse Tweaks: shift + clic mantenido arrastrando sobre muchos slots. Shift + doble clic **no** se marca |

> El servidor no sabe qué mod usa el jugador, solo ve paquetes. Por eso se detecta el patrón.

## Archivos
- `config.yml`: umbrales, exenciones, sanciones y opciones de cada check.
- `messages.yml`: alertas, hover, comandos (formato LEGACY o MINIMESSAGE).
- `data.yml`: historial (se genera solo).

## Comandos (`/batmaninv`, alias `/binv`)
| Comando | Permiso |
|---------|---------|
| `/batmaninv alerts [jugador]` | `batmaninv.alerts` (`.others` para otros) |
| `/batmaninv profile <jugador>` | `batmaninv.profile` |
| `/batmaninv stats` | `batmaninv.stats` |
| `/batmaninv clearlogs <jugador>` | `batmaninv.admin` |
| `/batmaninv reload` | `batmaninv.admin` |

`batmaninv.bypass` exime a un jugador.

## Compilar en GitHub
1. Sube esta carpeta a un repositorio (rama `main`).
2. **Actions → Build**: descarga el `.jar` desde **Artifacts**.
3. Un tag `v1.0.0` publica el jar en un Release.

Local: `mvn clean package` (Java 21) → `target/BatmanInv-1.0.0.jar`.

## Ajustar umbrales
Pon `settings.cancel-events: false`, prueba con y sin Mouse Tweaks, revisa las alertas y ajusta. Luego vuelve a `true`.
