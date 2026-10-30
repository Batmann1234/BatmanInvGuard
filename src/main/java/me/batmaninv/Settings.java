package me.batmaninv;

import org.bukkit.GameMode;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class Settings {

    /** Opciones comunes a todos los checks. */
    public static final class CheckCfg {
        public boolean enabled = true;
        public boolean experimental;
        public boolean cancel = true;
        public int maxViolations = 10;
        public int alertAfter = 1;
        public List<String> commands = new ArrayList<>();
    }

    private final java.util.EnumMap<Check, CheckCfg> checks = new java.util.EnumMap<>(Check.class);

    // settings
    public boolean cancelEvents;
    public boolean debug;
    public String dateFormat;
    public Set<GameMode> ignoredGameModes = EnumSet.noneOf(GameMode.class);
    public Set<String> ignoredWorlds = new HashSet<>();
    public Set<InventoryType> ignoredInventories = EnumSet.noneOf(InventoryType.class);
    public boolean exemptBedrock;
    public int exemptPingAbove;
    public double exemptTpsBelow;
    public int exemptPingJump;
    public int exemptJitterMs;
    public int exemptTeleportMs;

    // violations
    public int decaySeconds;
    public int countCooldownMs;

    // alerts
    public boolean alertsEnabled;
    public boolean alertsConsole;
    public boolean alertsEnabledOnJoin;
    public boolean alertsHover;
    public boolean alertsClickEnabled;
    public String alertsClickAction;
    public boolean soundEnabled;
    public String soundName;
    public float soundVolume;
    public float soundPitch;

    // punishments
    public boolean punishEnabled;
    public boolean punishBroadcast;
    public boolean punishBroadcastStaffOnly;

    // storage
    public boolean storageEnabled;
    public int autosaveMinutes;
    public int maxAlertHistory;
    public int purgeDays;

    // profile
    public int profileSuspicious;
    public int profileDangerous;
    public int profileMaxPunishments;

    // FastHotbar
    public int hbWindowMs;
    public int hbMaxActions;
    public int hbBurstMs;
    public int hbBurstActions;
    public boolean hbCountSlotChanges;
    public boolean hbCountNumberKeys;
    public boolean hbCountSwapHands;
    public int hbConfirmHits;
    public int hbConfirmWindowMs;
    public int hbScrollWindowMs;
    public int hbScrollMax;
    public int hbScrollBurstMs;
    public int hbScrollBurstActions;
    public boolean hbRearrangeEnabled;
    public int hbRearrangeWindowMs;
    public int hbRearrangeMin;
    public int hbRearrangeConfirmHits;
    public boolean hbInstantEnabled;
    public int hbInstantWindowMs;
    public int hbInstantMin;

    // InvMove
    public boolean imOnlyHotbar;
    public double imMinDistance;
    public int imStreak;
    public int imWindowMs;
    public int imAirGraceMs;
    public double imDecelRatio;
    public int imDecelTicks;
    public int imConfirmHits;
    public int imConfirmWindowMs;
    public Set<ClickType> imIgnoredClicks = EnumSet.noneOf(ClickType.class);
    public int imExemptHitMs;
    public boolean imExemptIce;
    public boolean imExemptWater;
    public boolean imExemptVehicle;
    public boolean imExemptGliding;
    public boolean imExemptFlying;
    public boolean imExemptSwimming;
    public boolean imExemptSneaking;
    public boolean imExemptClimbing;

    // FastMouse
    public int fmMaxGapMs;
    public int fmMaxAvgGapMs;
    public boolean fmDragEnabled;
    public int fmDragMinChain;
    public double fmDragAdjacency;
    public int fmDragMaxAvgGapMs;
    public int fmMinChain;
    public int fmBurstMs;
    public boolean fmAllowBurst;
    public int fmSameSlotMs;
    public boolean fmShiftLeft;
    public boolean fmShiftRight;

    public CheckCfg check(Check c) {
        return checks.get(c);
    }

    public void load(FileConfiguration c) {
        cancelEvents = c.getBoolean("settings.cancel-events", true);
        debug = c.getBoolean("settings.debug", false);
        dateFormat = c.getString("settings.date-format", "dd/MM/yyyy HH:mm");

        ignoredGameModes = EnumSet.noneOf(GameMode.class);
        for (String n : c.getStringList("settings.ignored-gamemodes")) {
            try {
                ignoredGameModes.add(GameMode.valueOf(n.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // nombre inválido
            }
        }
        ignoredWorlds = new HashSet<>(c.getStringList("settings.ignored-worlds"));
        ignoredInventories = EnumSet.noneOf(InventoryType.class);
        for (String n : c.getStringList("settings.ignored-inventory-types")) {
            try {
                ignoredInventories.add(InventoryType.valueOf(n.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // nombre inválido
            }
        }
        exemptBedrock = c.getBoolean("settings.exempt.bedrock-players", true);
        exemptPingAbove = c.getInt("settings.exempt.ping-above-ms", 600);
        exemptTpsBelow = c.getDouble("settings.exempt.tps-below", 15.0);
        exemptPingJump = c.getInt("settings.exempt.ping-jump-ms", 50);
        exemptJitterMs = c.getInt("settings.exempt.jitter-guard-ms", 1500);
        exemptTeleportMs = c.getInt("settings.exempt.after-teleport-ms", 1500);

        decaySeconds = c.getInt("violations.decay-seconds", 60);
        countCooldownMs = c.getInt("violations.count-cooldown-ms", 400);

        alertsEnabled = c.getBoolean("alerts.enabled", true);
        alertsConsole = c.getBoolean("alerts.console", true);
        alertsEnabledOnJoin = c.getBoolean("alerts.enabled-on-join", true);
        alertsHover = c.getBoolean("alerts.hover", true);
        alertsClickEnabled = c.getBoolean("alerts.click.enabled", true);
        alertsClickAction = c.getString("alerts.click.action", "RUN_COMMAND").toUpperCase(Locale.ROOT);
        soundEnabled = c.getBoolean("alerts.sound.enabled", false);
        soundName = c.getString("alerts.sound.name", "entity.experience_orb.pickup");
        soundVolume = (float) c.getDouble("alerts.sound.volume", 1.0);
        soundPitch = (float) c.getDouble("alerts.sound.pitch", 1.5);

        punishEnabled = c.getBoolean("punishments.enabled", false);
        punishBroadcast = c.getBoolean("punishments.broadcast", true);
        punishBroadcastStaffOnly = c.getBoolean("punishments.broadcast-staff-only", false);

        storageEnabled = c.getBoolean("storage.enabled", true);
        autosaveMinutes = c.getInt("storage.autosave-minutes", 5);
        maxAlertHistory = c.getInt("storage.max-alert-history-per-player", 500);
        purgeDays = c.getInt("storage.purge-after-days", 30);

        profileSuspicious = c.getInt("profile.suspicious-alerts", 5);
        profileDangerous = c.getInt("profile.dangerous-alerts", 20);
        profileMaxPunishments = c.getInt("profile.max-punishments-shown", 3);

        // Opciones comunes por check
        for (Check ch : Check.values()) {
            String base = "checks." + ch.id() + ".";
            CheckCfg cc = new CheckCfg();
            cc.enabled = c.getBoolean(base + "enabled", true);
            cc.experimental = c.getBoolean(base + "experimental", false);
            cc.cancel = c.getBoolean(base + "cancel-events", true);
            cc.maxViolations = c.getInt(base + "max-violations", 10);
            cc.alertAfter = c.getInt(base + "alert-after", 1);
            cc.commands = new ArrayList<>(c.getStringList(base + "commands"));
            checks.put(ch, cc);
        }

        // FastHotbar
        String h = "checks.fast-hotbar.";
        hbWindowMs = c.getInt(h + "window-ms", 1000);
        hbMaxActions = c.getInt(h + "max-actions", 20);
        hbBurstMs = c.getInt(h + "burst-ms", 100);
        hbBurstActions = c.getInt(h + "burst-actions", 6);
        hbConfirmHits = c.getInt(h + "confirm.hits", 2);
        hbConfirmWindowMs = c.getInt(h + "confirm.window-ms", 5000);
        hbScrollWindowMs = c.getInt(h + "scroll.window-ms", 1000);
        hbScrollMax = c.getInt(h + "scroll.max-actions", 40);
        hbScrollBurstMs = c.getInt(h + "scroll.burst-ms", 100);
        hbScrollBurstActions = c.getInt(h + "scroll.burst-actions", 12);
        hbRearrangeEnabled = c.getBoolean(h + "rearrange.enabled", true);
        hbRearrangeWindowMs = c.getInt(h + "rearrange.window-ms", 100);
        hbRearrangeMin = c.getInt(h + "rearrange.min-items", 5);
        hbRearrangeConfirmHits = c.getInt(h + "rearrange.confirm-hits", 2);
        hbInstantEnabled = c.getBoolean(h + "rearrange.instant.enabled", true);
        hbInstantWindowMs = c.getInt(h + "rearrange.instant.window-ms", 20);
        hbInstantMin = c.getInt(h + "rearrange.instant.min-items", 4);
        hbCountSlotChanges = c.getBoolean(h + "count.slot-changes", true);
        hbCountNumberKeys = c.getBoolean(h + "count.number-key-clicks", true);
        hbCountSwapHands = c.getBoolean(h + "count.swap-hands", true);

        // InvMove
        String m = "checks.inv-move.";
        imOnlyHotbar = c.getBoolean(m + "only-hotbar", false);
        imMinDistance = c.getDouble(m + "min-distance", 0.12);
        imStreak = c.getInt(m + "streak-ticks", 5);
        imWindowMs = c.getInt(m + "window-ms", 120);
        imAirGraceMs = c.getInt(m + "air-grace-ms", 400);
        imDecelRatio = c.getDouble(m + "decel-ratio", 0.95);
        imDecelTicks = c.getInt(m + "decel-ticks", 2);
        imConfirmHits = c.getInt(m + "confirm.hits", 2);
        imConfirmWindowMs = c.getInt(m + "confirm.window-ms", 3000);
        imIgnoredClicks = EnumSet.noneOf(ClickType.class);
        for (String n : c.getStringList(m + "ignored-click-types")) {
            try {
                imIgnoredClicks.add(ClickType.valueOf(n.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // nombre inválido
            }
        }
        imExemptHitMs = c.getInt(m + "exempt.after-hit-ms", 1000);
        imExemptIce = c.getBoolean(m + "exempt.ice", true);
        imExemptWater = c.getBoolean(m + "exempt.water", true);
        imExemptVehicle = c.getBoolean(m + "exempt.vehicle", true);
        imExemptGliding = c.getBoolean(m + "exempt.gliding", true);
        imExemptFlying = c.getBoolean(m + "exempt.flying", true);
        imExemptSwimming = c.getBoolean(m + "exempt.swimming", true);
        imExemptSneaking = c.getBoolean(m + "exempt.sneaking", false);
        imExemptClimbing = c.getBoolean(m + "exempt.climbing", true);

        // FastMouse
        String f = "checks.fast-mouse.";
        fmMaxGapMs = c.getInt(f + "max-gap-ms", 110);
        fmMaxAvgGapMs = c.getInt(f + "max-avg-gap-ms", 65);
        fmMinChain = c.getInt(f + "min-chain", 6);
        fmDragEnabled = c.getBoolean(f + "drag-detection.enabled", true);
        fmDragMinChain = c.getInt(f + "drag-detection.min-chain", 8);
        fmDragAdjacency = c.getDouble(f + "drag-detection.min-adjacent-ratio", 0.85);
        fmDragMaxAvgGapMs = c.getInt(f + "drag-detection.max-avg-gap-ms", 110);
        fmBurstMs = c.getInt(f + "double-click-burst-ms", 100);
        fmAllowBurst = c.getBoolean(f + "allow-double-click-burst", true);
        fmSameSlotMs = c.getInt(f + "same-slot-repeat-ms", 400);
        fmShiftLeft = c.getBoolean(f + "count.shift-left", true);
        fmShiftRight = c.getBoolean(f + "count.shift-right", true);
    }
}
