# BatmanInv

Plugin para Paper/Purpur 1.21.x. Detecta el **comportamiento** típico de mods de inventario:

| Check | Qué detecta |
|-------|-------------|
| **FastHotbar** | Cambios de hotbar demasiado rápidos (rueda, teclas 1-9, tecla F) y **varios ítems de la hotbar reorganizados al instante** (inventario abierto o cerrado) |
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
3. Un tag `v1.1.0` publica el jar en un Release.

Local: `mvn clean package` (Java 21) → `target/BatmanInv-1.1.0.jar`.

## Ajustar umbrales
Pon `settings.cancel-events: false`, prueba con y sin Mouse Tweaks, revisa las alertas y ajusta. Luego vuelve a `true`.

## Cambios v1.1.0 (menos falsos positivos)
- **InvMove**: ya no marca el impulso de un salto (`air-grace-ms`), ni la inercia al abrir el inventario
  (`decel-ratio`/`decel-ticks`: en vanilla la velocidad baja sola sin input), ni escaleras/lava/teletransportes.
  Exige 2 clics sospechosos seguidos (`confirm`).
- **FastHotbar**: la rueda del ratón (slot contiguo) tiene umbrales propios y mucho más altos (`scroll`);
  teclas/F con umbrales más realistas y confirmación. Nueva regla `rearrange`: 4+ ítems distintos de la
  hotbar cambian de sitio en 150 ms.
- **FastMouse**: intervalo *medio* en vez de un hueco fijo (robusto a los ticks del servidor), slots
  distintos obligatorios y regla de arrastre por slots vecinos (`drag-detection`).
- **Exenciones**: TPS de los últimos ~5 s (antes promedio de 1 min, que dejaba los checks apagados minutos
  tras un pico de lag), guarda de jitter de ping, y pausa tras teletransporte/respawn/cambio de mundo.
- Un fallo al guardar el historial o enviar una alerta ya no puede cortar las alertas de un jugador.
- Con `settings.debug: true` se registra en consola cuándo y por qué un jugador pasa a estar exento.
