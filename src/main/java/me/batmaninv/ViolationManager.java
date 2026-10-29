package me.batmaninv;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ViolationManager {
    private final BatmanInvPlugin plugin;
    private final Set<UUID> muted = new HashSet<>();

    ViolationManager(BatmanInvPlugin plugin) {
        this.plugin = plugin;
    }

    /** @return true si las alertas quedaron activadas */
    boolean toggle(UUID id) {
        if (muted.remove(id)) {
            return true;
        }
        muted.add(id);
        return false;
    }

    void setEnabled(UUID id, boolean enabled) {
        if (enabled) {
            muted.remove(id);
        } else {
            muted.add(id);
        }
    }

    void forget(UUID id) {
        muted.remove(id);
    }

    void flag(Player p, PlayerData d, Check check, Map<String, String> details) {
        Settings s = plugin.settings();
        Settings.CheckCfg cc = s.check(check);
        long now = System.currentTimeMillis();

        if (s.debug) {
            plugin.getLogger().info("[debug] " + p.getName() + " " + check.display() + " " + details);
        }

        Long last = d.lastCounted.get(check);
        if (last != null && now - last < s.countCooldownMs) {
            return;
        }
        d.lastCounted.put(check, now);

        ArrayDeque<Long> q = d.vl.computeIfAbsent(check, k -> new ArrayDeque<>());
        long window = s.decaySeconds * 1000L;
        q.addLast(now);
        while (!q.isEmpty() && now - q.peekFirst() > window) {
            q.pollFirst();
        }
        int vl = q.size();

        // El historial es secundario: si falla, la alerta y la sanción deben seguir funcionando.
        try {
            plugin.storage().recordAlert(p.getUniqueId(), p.getName(), check, now);
        } catch (Exception ex) {
            plugin.getLogger().warning("No se pudo guardar la alerta de " + p.getName() + ": " + ex);
        }

        if (s.alertsEnabled && vl >= cc.alertAfter) {
            try {
                sendAlert(p, check, cc, vl, details);
            } catch (Exception ex) {
                plugin.getLogger().warning("No se pudo enviar la alerta de " + p.getName() + ": " + ex);
            }
        }

        if (s.punishEnabled && vl >= cc.maxViolations && !cc.commands.isEmpty()) {
            q.clear();
            punish(p, check, cc, now);
        }
    }

    private String buildDetails(Map<String, String> details) {
        Messages m = plugin.messages();
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : details.entrySet()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(m.apply(m.raw("alert-format.check-details-line"), Messages.ph(
                    "main", m.raw("alert-format.check-details-color.main"),
                    "secondary", m.raw("alert-format.check-details-color.secondary"),
                    "key", e.getKey(),
                    "value", e.getValue())));
        }
        return sb.toString();
    }

    private void sendAlert(Player p, Check check, Settings.CheckCfg cc, int vl, Map<String, String> details) {
        Messages m = plugin.messages();
        Settings s = plugin.settings();

        String brand = p.getClientBrandName();
        if (brand == null || brand.isEmpty()) {
            brand = m.raw("unknown");
        }
        double tps = Math.min(20.0, Bukkit.getTPS()[0]);

        Map<String, String> ph = Messages.ph(
                "player", p.getName(),
                "check_name", check.display(),
                "violations", String.valueOf(vl),
                "max_violations", String.valueOf(cc.maxViolations),
                "dev", cc.experimental ? m.raw("alert-format.dev-prefix") : "",
                "tps", String.format(Locale.ROOT, "%.1f", tps),
                "ping", String.valueOf(p.getPing()),
                "client_brand", brand,
                "world", p.getWorld().getName(),
                "check_description", m.raw("checks." + check.id() + ".description"),
                "check_details", buildDetails(details),
                "click_hint", s.alertsClickEnabled ? m.raw("alert-format.click-hint") : "");

        Component alert = m.parse(m.apply(m.raw("alert-format.alert-format"), ph));
        if (s.alertsHover) {
            alert = alert.hoverEvent(HoverEvent.showText(
                    m.parse(m.apply(m.raw("alert-format.alert-hover-message"), ph))));
        }
        if (s.alertsClickEnabled) {
            String cmd = m.apply(m.raw("alert-format.alert-click-command"), ph);
            switch (s.alertsClickAction) {
                case "SUGGEST_COMMAND" -> alert = alert.clickEvent(ClickEvent.suggestCommand(cmd));
                case "COPY_TO_CLIPBOARD" -> alert = alert.clickEvent(ClickEvent.copyToClipboard(cmd));
                default -> alert = alert.clickEvent(ClickEvent.runCommand(cmd));
            }
        }

        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("batmaninv.alerts") && !muted.contains(staff.getUniqueId())) {
                try {
                    staff.sendMessage(alert);
                    if (s.soundEnabled) {
                        staff.playSound(staff.getLocation(), s.soundName, s.soundVolume, s.soundPitch);
                    }
                } catch (Exception ex) {
                    plugin.getLogger().warning("Alerta no entregada a " + staff.getName() + ": " + ex);
                }
            }
        }

        if (s.alertsConsole) {
            Component console = m.parse(m.apply(m.raw("alert-format.alert-format-console"), ph));
            plugin.getLogger().info(PlainTextComponentSerializer.plainText().serialize(console));
        }
    }

    private void punish(Player p, Check check, Settings.CheckCfg cc, long now) {
        Settings s = plugin.settings();
        Messages m = plugin.messages();

        plugin.storage().recordPunishment(p.getUniqueId(), p.getName(), check, now);

        for (String cmd : cc.commands) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    cmd.replace("%player%", p.getName()).replace("%check%", check.display()));
        }

        if (s.punishBroadcast) {
            Component msg = m.parse(m.apply(m.raw("punishment-broadcast"),
                    Messages.ph("player", p.getName(), "check_name", check.display())));
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (!s.punishBroadcastStaffOnly || online.hasPermission("batmaninv.alerts")) {
                    online.sendMessage(msg);
                }
            }
        }
    }
}
