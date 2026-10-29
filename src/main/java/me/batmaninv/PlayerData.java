package me.batmaninv;

import org.bukkit.Material;

import java.util.ArrayDeque;
import java.util.EnumMap;

/** Datos temporales por jugador. Solo se usa desde el hilo principal. */
final class PlayerData {
    // FastHotbar
    final ArrayDeque<Long> hotbar = new ArrayDeque<>();

    // InvMove
    long lastFastMove;
    int moveStreak;
    double lastMoveDist;
    long lastVelocity;
    long lastDamage;

    // FastMouse (cadena de shift-clics)
    int chainCount;
    long chainStart;
    long lastShiftTime;
    int lastShiftSlot = -1;
    Material chainType;
    boolean chainMixed;

    // Infracciones
    final EnumMap<Check, ArrayDeque<Long>> vl = new EnumMap<>(Check.class);
    final EnumMap<Check, Long> lastCounted = new EnumMap<>(Check.class);
}
