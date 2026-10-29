package me.batmaninv;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class BatmanInvCommand implements CommandExecutor, TabCompleter {
    private static final String GEN = "command-messages.generic-commands.";
    private static final String PROF = "command-messages.profile-command.profile-format.";
    private static final String STATS = "command-messages.stats-command.stats-format.";
    private static final String CLEAR = "command-messages.clear-logs-command.";

    private final BatmanInvPlugin plugin;

    BatmanInvCommand(BatmanInvPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean perm(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        plugin.messages().send(sender, GEN + "no-permission", null);
        return false;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Messages m = plugin.messages();
        if (args.length == 0) {
            m.send(sender, GEN + "help", Messages.ph("label", label));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "help" -> m.send(sender, GEN + "help", Messages.ph("label", label));
            case "reload" -> {
                if (!perm(sender, "batmaninv.admin")) {
                    return true;
                }
                plugin.reloadAll();
                m.send(sender, GEN + "plugin-reloaded", null);
            }
            case "alerts" -> alerts(sender, args);
            case "profile" -> profile(sender, args);
            case "stats" -> stats(sender);
            case "clearlogs" -> clearLogs(sender, args);
            default -> m.send(sender, GEN + "unknown-command", Messages.ph("label", label));
        }
        return true;
    }

    // ---------------------------------------------------------------- alerts

    private void alerts(CommandSender sender, String[] args) {
        Messages m = plugin.messages();
        if (args.length >= 2) {
            if (!sender.hasPermission("batmaninv.alerts.others")) {
                m.send(sender, "no-permission-toggle-other", null);
                return;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                m.send(sender, GEN + "player-not-found", Messages.ph("player", args[1]));
                return;
            }
            boolean on = plugin.violations().toggle(target.getUniqueId());
            m.send(sender, on ? "alerts-enabled-other" : "alerts-disabled-other",
                    Messages.ph("player", target.getName()));
            return;
        }
        if (!(sender instanceof Player p)) {
            m.send(sender, GEN + "only-players", null);
            return;
        }
        if (!perm(sender, "batmaninv.alerts")) {
            return;
        }
        boolean on = plugin.violations().toggle(p.getUniqueId());
        m.send(sender, on ? "alerts-enabled" : "alerts-disabled", null);
    }

    // ---------------------------------------------------------------- profile

    private String date(long millis) {
        String pattern = plugin.settings().dateFormat;
        try {
            return DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochMilli(millis));
        } catch (IllegalArgumentException e) {
            return String.valueOf(millis);
        }
    }

    private void profile(CommandSender sender, String[] args) {
        Messages m = plugin.messages();
        Settings s = plugin.settings();
        if (!perm(sender, "batmaninv.profile")) {
            return;
        }
        if (args.length < 2) {
            m.send(sender, GEN + "specify-player", null);
            return;
        }
        Player online = Bukkit.getPlayerExact(args[1]);
        Storage.PlayerRecord r = online != null
                ? plugin.storage().find(online.getUniqueId())
                : plugin.storage().findByName(args[1]);
        if (online == null && r == null) {
            m.send(sender, GEN + "target-never-joined", Messages.ph("player", args[1]));
            return;
        }
        String name = online != null ? online.getName() : r.name;

        int total = r == null ? 0 : r.totalAlerts();

        // Resumen de alertas
        StringBuilder alerts = new StringBuilder();
        if (r != null) {
            for (Check c : Check.values()) {
                Integer n = r.counts.get(c);
                if (n != null && n > 0) {
                    if (alerts.length() > 0) {
                        alerts.append('\n');
                    }
                    alerts.append(m.apply(m.raw(PROF + "alert-summary"),
                            Messages.ph("check_name", c.display(), "violations", String.valueOf(n))));
                }
            }
        }
        String alertSummary = alerts.length() == 0 ? m.raw(PROF + "no-alerts-found") : alerts.toString();

        // Resumen de sanciones (las más recientes primero)
        StringBuilder puns = new StringBuilder();
        int max = Math.max(1, s.profileMaxPunishments);
        if (r != null && !r.punishments.isEmpty()) {
            int size = r.punishments.size();
            boolean more = size > max;
            if (more) {
                puns.append(m.apply(m.raw(PROF + "showing-last-punishments"),
                        Messages.ph("amount", String.valueOf(max)))).append('\n');
            }
            for (int i = size - 1; i >= Math.max(0, size - max); i--) {
                Storage.Punishment p = r.punishments.get(i);
                puns.append(m.apply(m.raw(PROF + "punishment-summary"),
                        Messages.ph("check_name", p.check().display(), "date", date(p.time())))).append('\n');
            }
            if (more) {
                puns.append(m.raw(PROF + "and-more-to-be-displayed"));
            } else if (puns.length() > 0) {
                puns.setLength(puns.length() - 1);
            }
        }
        String punSummary = puns.length() == 0 ? m.raw(PROF + "no-punishments-found") : puns.toString();

        String status = total >= s.profileDangerous ? m.raw(PROF + "status-dangerous")
                : total >= s.profileSuspicious ? m.raw(PROF + "status-suspicious")
                : m.raw(PROF + "status-clean");

        long last = r == null ? 0 : r.alertTimes.isEmpty() ? 0 : r.alertTimes.get(r.alertTimes.size() - 1);
        String brand = online != null ? online.getClientBrandName() : null;
        if (brand == null || brand.isEmpty()) {
            brand = m.raw("unknown");
        }

        m.sendText(sender, m.apply(m.raw(PROF + "profile-format"), Messages.ph(
                "player", name,
                "client_brand", brand,
                "ping", online != null ? String.valueOf(online.getPing()) : "-",
                "safety_status", status,
                "total_alerts", String.valueOf(total),
                "last_alert", last == 0 ? m.raw(PROF + "never") : date(last),
                "alert_summary", alertSummary,
                "punishment_summary", punSummary)));
    }

    // ---------------------------------------------------------------- stats

    private void stats(CommandSender sender) {
        Messages m = plugin.messages();
        if (!perm(sender, "batmaninv.stats")) {
            return;
        }
        Storage.Stats st = plugin.storage().stats();

        String punSummary = st.totalPunishments() == 0 ? m.raw(STATS + "no-punishments-found")
                : m.apply(m.raw(STATS + "punishment-summary"), Messages.ph(
                "punishment_last_30", String.valueOf(st.p30()),
                "punishment_last_7", String.valueOf(st.p7()),
                "punishment_last_24h", String.valueOf(st.p24())));
        String alertSummary = st.totalAlerts() == 0 ? m.raw(STATS + "no-alerts-found")
                : m.apply(m.raw(STATS + "alert-summary"), Messages.ph(
                "alerts_last_30", String.valueOf(st.a30()),
                "alerts_last_7", String.valueOf(st.a7()),
                "alerts_last_24h", String.valueOf(st.a24())));

        m.sendText(sender, m.apply(m.raw(STATS + "stats-format"), Messages.ph(
                "total_punishments", String.valueOf(st.totalPunishments()),
                "total_alerts", String.valueOf(st.totalAlerts()),
                "punishment_summary", punSummary,
                "alert_summary", alertSummary)));
    }

    // ---------------------------------------------------------------- clearlogs

    private void clearLogs(CommandSender sender, String[] args) {
        Messages m = plugin.messages();
        if (!perm(sender, "batmaninv.admin")) {
            return;
        }
        if (args.length < 2) {
            m.send(sender, GEN + "specify-player", null);
            return;
        }
        Player online = Bukkit.getPlayerExact(args[1]);
        Storage.PlayerRecord r = online != null
                ? plugin.storage().find(online.getUniqueId())
                : plugin.storage().findByName(args[1]);
        if (r == null) {
            m.send(sender, GEN + "target-never-joined", Messages.ph("player", args[1]));
            return;
        }
        m.send(sender, CLEAR + "clearing-logs", null);
        long start = System.currentTimeMillis();
        String name = r.name;
        int amount = plugin.storage().clear(r.id);
        m.send(sender, CLEAR + "logs-cleared", Messages.ph(
                "amount", String.valueOf(amount),
                "player", name,
                "duration", String.valueOf(System.currentTimeMillis() - start)));
    }

    // ---------------------------------------------------------------- tab

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String opt : List.of("help", "reload", "alerts", "profile", "stats", "clearlogs")) {
                if (opt.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(opt);
                }
            }
        } else if (args.length == 2 && List.of("alerts", "profile", "clearlogs").contains(args[0].toLowerCase(Locale.ROOT))) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(p.getName());
                }
            }
        }
        return out;
    }
}
