package me.batmaninv;

/** Una acción del jugador guardada como prueba (clic, cambio de hotbar, tecla F o infracción). */
final class EvidenceEvent {
    enum Kind { CLICK, HELD, SWAP, VIOLATION }

    final long time;      // System.currentTimeMillis() del servidor al recibir la acción
    final Kind kind;
    final int ping;       // ping del jugador (ms) en ese momento
    final String text;    // descripción ya formateada
    final String click;   // tipo de clic (CLICK) o nombre del check (VIOLATION)
    final String item;    // material del ítem agarrado (solo CLICK), para el resumen
    final int amount;     // cantidad del ítem agarrado

    String note = "";     // p. ej. " [EXENTO: tps]"
    boolean cancelled;    // BatmanInv canceló la acción

    EvidenceEvent(long time, Kind kind, int ping, String text, String click, String item, int amount) {
        this.time = time;
        this.kind = kind;
        this.ping = ping;
        this.text = text;
        this.click = click;
        this.item = item;
        this.amount = amount;
    }
}
