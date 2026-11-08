package me.batmaninv;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

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

    /** ¿Se guarda la acción como prueba? Se omiten solo las exenciones permanentes (creativo, mundo, bypass...). */
    private boolean shouldRecord(PlayerData d, boolean exempt) {
        if (!exempt) {
            return true;
        }
        String r = d.lastExemptReason;
        return r != null && !r.equals("gamemode") && !r.equals("world") && !r.equals("bedrock") && !r.equals("bypass");
    }

    /** Datos actuales de un jugador conectado (o null). */
    PlayerData peek(UUID id) {
        return players.get(id);
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
        PlayerData d = data(p);
        long now = System.currentTimeMillis();
        boolean ex = exempt(p, d, now);
        int diff = Math.floorMod(e.getNewSlot() - e.getPreviousSlot(), 9);
        boolean scroll = diff == 1 || diff == 8;
        EvidenceEvent ev = shouldRecord(d, ex)
                ? plugin.evidence().recordHeld(p, d, e.getPreviousSlot(), e.getNewSlot(), scroll, now,
                        ex ? d.lastExemptReason : null)
                : null;
        if (ex || !s.check(Check.FAST_HOTBAR).enabled || !s.hbCountSlotChanges) {
            return;
        }
        // Rueda del ratón = paso al slot contiguo (±1, con vuelta). Tiene umbrales mucho más altos
        // porque una rueda libre puede soltar muchos pasos legítimos de golpe.
        boolean hit = scroll ? registerScroll(p, d, now) : registerHotbar(p, d, now, "slot-changes");
        if (hit && canCancel(Check.FAST_HOTBAR)) {
            e.setCancelled(true);
            if (ev != null) {
                ev.cancelled = true;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Settings s = plugin.settings();
        Player p = e.getPlayer();
        PlayerData d = data(p);
        long now = System.currentTimeMillis();
        boolean ex = exempt(p, d, now);
        EvidenceEvent ev = shouldRecord(d, ex)
                ? plugin.evidence().recordSwap(p, d, now, ex ? d.lastExemptReason : null)
                : null;
        if (ex || !s.check(Check.FAST_HOTBAR).enabled || !s.hbCountSwapHands) {
            return;
        }
        if (registerHotbar(p, d, now, "swap-hands") && canCancel(Check.FAST_HOTBAR)) {
            e.setCancelled(true);
            if (ev != null) {
                ev.cancelled = true;
            }
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
        boolean ex = exempt(p, d, now);
        EvidenceEvent ev = shouldRecord(d, ex)
                ? plugin.evidence().recordClick(p, d, e, now, ex ? d.lastExemptReason : null)
                : null;
        if (ex) {
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
        boolean cancelArmor = false;

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

        // AutoArmor: equipar armadura a velocidad / reacción inhumana
        if (s.check(Check.AUTO_ARMOR).enabled) {
            cancelArmor = checkAutoArmor(p, d, e, ct, now);
        }

        if ((cancelHotbar && canCancel(Check.FAST_HOTBAR))
                || (cancelMove && canCancel(Check.INV_MOVE))
                || (cancelMouse && canCancel(Check.FAST_MOUSE))
                || (cancelArmor && canCancel(Check.AUTO_ARMOR))) {
            e.setCancelled(true);
            if (ev != null) {
                ev.cancelled = true;
            }
        }
    }

    // ------------------------------------------------------------------
    //  AutoArmor
    // ------------------------------------------------------------------

    /** @return slot de armadura del inventario (36 botas, 37 pantalones, 38 peto/elytra, 39 casco) o -1 */
    private static int armorIndex(Material m) {
        if (m == null) {
            return -1;
        }
        String n = m.name();
        if (n.endsWith("_HELMET")) {
            return 39;
        }
        if (n.endsWith("_CHESTPLATE") || m == Material.ELYTRA) {
            return 38;
        }
        if (n.endsWith("_LEGGINGS")) {
            return 37;
        }
        if (n.endsWith("_BOOTS")) {
            return 36;
        }
        return -1;
    }

    private static boolean isEmpty(ItemStack it) {
        return it == null || it.getType().isAir();
    }

    /** Pieza equipada por un clic: el slot de armadura donde queda y el ítem. */
    private record Equip(int slot, ItemStack piece) { }

    /**
     * Solo cuenta clics que de verdad EQUIPAN una pieza en un slot de armadura VACÍO, dentro del inventario
     * propio del jugador (en un cofre, shift+clic manda el ítem al cofre, no a la armadura). Intercambiar una
     * pieza ya puesta, quitarla o moverla entre slots normales no cuenta.
     */
    private Equip armorEquip(Player p, InventoryClickEvent e, ClickType ct) {
        Settings s = plugin.settings();
        if (e.getView().getTopInventory().getType() != InventoryType.CRAFTING
                || e.getClickedInventory() != p.getInventory()) {
            return null;
        }
        PlayerInventory inv = p.getInventory();
        ItemStack cur = e.getCurrentItem();
        boolean armorSlot = e.getSlotType() == InventoryType.SlotType.ARMOR;
        switch (ct) {
            case SHIFT_LEFT:
            case SHIFT_RIGHT: {
                if (!s.aaCountShift || armorSlot || isEmpty(cur)) {
                    return null;
                }
                int idx = armorIndex(cur.getType());
                return idx >= 0 && isEmpty(inv.getItem(idx)) ? new Equip(idx, cur) : null;
            }
            case LEFT:
            case RIGHT: {
                if (!s.aaCountDirect || !armorSlot || !isEmpty(cur)) {
                    return null;
                }
                ItemStack cursor = e.getCursor();
                if (isEmpty(cursor)) {
                    return null;
                }
                int idx = armorIndex(cursor.getType());
                return idx >= 0 && idx == e.getSlot() ? new Equip(idx, cursor) : null;
            }
            case NUMBER_KEY: {
                int b = e.getHotbarButton();
                if (!s.aaCountNumberKey || !armorSlot || !isEmpty(cur) || b < 0 || b > 8) {
                    return null;
                }
                ItemStack hb = inv.getItem(b);
                if (isEmpty(hb)) {
                    return null;
                }
                int idx = armorIndex(hb.getType());
                return idx >= 0 && idx == e.getSlot() ? new Equip(idx, hb) : null;
            }
            case SWAP_OFFHAND: {
                if (!s.aaCountSwapHands || !armorSlot || !isEmpty(cur)) {
                    return null;
                }
                ItemStack off = inv.getItemInOffHand();
                if (isEmpty(off)) {
                    return null;
                }
                int idx = armorIndex(off.getType());
                return idx >= 0 && idx == e.getSlot() ? new Equip(idx, off) : null;
            }
            default:
                return null;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemBreak(PlayerItemBreakEvent e) {
        int idx = armorIndex(e.getBrokenItem().getType());
        if (idx >= 0) {
            data(e.getPlayer()).armorBreak[idx - 36] = System.currentTimeMillis();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArmorPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p)) {
            return;
        }
        ItemStack it = e.getItem().getItemStack();
        if (armorIndex(it.getType()) < 0) {
            return;
        }
        PlayerData d = data(p);
        d.armorPickupTime = System.currentTimeMillis();
        d.armorPickupItem = it.clone();
    }

    /**
     * AutoArmor = el cliente equipa la armadura solo. Dos señales, ambas imposibles para una mano humana,
     * y ambas necesitan repetirse (confirm) antes de alertar:
     *  - Reacción: equipar la pieza justo tras romperse la anterior (o tras recogerla) con un tiempo de
     *    reacción menor al humano. Al tiempo medido se le RESTA el ping: el cliente no puede reaccionar a
     *    algo que todavía no le ha llegado.
     *  - Ráfaga: varias piezas DISTINTAS equipadas en una ventana de pocos ms.
     * Solo se cuentan clics que equipan de verdad en un slot vacío (ver armorEquip).
     * @return true si se registró una infracción
     */
    private boolean checkAutoArmor(Player p, PlayerData d, InventoryClickEvent e, ClickType ct, long now) {
        Settings s = plugin.settings();
        Messages m = plugin.messages();
        Equip eq = armorEquip(p, e, ct);
        if (eq == null) {
            return false;
        }
        int ping = Math.max(0, p.getPing());
        String pieceName = eq.piece().getType().name();

        d.armorEquips.addLast(new long[]{now, eq.slot()});
        while (!d.armorEquips.isEmpty() && now - d.armorEquips.peekFirst()[0] > s.aaBurstWindowMs) {
            d.armorEquips.pollFirst();
        }

        // --- Reacción tras romperse / recogerla ---
        if (s.aaReactionEnabled) {
            String origin = null;
            long react = 0;
            long brokeAt = d.armorBreak[eq.slot() - 36];
            if (s.aaAfterBreak && brokeAt > 0 && now - brokeAt <= s.aaReactionMemoryMs) {
                react = now - brokeAt - ping;
                if (react < s.aaReactionMinMs) {
                    origin = "origin-armor-break";
                    d.armorBreak[eq.slot() - 36] = 0;
                }
            }
            if (origin == null && s.aaAfterPickup && d.armorPickupItem != null
                    && now - d.armorPickupTime <= s.aaReactionMemoryMs
                    && eq.piece().isSimilar(d.armorPickupItem)) {
                react = now - d.armorPickupTime - ping;
                if (react < s.aaReactionMinMs) {
                    origin = "origin-armor-pickup";
                    d.armorPickupItem = null;
                }
            }
            if (origin != null) {
                if (confirm(d, "auto-armor-react", now, s.aaConfirmHits, s.aaConfirmWindowMs)) {
                    flag(p, d, Check.AUTO_ARMOR, Messages.ph(
                            m.detail("origin"), m.detail(origin),
                            m.detail("reaction"), Math.max(0, react) + "ms",
                            m.detail("limit"), "<" + s.aaReactionMinMs + "ms",
                            m.detail("piece"), pieceName));
                    return true;
                }
                return false;
            }
        }

        // --- Ráfaga de piezas distintas ---
        if (!s.aaBurstEnabled) {
            return false;
        }
        Set<Long> slots = new HashSet<>();
        long start = now;
        for (long[] a : d.armorEquips) {
            slots.add(a[1]);
            start = Math.min(start, a[0]);
        }
        if (slots.size() < s.aaBurstMinPieces) {
            return false;
        }
        int pieces = slots.size();
        long span = now - start;
        d.armorEquips.clear(); // este grupo ya se evaluó: el siguiente tiene que ser uno nuevo
        if (!confirm(d, "auto-armor-burst", now, s.aaConfirmHits, s.aaConfirmWindowMs)) {
            return false;
        }
        flag(p, d, Check.AUTO_ARMOR, Messages.ph(
                m.detail("origin"), m.detail("origin-armor-burst"),
                m.detail("pieces"), String.valueOf(pieces),
                m.detail("window"), span + "ms",
                m.detail("limit"), s.aaBurstMinPieces + " / " + s.aaBurstWindowMs + "ms"));
        return true;
    }

    // ------------------------------------------------------------------
    //  ContainerWall
    // ------------------------------------------------------------------

    /** Recuerda el último bloque tocado con clic derecho: solo se juzgan contenedores abiertos POR el jugador. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteractBlock(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) {
            return;
        }
        PlayerData d = data(e.getPlayer());
        d.interactBlock = e.getClickedBlock();
        d.interactTime = System.currentTimeMillis();
    }

    /**
     * Abrir un cofre/barril/shulker/ender chest SIN poder verlo (hay bloques sólidos por medio). El servidor
     * vanilla solo comprueba la distancia, no la línea de visión.
     *
     * Sin falsos positivos:
     *  - Solo se juzgan contenedores de BLOQUE abiertos con un clic derecho del propio jugador sobre ese bloque.
     *    Inventarios de plugins (tumbas de AxGraves, menús, /ec, /invsee...) no tienen bloque asociado
     *    (getLocation() == null) o no vienen de un clic en el bloque, así que se saltan solos.
     *  - Se prueban 15 puntos del bloque (centro, caras y esquinas) desde 7 posiciones del ojo (con
     *    eye-tolerance de margen por lag/agacharse). Solo se alerta si TODOS los rayos están tapados.
     *  - Se ignoran los bloques atravesables (hierba, antorchas, carteles, puertas abiertas...).
     *  - Si el ojo está dentro de un bloque sólido no se juzga.
     *  - Hace falta repetirlo (confirm) antes de alertar.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onContainerOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p)) {
            return;
        }
        Settings s = plugin.settings();
        if (!s.check(Check.CONTAINER_WALL).enabled) {
            return;
        }
        Inventory inv = e.getInventory();
        if (!s.cwTypes.contains(inv.getType())) {
            return;
        }
        Location loc = inv.getLocation();
        if (loc == null || loc.getWorld() == null) {
            return; // inventario de plugin (tumbas de AxGraves, menús...): no es un bloque
        }
        PlayerData d = data(p);
        long now = System.currentTimeMillis();
        Block target = d.interactBlock;
        if (target == null || now - d.interactTime > s.cwOpenDelayMs
                || !target.getWorld().equals(loc.getWorld())) {
            return; // no lo abrió con un clic en el bloque (comando, plugin...)
        }
        // El bloque tocado tiene que ser ESE contenedor (cofre doble: su centro queda a ~1 bloque).
        double dxc = target.getX() + 0.5 - loc.getX();
        double dyc = target.getY() + 0.5 - loc.getY();
        double dzc = target.getZ() + 0.5 - loc.getZ();
        if (dxc * dxc + dyc * dyc + dzc * dzc > 2.25) {
            return;
        }
        if (s.cwIgnoredMaterials.contains(target.getType()) || exempt(p, d, now)) {
            return;
        }

        Block blocker = wallBetween(p, target, s.cwTolerance);
        if (blocker == null) {
            return;
        }
        if (!confirm(d, "container-wall", now, s.cwConfirmHits, s.cwConfirmWindowMs)) {
            return;
        }
        Messages m = plugin.messages();
        Location eye = p.getEyeLocation();
        double cx = Math.max(target.getX(), Math.min(eye.getX(), target.getX() + 1.0));
        double cy = Math.max(target.getY(), Math.min(eye.getY(), target.getY() + 1.0));
        double cz = Math.max(target.getZ(), Math.min(eye.getZ(), target.getZ() + 1.0));
        double dist = Math.sqrt((eye.getX() - cx) * (eye.getX() - cx) + (eye.getY() - cy) * (eye.getY() - cy)
                + (eye.getZ() - cz) * (eye.getZ() - cz));
        flag(p, d, Check.CONTAINER_WALL, Messages.ph(
                m.detail("container"), target.getType().name() + " (" + target.getX() + ", " + target.getY()
                        + ", " + target.getZ() + ")",
                m.detail("distance"), String.format(Locale.ROOT, "%.1f", dist) + " " + m.detail("unit-blocks"),
                m.detail("blocked-by"), blocker.getType().name()));
        if (canCancel(Check.CONTAINER_WALL)) {
            e.setCancelled(true);
        }
    }

    /**
     * @return el bloque que tapa el contenedor, o null si se puede ver desde algún punto (o no se puede juzgar)
     */
    private Block wallBetween(Player p, Block target, double tol) {
        World w = target.getWorld();
        Location eyeLoc = p.getEyeLocation();
        if (!eyeLoc.getBlock().isPassable()) {
            return null; // el ojo está dentro de un bloque sólido: no se juzga
        }
        double[][] offsets = {
                {0, 0, 0}, {tol, 0, 0}, {-tol, 0, 0}, {0, 0, tol}, {0, 0, -tol}, {0, tol, 0}, {0, -tol, 0}
        };
        double[][] samples = {
                {0.5, 0.5, 0.5},
                {0.5, 0.97, 0.5}, {0.5, 0.03, 0.5}, {0.03, 0.5, 0.5}, {0.97, 0.5, 0.5},
                {0.5, 0.5, 0.03}, {0.5, 0.5, 0.97},
                {0.1, 0.1, 0.1}, {0.9, 0.1, 0.1}, {0.1, 0.9, 0.1}, {0.9, 0.9, 0.1},
                {0.1, 0.1, 0.9}, {0.9, 0.1, 0.9}, {0.1, 0.9, 0.9}, {0.9, 0.9, 0.9}
        };
        Block first = null;
        boolean judged = false;
        for (double[] off : offsets) {
            Vector from = eyeLoc.toVector().add(new Vector(off[0], off[1], off[2]));
            if (!w.getBlockAt(from.getBlockX(), from.getBlockY(), from.getBlockZ()).isPassable()) {
                continue; // esta posición de prueba cae dentro de una pared: se descarta
            }
            for (double[] smp : samples) {
                Vector to = new Vector(target.getX() + smp[0], target.getY() + smp[1], target.getZ() + smp[2]);
                Vector dir = to.clone().subtract(from);
                double len = dir.length();
                if (len < 1.0E-4) {
                    return null;
                }
                dir.normalize();
                RayTraceResult r = w.rayTraceBlocks(from.toLocation(w), dir, len, FluidCollisionMode.NEVER, true);
                judged = true;
                if (r == null || r.getHitBlock() == null || r.getHitBlock().equals(target)) {
                    return null; // hay línea de visión
                }
                if (first == null) {
                    first = r.getHitBlock();
                }
            }
        }
        return judged ? first : null;
    }

    /** Ítem "normal": sin nombre personalizado ni lore (tótems, bloques, comida...). */
    private static boolean isPlain(ItemStack it) {
        if (it == null || !it.hasItemMeta()) {
            return true;
        }
        ItemMeta meta = it.getItemMeta();
        return !meta.hasDisplayName() && !meta.hasLore();
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
     * Shift + doble clic manda clics sobre ítems IDÉNTICOS (mismo ítem): se permite siempre.
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
        // Terminó una tanda de ítems IDÉNTICOS (shift + doble clic) y llega otro ítem distinto:
        // se empieza una cadena nueva en vez de mezclar la tanda anterior con este clic.
        if (continues && d.chainRef != null && !d.chainMixed && d.chainCount >= s.fmIdenticalRunMin
                && !cur.isSimilar(d.chainRef)) {
            continues = false;
        }
        if (!continues) {
            d.resetChain();
            d.chainStart = now;
            d.chainRef = cur.clone();
        } else {
            int step = Math.abs(slot - d.lastShiftSlot);
            if (step == 1 || step == 9) {
                d.chainAdj++; // slot vecino (izquierda/derecha o arriba/abajo)
            }
        }
        d.chainCount++;
        d.chainSlots.add(slot);
        int tick = Bukkit.getCurrentTick();
        if (tick != d.chainLastTick) {
            d.chainTicks++;
            d.chainLastTick = tick;
        }
        if (d.chainRef != null && !cur.isSimilar(d.chainRef)) {
            d.chainMixed = true;
        }
        d.lastShiftTime = now;
        d.lastShiftSlot = slot;

        int n = d.chainCount;
        if (n < s.fmMinChain && (!s.fmDragEnabled || n < s.fmDragMinChain)) {
            return false;
        }

        // Cadena de ítems IDÉNTICOS (misma pila repetida).
        if (!d.chainMixed) {
            // Modo permisivo: nunca se marca (es lo que hace shift + doble clic en vanilla).
            if (s.fmAllowBurst) {
                return false;
            }
            // Modo estricto: shift + doble clic llega TODO en el mismo tick del servidor (como mucho 1-2 si la
            // ráfaga se parte), así que una cadena de ítems idénticos solo se juzga si dura varios ticks
            // distintos: llevarse las pilas de 1 en 1 o de 2 en 2 y muy rápido (Mouse Tweaks).
            // Los ítems con nombre/lore (armas custom...) no tienen este trato y se juzgan como cualquier cadena.
            if ((!s.fmIdenticalPlainOnly || isPlain(d.chainRef)) && d.chainTicks < s.fmIdenticalMinTicks) {
                return false;
            }
        }
        long span = now - d.chainStart;

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
                m.detail("chain-ticks"), String.valueOf(d.chainTicks),
                m.detail("avg-gap"), String.format(Locale.ROOT, "%.0fms", avgGap),
                m.detail("adjacent"), String.format(Locale.ROOT, "%.0f%%", adjRatio * 100.0),
                m.detail("mixed"), d.chainMixed ? m.detail("answer-yes") : m.detail("answer-no")));
        return true;
    }
}
