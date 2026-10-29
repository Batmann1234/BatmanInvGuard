package me.batmaninv;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Historial de alertas y sanciones (data.yml). Los métodos son seguros entre hilos. */
public final class Storage {

    public record Punishment(long time, Check check) {}

    public static final class PlayerRecord {
        public final UUID id;
        public String name;
        public final EnumMap<Check, Integer> counts = new EnumMap<>(Check.class);
        public final ArrayList<Long> alertTimes = new ArrayList<>();
        public final ArrayList<Punishment> punishments = new ArrayList<>();

        PlayerRecord(UUID id, String name) {
            this.id = id;
            this.name = name;
        }

        public int totalAlerts() {
            int t = 0;
            for (int v : counts.values()) {
                t += v;
            }
            return t;
        }

        long lastActivity() {
            long last = 0;
            for (long t : alertTimes) {
                last = Math.max(last, t);
            }
            for (Punishment p : punishments) {
                last = Math.max(last, p.time());
            }
            return last;
        }
    }

    public record Stats(int totalPunishments, int totalAlerts,
                        int p30, int p7, int p24, int a30, int a7, int a24) {}

    private final BatmanInvPlugin plugin;
    private final Map<UUID, PlayerRecord> records = new HashMap<>();
    private final File file;

    Storage(BatmanInvPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    synchronized void load() {
        records.clear();
        if (!plugin.settings().storageEnabled || !file.exists()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection players = y.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long purgeMs = plugin.settings().purgeDays * 86_400_000L;
        for (String key : players.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                continue;
            }
            ConfigurationSection sec = players.getConfigurationSection(key);
            if (sec == null) {
                continue;
            }
            PlayerRecord r = new PlayerRecord(id, sec.getString("name", key));
            ConfigurationSection counts = sec.getConfigurationSection("counts");
            if (counts != null) {
                for (String ck : counts.getKeys(false)) {
                    Check c = Check.byId(ck);
                    if (c != null) {
                        r.counts.put(c, counts.getInt(ck));
                    }
                }
            }
            for (long t : sec.getLongList("alert-times")) {
                r.alertTimes.add(t);
            }
            for (String s : sec.getStringList("punishments")) {
                String[] parts = s.split(";");
                if (parts.length == 2) {
                    Check c = Check.byId(parts[1]);
                    try {
                        if (c != null) {
                            r.punishments.add(new Punishment(Long.parseLong(parts[0]), c));
                        }
                    } catch (NumberFormatException ignored) {
                        // línea corrupta
                    }
                }
            }
            if (purgeMs > 0 && r.lastActivity() > 0 && now - r.lastActivity() > purgeMs) {
                continue;
            }
            records.put(id, r);
        }
    }

    void save() {
        if (!plugin.settings().storageEnabled) {
            return;
        }
        YamlConfiguration y = new YamlConfiguration();
        synchronized (this) {
            for (PlayerRecord r : records.values()) {
                if (r.counts.isEmpty() && r.punishments.isEmpty()) {
                    continue;
                }
                String base = "players." + r.id;
                y.set(base + ".name", r.name);
                for (Map.Entry<Check, Integer> e : r.counts.entrySet()) {
                    y.set(base + ".counts." + e.getKey().id(), e.getValue());
                }
                y.set(base + ".alert-times", new ArrayList<>(r.alertTimes));
                List<String> pun = new ArrayList<>();
                for (Punishment p : r.punishments) {
                    pun.add(p.time() + ";" + p.check().id());
                }
                y.set(base + ".punishments", pun);
            }
        }
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("No se pudo guardar data.yml: " + e.getMessage());
        }
    }

    private PlayerRecord get(UUID id, String name) {
        PlayerRecord r = records.computeIfAbsent(id, k -> new PlayerRecord(id, name));
        r.name = name;
        return r;
    }

    synchronized void recordAlert(UUID id, String name, Check check, long now) {
        PlayerRecord r = get(id, name);
        r.counts.merge(check, 1, Integer::sum);
        r.alertTimes.add(now);
        int max = plugin.settings().maxAlertHistory;
        while (max > 0 && r.alertTimes.size() > max) {
            r.alertTimes.remove(0);
        }
    }

    synchronized void recordPunishment(UUID id, String name, Check check, long now) {
        get(id, name).punishments.add(new Punishment(now, check));
    }

    public synchronized PlayerRecord find(UUID id) {
        return records.get(id);
    }

    public synchronized PlayerRecord findByName(String name) {
        for (PlayerRecord r : records.values()) {
            if (r.name.equalsIgnoreCase(name)) {
                return r;
            }
        }
        return null;
    }

    /** Borra el historial del jugador y devuelve cuántos registros (alertas + sanciones) se borraron. */
    public synchronized int clear(UUID id) {
        PlayerRecord r = records.remove(id);
        return r == null ? 0 : r.totalAlerts() + r.punishments.size();
    }

    public synchronized Stats stats() {
        long now = System.currentTimeMillis();
        long d30 = now - 30L * 86_400_000L;
        long d7 = now - 7L * 86_400_000L;
        long d1 = now - 86_400_000L;
        int tp = 0, ta = 0, p30 = 0, p7 = 0, p24 = 0, a30 = 0, a7 = 0, a24 = 0;
        for (PlayerRecord r : records.values()) {
            ta += r.totalAlerts();
            tp += r.punishments.size();
            for (long t : r.alertTimes) {
                if (t >= d30) a30++;
                if (t >= d7) a7++;
                if (t >= d1) a24++;
            }
            for (Punishment p : r.punishments) {
                if (p.time() >= d30) p30++;
                if (p.time() >= d7) p7++;
                if (p.time() >= d1) p24++;
            }
        }
        return new Stats(tp, ta, p30, p7, p24, a30, a7, a24);
    }
}
