package me.batmaninv;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class GuardListener implements Listener {
    private final BatmanInvPlugin plugin;
    private final Map<UUID, PlayerData> players = new HashMap<>();

    GuardListener(BatmanInvPlugin plugin) {
        this.plugin = plugin;
    }

    private PlayerData data(Player p) {
        return players.computeIfAbsent(p.getUniqueId(), k -> new PlayerData());
    }

    private boolean exempt(Player p) {
        Settings s = plugin.settings();
        if (s.ignoredGameModes.contains(p.getGameMode())) {
            return true;
        }
        if (s.ignoredWorlds.contains(p.getWorld().getName())) {
            return true;
        }
        if (s.exemptBedrock && p.getUniqueId().getMostSignificantBits() == 0L) {
            return true;
        }
        if (s.exemptPingAbove > 0 && p.getPing() > s.exemptPingAbove) {
            return true;
        }
        if (s.exemptTpsBelow > 0 && Bukkit.getTPS()[0] < s.exemptTpsBelow) {
            return true;
        }
        return p.hasPermission("batmaninv.bypass");
    }

    private boolean canCancel(Check check) {
        Settings s = plugin.settings();
        return s.cancelEvents && s.check(check).cancel;
    }

    // ------------------------------------------------------------------
    //  Entrada / salida
    // ------------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!plugin.settings().alertsEnabledOnJoin && p.hasPermission("batmaninv.alerts")) {
            plugin.violations().setEnabled(p.getUniqueId(), false);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        players.remove(e.getPlayer().getUniqueId());
        plugin.violations().forget(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------
    //  Seguimiento de movimiento (para InvMove)
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (e.getTo() == null) {
            return;
        }
        double dx = e.getTo().getX() - e.getFrom().getX();
        double dz = e.getTo().getZ() - e.getFrom().getZ();
        double dist2 = dx * dx + dz * dz;
        double min = plugin.settings().imMinDistance;

        PlayerData d = data(e.getPlayer());
        if (dist2 >= min * min) {
            d.moveStreak++;
            d.lastFastMove = System.currentTimeMillis();
            d.lastMoveDist = Math.sqrt(dist2);
        } else {
            d.moveStreak = 0;
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
        if (s.imExemptWater && p.isInWater()) {
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
        if (!s.check(Check.FAST_HOTBAR).enabled || !s.hbCountSlotChanges || exempt(p)) {
            return;
        }
        if (registerHotbar(p, data(p), System.currentTimeMillis(), "slot-changes") && canCancel(Check.FAST_HOTBAR)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Settings s = plugin.settings();
        Player p = e.getPlayer();
        if (!s.check(Check.FAST_HOTBAR).enabled || !s.hbCountSwapHands || exempt(p)) {
            return;
        }
        if (registerHotbar(p, data(p), System.currentTimeMillis(), "swap-hands") && canCancel(Check.FAST_HOTBAR)) {
            e.setCancelled(true);
        }
    }

    /** @return true si se superó algún umbral (y se registró la infracción) */
    private boolean registerHotbar(Player p, PlayerData d, long now, String originKey) {
        Settings s = plugin.settings();
        Messages m = plugin.messages();

        d.hotbar.addLast(now);
        while (!d.hotbar.isEmpty() && now - d.hotbar.peekFirst() > s.hbWindowMs) {
            d.hotbar.pollFirst();
        }
        int inWindow = d.hotbar.size();

        int inBurst = 0;
        Iterator<Long> it = d.hotbar.descendingIterator();
        while (it.hasNext()) {
            if (now - it.next() <= s.hbBurstMs) {
                inBurst++;
            } else {
                break;
            }
        }

        String origin = m.detail("origin-" + originKey);
        if (inWindow > s.hbMaxActions) {
            plugin.violations().flag(p, d, Check.FAST_HOTBAR, Messages.ph(
                    m.detail("actions"), String.valueOf(inWindow),
                    m.detail("window"), s.hbWindowMs + "ms",
                    m.detail("limit"), String.valueOf(s.hbMaxActions),
                    m.detail("origin"), origin));
            return true;
        }
        if (inBurst >= s.hbBurstActions) {
            plugin.violations().flag(p, d, Check.FAST_HOTBAR, Messages.ph(
                    m.detail("actions"), String.valueOf(inBurst),
                    m.detail("window"), s.hbBurstMs + "ms",
                    m.detail("limit"), String.valueOf(s.hbBurstActions),
                    m.detail("origin"), origin));
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    //  Clics de inventario: FastHotbar (números/F) + InvMove + FastMouse
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) {
            return;
        }
        Settings s = plugin.settings();
        if (exempt(p)) {
            return;
        }
        InventoryType top = e.getView().getTopInventory().getType();
        if (s.ignoredInventories.contains(top)) {
            return;
        }

        PlayerData d = data(p);
        Messages m = plugin.messages();
        long now = System.currentTimeMillis();
        ClickType ct = e.getClick();

        boolean cancelHotbar = false;
        boolean cancelMove = false;
        boolean cancelMouse = false;

        // FastHotbar: tecla de número / tecla F dentro del inventario
        if (s.check(Check.FAST_HOTBAR).enabled) {
            if (s.hbCountNumberKeys && ct == ClickType.NUMBER_KEY) {
                cancelHotbar = registerHotbar(p, d, now, "number-key-clicks");
            } else if (s.hbCountSwapHands && ct == ClickType.SWAP_OFFHAND) {
                cancelHotbar = registerHotbar(p, d, now, "swap-hands");
            }
        }

        // InvMove: mover ítems mientras te mueves
        if (s.check(Check.INV_MOVE).enabled
                && !s.imIgnoredClicks.contains(ct)
                && isMoving(p, d, now)
                && (!s.imOnlyHotbar || hotbarRelated(e, ct))) {
            plugin.violations().flag(p, d, Check.INV_MOVE, Messages.ph(
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
     * Mouse Tweaks (shift + mantener) manda shift-clics en slots DISTINTOS con muy poco tiempo entre ellos.
     * Shift + doble clic manda, en un mismo instante, clics sobre slots con el MISMO tipo de ítem: se permite.
     */
    private boolean checkShiftChain(Player p, PlayerData d, InventoryClickEvent e, ClickType ct, long now) {
        Settings s = plugin.settings();
        Messages m = plugin.messages();
        ItemStack cur = e.getCurrentItem();
        if (e.getClickedInventory() == null || cur == null || cur.getType().isAir()) {
            return false;
        }
        int slot = e.getRawSlot();

        // Repetir el mismo slot seguido (doble clic normal) no cuenta
        if (slot == d.lastShiftSlot && now - d.lastShiftTime <= s.fmSameSlotMs) {
            return false;
        }

        boolean continues = d.chainCount > 0 && now - d.lastShiftTime <= s.fmMaxGapMs;
        if (!continues) {
            d.chainCount = 0;
            d.chainStart = now;
            d.chainType = cur.getType();
            d.chainMixed = false;
        }
        d.chainCount++;
        if (cur.getType() != d.chainType) {
            d.chainMixed = true;
        }
        d.lastShiftTime = now;
        d.lastShiftSlot = slot;

        if (d.chainCount < s.fmMinChain) {
            return false;
        }

        long span = now - d.chainStart;
        if (s.fmAllowBurst && !d.chainMixed && span <= s.fmBurstMs) {
            return false; // shift + doble clic
        }

        plugin.violations().flag(p, d, Check.FAST_MOUSE, Messages.ph(
                m.detail("click"), ct.name(),
                m.detail("chain"), String.valueOf(d.chainCount),
                m.detail("duration"), span + "ms",
                m.detail("mixed"), d.chainMixed ? m.detail("answer-yes") : m.detail("answer-no")));
        return true;
    }
}
