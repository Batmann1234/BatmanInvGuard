package me.batmaninv;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class GuardListener implements Listener {
    /** Un salto horizontal mayor a esto en un solo paquete es un teletransporte, no un paso. */
    private static final double MAX_STEP = 1.5;

    private final BatmanInvPlugin plugin;
    private final Map<UUID, PlayerData> players = new HashMap<>();

    GuardListener(BatmanInvPlugin plugin) {
        this.plugin = plugin;
    }

    private PlayerData data(Player p) {
        return players.computeIfAbsent(p.getUniqueId(), k -> new PlayerData());
    }

    // ------------------------------------------------------------------
    //  Exenciones
    // ------------------------------------------------------------------

    /** TPS "reales" de los últimos ~5 s (el promedio de 1 min tardaba demasiado en recuperarse). */
    private double currentTps() {
        double mspt = Bukkit.getAverageTickTime();
        if (mspt <= 0) {
            return 20.0;
        }
        return Math.min(20.0, 1000.0 / mspt);
    }

    /** @return motivo de la exención, o null si el jugador debe revisarse */
    private String exemptReason(Player p, PlayerData d, long now) {
        Settings s = plugin.settings();
        if (s.ignoredGameModes.contains(p.getGameMode())) {
            return "gamemode";
        }
        if (s.ignoredWorlds.contains(p.getWorld().getName())) {
            return "world";
        }
        if (s.exemptBedrock && p.getUniqueId().getMostSignificantBits() == 0L) {
            return "bedrock";
        }
        if (p.hasPermission("batmaninv.bypass")) {
            return "bypass";
        }
        int ping = p.getPing();
        if (d.lastPing >= 0 && Math.abs(ping - d.lastPing) >= s.exemptPingJump) {
            d.lastJitter = now; // picos de ping = paquetes que llegan juntos
        }
        d.lastPing = ping;
        if (s.exemptPingAbove > 0 && ping > s.exemptPingAbove) {
            return "ping>" + s.exemptPingAbove;
        }
        if (s.exemptJitterMs > 0 && now - d.lastJitter < s.exemptJitterMs) {
            return "ping-jitter";
        }
        if (s.exemptTpsBelow > 0 && currentTps() < s.exemptTpsBelow) {
            return "tps";
        }
        if (s.exemptTeleportMs > 0 && now - d.lastTeleport < s.exemptTeleportMs) {
            return "teleport";
        }
        return null;
    }

    private boolean exempt(Player p, PlayerData d, long now) {
        String reason = exemptReason(p, d, now);
        if (plugin.settings().debug && !Objects.equals(reason, d.lastExemptReason)
                && (reason != null || d.lastExemptReason != null)) {
            plugin.getLogger().info("[debug] " + p.getName() + " exento: "
                    + (reason == null ? "no (se revisa de nuevo)" : reason));
        }
        d.lastExemptReason = reason;
        return reason != null;
    }

    private boolean canCancel(Check check) {
        Settings s = plugin.settings();
        return s.cancelEvents && s.check(check).cancel;
    }

    /**
     * Exige varias sospechas seguidas antes de alertar. Un evento raro aislado
     * (lag, un clic justo al aterrizar...) ya no genera alerta.
     */
    private boolean confirm(PlayerData d, String key, long now, int hits, int windowMs) {
        if (hits <= 1) {
            return true;
        }
        ArrayDeque<Long> q = d.suspicion.computeIfAbsent(key, k -> new ArrayDeque<>());
        q.addLast(now);
        while (!q.isEmpty() && now - q.peekFirst() > windowMs) {
            q.pollFirst();
        }
        return q.size() >= hits;
    }

    private void flag(Player p, PlayerData d, Check check, Map<String, String> details) {
        try {
            plugin.violations().flag(p, d, check, details);
        } catch (Exception ex) {
            // Un fallo (almacenamiento, formato de mensaje...) jamás debe dejar al jugador sin alertas.
            plugin.getLogger().warning("Error al registrar " + check.display() + " de " + p.getName() + ": " + ex);
        }
    }

    // ------------------------------------------------------------------
    //  Entrada / salida / cambios de estado
    // ------------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        players.remove(p.getUniqueId());
        if (!plugin.settings().alertsEnabledOnJoin && p.hasPermission("batmaninv.alerts")) {
            plugin.violations().setEnabled(p.getUniqueId(), false);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        players.remove(e.getPlayer().getUniqueId());
        plugin.violations().forget(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeleport(PlayerTeleportEvent e) {
        PlayerData d = data(e.getPlayer());
        d.resetMovement();
        d.lastTeleport = System.currentTimeMillis();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        PlayerData d = data(e.getPlayer());
        d.resetAll();
        d.lastTeleport = System.currentTimeMillis();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent e) {
        PlayerData d = data(e.getPlayer());
        d.resetAll();
        d.lastTeleport = System.currentTimeMillis();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameMode(PlayerGameModeChangeEvent e) {
        data(e.getPlayer()).resetAll();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent e) {
        PlayerData d = players.get(e.getPlayer().getUniqueId());
        if (d != null) {
            d.resetChain();
            d.shuffle.clear();
        }
    }

    // ------------------------------------------------------------------
    //  Seguimiento de movimiento (para InvMove)
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) {
            return;
        }
        Player p = e.getPlayer();
        PlayerData d = data(p);
        long now = System.currentTimeMillis();

        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double dy = to.getY() - from.getY();
        double dist = Math.sqrt(dx * dx + dz * dz);

        if (dist > MAX_STEP) {
            // teletransporte / empuje enorme: no es un "paso" normal
            d.resetMovement();
            d.lastTeleport = now;
            return;
        }

        // Saltando, cayendo, subiendo escalones: el impulso del salto NO es "moverse con el inventario"
        if (!p.isOnGround() || Math.abs(dy) > 1.0E-4) {
            d.lastAirborne = now;
        }

        Settings s = plugin.settings();
        if (dist >= s.imMinDistance) {
            // En vanilla, con un menú abierto no hay input: la velocidad CAE sola (fricción).
            // Un jugador que mantiene la velocidad constante sí sigue pulsando teclas.
            if (d.lastMoveDist > 0 && dist < d.lastMoveDist * s.imDecelRatio) {
                d.decelStreak++;
            } else {
                d.decelStreak = 0;
            }
            d.moveStreak++;
            d.lastFastMove = now;
            d.lastMoveDist = dist;
        } else {
            d.moveStreak = 0;
            d.decelStreak = 0;
            d.lastMoveDist = 0;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVelocity(PlayerVelocityEvent e) {
        data(e.getPlayer()).lastVelocity = System.currentTimeMillis();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p) {
            data(p).lastDamage = System.currentTimeMillis();
        }
    }

    private boolean isMoving(Player p, PlayerData d, long now) {
        Settings s = plugin.settings();
        if (d.moveStreak < s.imStreak) {
            return false;
        }
        if (now - d.lastFastMove > s.imWindowMs) {
            return false;
        }
        // Acaba de saltar / caer / subir un escalón: tiene impulso aunque no pulse nada
        if (s.imAirGraceMs > 0 && now - d.lastAirborne < s.imAirGraceMs) {
            return false;
        }
        // La velocidad va bajando: está deslizándose por inercia, no caminando
        if (s.imDecelTicks > 0 && d.decelStreak >= s.imDecelTicks) {
            return false;
        }
        if (s.imExemptHitMs > 0
                && (now - d.lastVelocity < s.imExemptHitMs || now - d.lastDamage < s.imExemptHitMs)) {
            return false;
        }
        if (p.isRiptiding()) {
            return false;
        }
        if (s.imExemptVehicle && p.isInsideVehicle()) {
            return false;
        }
        if (s.imExemptGliding && p.isGliding()) {
            return false;
        }
        if (s.imExemptFlying && p.isFlying()) {
            return false;
        }
        if (s.imExemptSwimming && p.isSwimming()) {
            return false;
        }
        if (s.imExemptWater && (p.isInWater() || p.isInLava())) {
            return false;
        }
        if (s.imExemptClimbing && p.isClimbing()) {
            return false;
        }
        if (s.imExemptSneaking && p.isSneaking()) {
            return false;
        }
        if (s.imExemptIce) {
            Material below = p.getLocation().subtract(0, 0.2, 0).getBlock().getType();
            if (below.name().contains("ICE")) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    //  FastHotbar
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent e) {
        Settings s = plugin.settings();
        Player p = e.getPlayer();
        if (!s.check(Check.FAST_HOTBAR).enabled || !s.hbCountSlotChanges) {
            return;
        }
        PlayerData d = data(p);
        long now = System.currentTimeMillis();
        if (exempt(p, d, now)) {
            return;
        }
        // Rueda del ratón = paso al slot contiguo (±1, con vuelta). Tiene umbrales mucho más altos
        // porque una rueda libre puede soltar muchos pasos legítimos de golpe.
        int diff = Math.floorMod(e.getNewSlot() - e.getPreviousSlot(), 9);
        boolean scroll = diff == 1 || diff == 8;
        boolean hit = scroll ? registerScroll(p, d, now) : registerHotbar(p, d, now, "slot-changes");
        if (hit && canCancel(Check.FAST_HOTBAR)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Settings s = plugin.settings();
        Player p = e.getPlayer();
        if (!s.check(Check.FAST_HOTBAR).enabled || !s.hbCountSwapHands) {
            return;
        }
        PlayerData d = data(p);
        long now = System.currentTimeMillis();
        if (exempt(p, d, now)) {
            return;
        }
        if (registerHotbar(p, d, now, "swap-hands") && canCancel(Check.FAST_HOTBAR)) {
            e.setCancelled(true);
        }
    }

    private static int countWithin(ArrayDeque<Long> q, long now, long ms) {
        int n = 0;
        Iterator<Long> it = q.descendingIterator();
        while (it.hasNext()) {
            if (now - it.next() <= ms) {
                n++;
            } else {
                break;
            }
        }
        return n;
    }

    /** Rueda del ratón. @return true si se superó el umbral (y se registró la infracción) */
    private boolean registerScroll(Player p, PlayerData d, long now) {
        Settings s = plugin.settings();
        Messages m = plugin.messages();

        d.scroll.addLast(now);
        while (!d.scroll.isEmpty() && now - d.scroll.peekFirst() > s.hbScrollWindowMs) {
            d.scroll.pollFirst();
        }
        int inWindow = d.scroll.size();
        int inBurst = countWithin(d.scroll, now, s.hbScrollBurstMs);

        boolean over = inWindow > s.hbScrollMax;
        boolean burst = inBurst >= s.hbScrollBurstActions;
        if (!over && !burst) {
            return false;
        }
        if (!confirm(d, "hotbar", now, s.hbConfirmHits, s.hbConfirmWindowMs)) {
            return false;
        }
        flag(p, d, Check.FAST_HOTBAR, Messages.ph(
                m.detail("actions"), String.valueOf(over ? inWindow : inBurst),
                m.detail("window"), (over ? s.hbScrollWindowMs : s.hbScrollBurstMs) + "ms",
                m.detail("limit"), String.valueOf(over ? s.hbScrollMax : s.hbScrollBurstActions),
                m.detail("origin"), m.detail("origin-scroll")));
        return true;
    }

    /** Teclas 1-9 / tecla F. @return true si se superó algún umbral (y se registró la infracción) */
    private boolean registerHotbar(Player p, PlayerData d, long now, String originKey) {
        Settings s = plugin.settings();
        Messages m = plugin.messages();

        d.hotbar.addLast(now);
        while (!d.hotbar.isEmpty() && now - d.hotbar.peekFirst() > s.hbWindowMs) {
            d.hotbar.pollFirst();
        }
        int inWindow = d.hotbar.size();
        int inBurst = countWithin(d.hotbar, now, s.hbBurstMs);

        boolean over = inWindow > s.hbMaxActions;
        boolean burst = inBurst >= s.hbBurstActions;
        if (!over && !burst) {
            return false;
        }
        if (!confirm(d, "hotbar", now, s.hbConfirmHits, s.hbConfirmWindowMs)) {
            return false;
        }
        flag(p, d, Check.FAST_HOTBAR, Messages.ph(
                m.detail("actions"), String.valueOf(over ? inWindow : inBurst),
                m.detail("window"), (over ? s.hbWindowMs : s.hbBurstMs) + "ms",
                m.detail("limit"), String.valueOf(over ? s.hbMaxActions : s.hbBurstActions),
                m.detail("origin"), m.detail("origin-" + originKey)));
        return true;
    }

    /**
     * Reorganización de la hotbar a velocidad inhumana (inventario abierto o no: el servidor solo ve clics).
     * Ordenar la hotbar a mano con 1-2-3-4 en "rodillo" es legítimo (~40-60 ms entre teclas), así que hay
     * dos niveles, ambos con ÍTEMS DISTINTOS y ambos necesitan repetirse (confirm-hits) para alertar:
     *  - Rápida: min-items distintos dentro de window-ms (ritmo imposible de sostener con los dedos).
     *  - Instantánea: instant.min-items distintos dentro de ~1 tick (varios ítems "a la vez").
     */
    private boolean registerRearrange(Player p, PlayerData d, long now, int slotKey) {
        Settings s = plugin.settings();
        Messages m = plugin.messages();

        d.shuffle.addLast(new long[]{now, slotKey});
        long keep = Math.max(s.hbRearrangeWindowMs, s.hbInstantEnabled ? s.hbInstantWindowMs : 0);
        while (!d.shuffle.isEmpty() && now - d.shuffle.peekFirst()[0] > keep) {
            d.shuffle.pollFirst();
        }
        Set<Long> rapidSlots = new HashSet<>();
        Set<Long> instantSlots = new HashSet<>();
        long rapidStart = now;
        long instantStart = now;
        for (long[] a : d.shuffle) {
            long age = now - a[0];
            if (age <= s.hbRearrangeWindowMs) {
                rapidSlots.add(a[1]);
                rapidStart = Math.min(rapidStart, a[0]);
            }
            if (s.hbInstantEnabled && age <= s.hbInstantWindowMs) {
                instantSlots.add(a[1]);
                instantStart = Math.min(instantStart, a[0]);
            }
        }
        boolean rapid = rapidSlots.size() >= s.hbRearrangeMin;
        boolean instant = s.hbInstantEnabled && instantSlots.size() >= s.hbInstantMin;
        if (!rapid && !instant) {
            return false;
        }
        int items = instant ? instantSlots.size() : rapidSlots.size();
        long span = now - (instant ? instantStart : rapidStart);
        String limit = instant
                ? s.hbInstantMin + " / " + s.hbInstantWindowMs + "ms"
                : s.hbRearrangeMin + " / " + s.hbRearrangeWindowMs + "ms";
        d.shuffle.clear(); // este grupo ya se evaluó: el siguiente tiene que ser uno nuevo

        if (!confirm(d, "rearrange", now, s.hbRearrangeConfirmHits, s.hbConfirmWindowMs)) {
            return false;
        }
        flag(p, d, Check.FAST_HOTBAR, Messages.ph(
                m.detail("items"), String.valueOf(items),
                m.detail("window"), span + "ms",
                m.detail("limit"), limit,
                m.detail("origin"), m.detail("origin-rearrange")));
        return true;
    }

    /** @return slot de hotbar afectado (0-8, 9 = mano secundaria) o -1 si el clic no movió ningún ítem */
    private int rearrangeKey(Player p, InventoryClickEvent e, ClickType ct) {
        ItemStack cur = e.getCurrentItem();
        boolean curItem = cur != null && !cur.getType().isAir();
        switch (ct) {
            case NUMBER_KEY: {
                int b = e.getHotbarButton();
                if (b < 0 || b > 8) {
                    return -1;
                }
                ItemStack other = p.getInventory().getItem(b);
                return (curItem || (other != null && !other.getType().isAir())) ? b : -1;
            }
            case SWAP_OFFHAND: {
                ItemStack off = p.getInventory().getItemInOffHand();
                return (curItem || !off.getType().isAir()) ? 9 : -1;
            }
            case LEFT:
            case RIGHT: {
                if (e.getSlotType() != InventoryType.SlotType.QUICKBAR
                        || e.getClickedInventory() != p.getInventory()) {
                    return -1;
                }
                ItemStack cursor = e.getCursor();
                boolean has = curItem || (cursor != null && !cursor.getType().isAir());
                return has ? e.getSlot() : -1;
            }
            default:
                return -1;
        }
    }

    // ------------------------------------------------------------------
    //  Clics de inventario: FastHotbar (números/F/reorganizar) + InvMove + FastMouse
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) {
            return;
        }
        Settings s = plugin.settings();
        PlayerData d = data(p);
        long now = System.currentTimeMillis();
        if (exempt(p, d, now)) {
            return;
        }
        InventoryType top = e.getView().getTopInventory().getType();
        if (s.ignoredInventories.contains(top)) {
            return;
        }

        Messages m = plugin.messages();
        ClickType ct = e.getClick();

        boolean cancelHotbar = false;
        boolean cancelMove = false;
        boolean cancelMouse = false;

        // FastHotbar: tecla de número / tecla F dentro del inventario + reorganización instantánea
        if (s.check(Check.FAST_HOTBAR).enabled) {
            if (s.hbCountNumberKeys && ct == ClickType.NUMBER_KEY) {
                cancelHotbar = registerHotbar(p, d, now, "number-key-clicks");
            } else if (s.hbCountSwapHands && ct == ClickType.SWAP_OFFHAND) {
                cancelHotbar = registerHotbar(p, d, now, "swap-hands");
            }
            if (s.hbRearrangeEnabled) {
                int key = rearrangeKey(p, e, ct);
                if (key >= 0 && registerRearrange(p, d, now, key)) {
                    cancelHotbar = true;
                }
            }
        }

        // InvMove: mover ítems mientras te mueves
        if (s.check(Check.INV_MOVE).enabled
                && !s.imIgnoredClicks.contains(ct)
                && isMoving(p, d, now)
                && (!s.imOnlyHotbar || hotbarRelated(e, ct))
                && confirm(d, "inv-move", now, s.imConfirmHits, s.imConfirmWindowMs)) {
            flag(p, d, Check.INV_MOVE, Messages.ph(
                    m.detail("click"), ct.name(),
                    m.detail("slot"), e.getSlotType().name(),
                    m.detail("speed"), String.format(Locale.ROOT, "%.2f", d.lastMoveDist) + " " + m.detail("unit-speed"),
                    m.detail("ticks"), String.valueOf(d.moveStreak)));
            cancelMove = true;
        }

        // FastMouse: Mouse Tweaks (shift + arrastre)
        if (s.check(Check.FAST_MOUSE).enabled
                && ((s.fmShiftLeft && ct == ClickType.SHIFT_LEFT) || (s.fmShiftRight && ct == ClickType.SHIFT_RIGHT))) {
            cancelMouse = checkShiftChain(p, d, e, ct, now);
        }

        if ((cancelHotbar && canCancel(Check.FAST_HOTBAR))
                || (cancelMove && canCancel(Check.INV_MOVE))
                || (cancelMouse && canCancel(Check.FAST_MOUSE))) {
            e.setCancelled(true);
        }
    }

    private boolean hotbarRelated(InventoryClickEvent e, ClickType ct) {
        return ct == ClickType.NUMBER_KEY
                || ct == ClickType.SWAP_OFFHAND
                || e.getSlotType() == InventoryType.SlotType.QUICKBAR;
    }

    /**
     * Mouse Tweaks (shift + mantener) manda shift-clics en slots DISTINTOS y normalmente CONTIGUOS,
     * a un ritmo casi de 1 por tick. Dos reglas:
     *  - Rápida: cadena larga de slots distintos con intervalo MEDIO muy bajo.
     *  - Arrastre: cadena aún más larga, casi toda entre slots vecinos, a ritmo inhumano sostenido.
     * Shift + doble clic manda, en un mismo instante, clics sobre el MISMO tipo de ítem: se permite.
     */
    private boolean checkShiftChain(Player p, PlayerData d, InventoryClickEvent e, ClickType ct, long now) {
        Settings s = plugin.settings();
        Messages m = plugin.messages();
        ItemStack cur = e.getCurrentItem();
        if (e.getClickedInventory() == null || cur == null || cur.getType().isAir()) {
            return false;
        }
        if (e.getSlotType() == InventoryType.SlotType.RESULT) {
            return false; // resultado de crafteo / comercio: repetir shift-clic ahí es normal
        }
        int slot = e.getRawSlot();

        // Repetir el mismo slot seguido (doble clic normal) no cuenta
        if (slot == d.lastShiftSlot && now - d.lastShiftTime <= s.fmSameSlotMs) {
            return false;
        }

        boolean continues = d.chainCount > 0 && now - d.lastShiftTime <= s.fmMaxGapMs;
        if (!continues) {
            d.resetChain();
            d.chainStart = now;
            d.chainType = cur.getType();
        } else {
            int step = Math.abs(slot - d.lastShiftSlot);
            if (step == 1 || step == 9) {
                d.chainAdj++; // slot vecino (izquierda/derecha o arriba/abajo)
            }
        }
        d.chainCount++;
        d.chainSlots.add(slot);
        if (cur.getType() != d.chainType) {
            d.chainMixed = true;
        }
        d.lastShiftTime = now;
        d.lastShiftSlot = slot;

        int n = d.chainCount;
        if (n < s.fmMinChain && (!s.fmDragEnabled || n < s.fmDragMinChain)) {
            return false;
        }

        long span = now - d.chainStart;
        if (s.fmAllowBurst && !d.chainMixed && span <= s.fmBurstMs) {
            return false; // shift + doble clic
        }

        int steps = n - 1;
        double avgGap = steps > 0 ? (double) span / steps : Double.MAX_VALUE;
        int distinct = d.chainSlots.size();
        double adjRatio = steps > 0 ? (double) d.chainAdj / steps : 0.0;

        boolean rapid = n >= s.fmMinChain && distinct >= s.fmMinChain && avgGap <= s.fmMaxAvgGapMs;
        boolean drag = s.fmDragEnabled && n >= s.fmDragMinChain && distinct >= s.fmDragMinChain
                && adjRatio >= s.fmDragAdjacency && avgGap <= s.fmDragMaxAvgGapMs;
        if (!rapid && !drag) {
            return false;
        }

        flag(p, d, Check.FAST_MOUSE, Messages.ph(
                m.detail("click"), ct.name(),
                m.detail("chain"), n + " (" + distinct + " slots)",
                m.detail("duration"), span + "ms",
                m.detail("avg-gap"), String.format(Locale.ROOT, "%.0fms", avgGap),
                m.detail("adjacent"), String.format(Locale.ROOT, "%.0f%%", adjRatio * 100.0),
                m.detail("mixed"), d.chainMixed ? m.detail("answer-yes") : m.detail("answer-no")));
        return true;
    }
}
