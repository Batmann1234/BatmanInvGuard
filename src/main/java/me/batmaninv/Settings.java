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

    // evidence (violations.txt)
    public boolean evEnabled;
    public boolean evEachViolation;
    public boolean evOnPunishment;
    public int evBufferMax;
    public int evKeepSeconds;
    public int evReportMaxEvents;
    public int evViolationContext;
    public boolean evHotbar;
    public boolean evItemNames;
    public int evPingWindowSeconds;
    public int evMaxFileMb;

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
    public int fmIdenticalRunMin;
    public boolean fmAllowBurst;
    public boolean fmIdenticalPlainOnly;
    public int fmIdenticalMinTicks;
    public int fmSameSlotMs;
    public boolean fmShiftLeft;
    public boolean fmShiftRight;

    // AutoArmor
    public boolean aaBurstEnabled;
    public int aaBurstMinPieces;
    public int aaBurstWindowMs;
    public boolean aaReactionEnabled;
    public int aaReactionMinMs;
    public int aaReactionMemoryMs;
    public boolean aaAfterBreak;
    public boolean aaAfterPickup;
    public int aaConfirmHits;
    public int aaConfirmWindowMs;
    public boolean aaCountShift;
    public boolean aaCountDirect;
    public boolean aaCountNumberKey;
    public boolean aaCountSwapHands;

    // ContainerWall
    public Set<InventoryType> cwTypes = EnumSet.noneOf(InventoryType.class);
    public Set<org.bukkit.Material> cwIgnoredMaterials = EnumSet.noneOf(org.bukkit.Material.class);
    public double cwTolerance;
    public int cwOpenDelayMs;
    public int cwConfirmHits;
    public int cwConfirmWindowMs;

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

        evEnabled = c.getBoolean("evidence.enabled", true);
        evEachViolation = c.getBoolean("evidence.log-each-violation", true);
        evOnPunishment = c.getBoolean("evidence.log-on-punishment", true);
        evBufferMax = Math.max(10, c.getInt("evidence.buffer.max-events", 400));
        evKeepSeconds = Math.max(5, c.getInt("evidence.buffer.keep-seconds", 120));
        evReportMaxEvents = c.getInt("evidence.report.max-events", 300);
        evViolationContext = c.getInt("evidence.report.violation-context", 20);
        evHotbar = c.getBoolean("evidence.record.hotbar-events", true);
        evItemNames = c.getBoolean("evidence.record.item-names", true);
        evPingWindowSeconds = Math.max(1, c.getInt("evidence.ping-window-seconds", 15));
        evMaxFileMb = c.getInt("evidence.max-file-size-mb", 20);

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
        fmIdenticalRunMin = c.getInt(f + "identical-run-min", 3);
        fmAllowBurst = c.getBoolean(f + "allow-double-click-burst", true);
        fmIdenticalPlainOnly = c.getBoolean(f + "identical.plain-items-only", true);
        fmIdenticalMinTicks = Math.max(1, c.getInt(f + "identical.min-ticks", 3));
        fmSameSlotMs = c.getInt(f + "same-slot-repeat-ms", 400);
        fmShiftLeft = c.getBoolean(f + "count.shift-left", true);
        fmShiftRight = c.getBoolean(f + "count.shift-right", true);

        // AutoArmor
        String a = "checks.auto-armor.";
        aaBurstEnabled = c.getBoolean(a + "burst.enabled", true);
        aaBurstMinPieces = Math.max(2, c.getInt(a + "burst.min-pieces", 3));
        aaBurstWindowMs = c.getInt(a + "burst.window-ms", 150);
        aaReactionEnabled = c.getBoolean(a + "reaction.enabled", true);
        aaReactionMinMs = c.getInt(a + "reaction.min-ms", 100);
        aaReactionMemoryMs = c.getInt(a + "reaction.memory-ms", 2000);
        aaAfterBreak = c.getBoolean(a + "reaction.after-break", true);
        aaAfterPickup = c.getBoolean(a + "reaction.after-pickup", true);
        aaConfirmHits = c.getInt(a + "confirm.hits", 2);
        aaConfirmWindowMs = c.getInt(a + "confirm.window-ms", 60000);
        aaCountShift = c.getBoolean(a + "count.shift-click", true);
        aaCountDirect = c.getBoolean(a + "count.direct-click", true);
        aaCountNumberKey = c.getBoolean(a + "count.number-key", true);
        aaCountSwapHands = c.getBoolean(a + "count.swap-hands", true);

        // ContainerWall
        String w = "checks.container-wall.";
        cwTypes = EnumSet.noneOf(InventoryType.class);
        for (String n : c.getStringList(w + "inventory-types")) {
            try {
                cwTypes.add(InventoryType.valueOf(n.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // nombre inválido
            }
        }
        if (!c.isList(w + "inventory-types")) {
            cwTypes.add(InventoryType.CHEST);
            cwTypes.add(InventoryType.BARREL);
            cwTypes.add(InventoryType.SHULKER_BOX);
            cwTypes.add(InventoryType.ENDER_CHEST);
        }
        cwIgnoredMaterials = EnumSet.noneOf(org.bukkit.Material.class);
        for (String n : c.getStringList(w + "ignored-materials")) {
            org.bukkit.Material mat = org.bukkit.Material.matchMaterial(n.trim());
            if (mat != null) {
                cwIgnoredMaterials.add(mat);
            }
        }
        cwTolerance = Math.max(0.0, c.getDouble(w + "eye-tolerance", 0.35));
        cwOpenDelayMs = c.getInt(w + "open-delay-ms", 250);
        cwConfirmHits = c.getInt(w + "confirm.hits", 2);
        cwConfirmWindowMs = c.getInt(w + "confirm.window-ms", 30000);
    }
}
