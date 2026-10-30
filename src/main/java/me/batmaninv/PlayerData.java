package me.batmaninv;

import org.bukkit.Material;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/** Datos temporales por jugador. Solo se usa desde el hilo principal. */
final class PlayerData {
    // Exenciones / estado de red
    int lastPing = -1;
    long lastJitter;
    long lastTeleport;
    String lastExemptReason;

    // FastHotbar
    final ArrayDeque<Long> hotbar = new ArrayDeque<>();      // teclas / F (acciones no contiguas)
    final ArrayDeque<Long> scroll = new ArrayDeque<>();      // rueda del ratón (slot contiguo)
    final ArrayDeque<long[]> shuffle = new ArrayDeque<>();   // {tiempo, slot} ítems de hotbar movidos

    // InvMove
    long lastFastMove;
    int moveStreak;
    int decelStreak;
    double lastMoveDist;
    long lastAirborne;
    long lastVelocity;
    long lastDamage;

    // FastMouse (cadena de shift-clics)
    int chainCount;
    int chainAdj;
    long chainStart;
    long lastShiftTime;
    int lastShiftSlot = -1;
    Material chainType;
    boolean chainMixed;
    final Set<Integer> chainSlots = new HashSet<>();

    // Confirmación de sospechas (evita alertar por un único evento raro)
    final Map<String, ArrayDeque<Long>> suspicion = new HashMap<>();

    // Infracciones
    final EnumMap<Check, ArrayDeque<Long>> vl = new EnumMap<>(Check.class);
    final EnumMap<Check, Long> lastCounted = new EnumMap<>(Check.class);

    /** Olvida el movimiento previo (teletransporte, respawn, cambio de mundo...). */
    void resetMovement() {
        moveStreak = 0;
        decelStreak = 0;
        lastMoveDist = 0;
        lastFastMove = 0;
    }

    void resetChain() {
        chainCount = 0;
        chainAdj = 0;
        chainMixed = false;
        chainType = null;
        lastShiftSlot = -1;
        chainSlots.clear();
    }

    /** Reinicio completo del estado transitorio (no toca las infracciones). */
    void resetAll() {
        resetMovement();
        resetChain();
        hotbar.clear();
        scroll.clear();
        shuffle.clear();
        suspicion.clear();
    }
}
