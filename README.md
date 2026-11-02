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
- `violations.txt`: **pruebas** de cada infracción y sanción (se genera solo).

## Comandos (`/batmaninv`, alias `/binv`)
| Comando | Permiso |
|---------|---------|
| `/batmaninv alerts [jugador]` | `batmaninv.alerts` (`.others` para otros) |
| `/batmaninv profile <jugador>` | `batmaninv.profile` |
| `/batmaninv stats` | `batmaninv.stats` |
| `/batmaninv clearlogs <jugador>` | `batmaninv.admin` |
| `/batmaninv evidence <jugador>` | `batmaninv.evidence` |
| `/batmaninv reload` | `batmaninv.admin` |

`batmaninv.bypass` exime a un jugador.

## Compilar en GitHub
1. Sube esta carpeta a un repositorio (rama `main`).
2. **Actions → Build**: descarga el `.jar` desde **Artifacts**.
3. Un tag `v1.2.0` publica el jar en un Release.

Local: `mvn clean package` (Java 21) → `target/BatmanInv-1.2.0.jar`.

## Ajustar umbrales
Pon `settings.cancel-events: false`, prueba con y sin Mouse Tweaks, revisa las alertas y ajusta. Luego vuelve a `true`.

## Cambios v1.2.0 (menos falsos positivos)
- **InvMove**: ya no marca el impulso de un salto (`air-grace-ms`), ni la inercia al abrir el inventario
  (`decel-ratio`/`decel-ticks`: en vanilla la velocidad baja sola sin input), ni escaleras/lava/teletransportes.
  Exige 2 clics sospechosos seguidos (`confirm`).
- **FastHotbar**: la rueda del ratón (slot contiguo) tiene umbrales propios y mucho más altos (`scroll`);
  teclas/F con umbrales más realistas y confirmación. Nueva regla `rearrange` (ver abajo).
- **FastMouse**: intervalo *medio* en vez de un hueco fijo (robusto a los ticks del servidor), slots
  distintos obligatorios y regla de arrastre por slots vecinos (`drag-detection`).
- **Exenciones**: TPS de los últimos ~5 s (antes promedio de 1 min, que dejaba los checks apagados minutos
  tras un pico de lag), guarda de jitter de ping, y pausa tras teletransporte/respawn/cambio de mundo.
- Un fallo al guardar el historial o enviar una alerta ya no puede cortar las alertas de un jugador.
- Con `settings.debug: true` se registra en consola cuándo y por qué un jugador pasa a estar exento.

## Ajuste v1.1.1: ordenar la hotbar con 1-2-3-4
Ordenar la hotbar a mano con las teclas numéricas es legítimo (dedos en "rodillo": ~40-60 ms entre teclas), y
la regla `rearrange` original (4 ítems en 150 ms) lo marcaba. Ahora:
- Nivel rápido: 5 ítems distintos en 100 ms. Nivel instantáneo: 4 ítems distintos en 20 ms (~1 tick).
- La sospecha debe repetirse 2 veces en 5 s (`confirm-hits`); un único grupo (p. ej. paquetes juntados por lag) no alerta.

## Cambios v1.2.0

### Ping en la sanción (`%ping%`)
Los comandos de sanción de cada check aceptan estos placeholders:
`%player% %check% %ping% %avg_ping% %max_ping% %vl% %max_violations% %tps% %client_brand% %world% %proof_id% %details%`

```yaml
commands:
  - "ban %player% [BatmanInv] %check% | Ping: %ping%ms (prom. %avg_ping%ms) | Prueba: %proof_id%"
```

### Pruebas en `violations.txt`
Con `evidence.enabled: true` el plugin recuerda las últimas acciones de cada jugador y las escribe (en otro hilo,
sin lag) en `plugins/BatmanInv/violations.txt`:
- **Cada infracción**: bloque con el detalle y las últimas acciones.
- **Cada sanción**: informe completo con un **ID de prueba** (`%proof_id%`): fecha exacta, UUID, cliente, ping
  actual/promedio/máximo, TPS, posición, comandos ejecutados, todas las infracciones, un resumen (clics por
  tipo, intervalo mínimo/medio/máximo entre clics, ráfaga máxima, **ítems agarrados** con cantidades) y la
  **cronología** de cada clic con `+N ms` desde el anterior, el ítem, cómo lo agarró (SHIFT_LEFT, NUMBER_KEY...),
  de/hacia qué inventario, el ping y si se movía.
- `/batmaninv evidence <jugador>` guarda las pruebas a mano (para bans manuales).
- Al pasar `max-file-size-mb` el archivo se archiva como `violations-<fecha>.txt`.

### FastMouse: shift + doble clic ya no se marca
Una cadena de ítems **idénticos** (20 tótems iguales, etc.) se permite siempre, sin importar la velocidad. Antes se
juzgaba por tiempo, pero lo que tarda el servidor en procesar los clics depende de los demás plugins y a veces
superaba el límite. Solo se marcan cadenas con ítems **distintos**.

## Versiones automáticas
Cada compilación en GitHub sube sola el número de versión: `1.1`, `1.2`, `1.3`... (`MAJOR.NÚMERO_DE_EJECUCIÓN`).
- El jar sale como `BatmanInv-1.N.jar` y la versión aparece en `/plugins` y en `violations.txt`.
- Cada push a `main` crea un **Release** `v1.N` con el jar (borra el paso *Release* de `build.yml` si no lo quieres).
- Para saltar a otra versión, pon un tag (ej. `v2.0`) y se usa ese número, o cambia `MAJOR` en `build.yml`.
- En local, `mvn clean package` usa `<revision>` del `pom.xml`; para otra: `mvn clean package -Drevision=1.9`.

### FastMouse en modo estricto (`allow-double-click-burst: false`)
- Shift + doble clic llega **entero en el mismo tick del servidor**, así que una cadena de ítems idénticos sin
  nombre/lore (tótems, bloques, comida...) solo se juzga si abarca `identical.min-ticks` ticks distintos (3).
  Llevarse las pilas de 1 en 1 o de 2 en 2 y muy rápido sí se marca.
- Ítems con nombre/lore (armas custom, espadas...) se juzgan como cualquier cadena (`identical.plain-items-only`).
- `violations.txt` guarda el tick del servidor de cada clic para poder demostrar las ráfagas.
