package me.batmaninv;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Pruebas para sanciones: guarda las últimas acciones de cada jugador y escribe violations.txt
 * (clics, ítems, milisegundos entre acciones, ping...). La escritura se hace en un hilo aparte.
 */
public final class Evidence {

    public record PingStats(int current, int avg, int max) {}

    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZONE);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZONE);
    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZONE);
    private static final String LINE = "=".repeat(100);
    private static final String SUB = "-".repeat(100);

    private final BatmanInvPlugin plugin;
    private final Path file;
    private ExecutorService writer;

    Evidence(BatmanInvPlugin plugin) {
        this.plugin = plugin;
        this.file = plugin.getDataFolder().toPath().resolve("violations.txt");
    }

    void start() {
        writer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "BatmanInv-Evidence");
            t.setDaemon(true);
            return t;
        });
    }

    /** Espera a que se termine de escribir lo pendiente. */
    void stop() {
        if (writer == null) {
            return;
        }
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) {
                writer.shutdownNow();
            }
        } catch (InterruptedException e) {
            writer.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------
    //  Utilidades
    // ------------------------------------------------------------------

    /** TPS de los últimos ~5 s a partir del tiempo medio de tick. */
    static double currentTps() {
        double mspt = Bukkit.getAverageTickTime();
        if (mspt <= 0) {
            return 20.0;
        }
        return Math.min(20.0, 1000.0 / mspt);
    }

    static String flatten(Map<String, String> details) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : details.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(e.getKey()).append(": ").append(e.getValue());
        }
        return sb.toString();
    }

    String newProofId(long now) {
        return "BI-" + ID_TIME.format(Instant.ofEpochMilli(now)) + "-"
                + String.format(Locale.ROOT, "%04X", ThreadLocalRandom.current().nextInt(0x10000));
    }

    private String describe(ItemStack it) {
        if (it == null || it.getType().isAir()) {
            return "AIR";
        }
        StringBuilder sb = new StringBuilder(it.getType().name()).append(" x").append(it.getAmount());
        if (plugin.settings().evItemNames && it.hasItemMeta()) {
            Component name = it.getItemMeta().displayName();
            if (name != null) {
                sb.append(" \"").append(PlainTextComponentSerializer.plainText().serialize(name)).append('"');
            }
        }
        return sb.toString();
    }

    private void add(PlayerData d, EvidenceEvent ev) {
        Settings s = plugin.settings();
        long keep = s.evKeepSeconds * 1000L;
        d.evidence.addLast(ev);
        while (!d.evidence.isEmpty()
                && (d.evidence.size() > s.evBufferMax || ev.time - d.evidence.peekFirst().time > keep)) {
            d.evidence.pollFirst();
        }
    }

    PingStats pingStats(Player p, PlayerData d, long now) {
        int cur = p.getPing();
        long sum = cur;
        int n = 1;
        int max = cur;
        long win = plugin.settings().evPingWindowSeconds * 1000L;
        for (EvidenceEvent ev : d.evidence) {
            if (now - ev.time <= win) {
                sum += ev.ping;
                n++;
                max = Math.max(max, ev.ping);
            }
        }
        return new PingStats(cur, (int) Math.round((double) sum / n), max);
    }

    // ------------------------------------------------------------------
    //  Registro de acciones (hilo principal)
    // ------------------------------------------------------------------

    EvidenceEvent recordClick(Player p, PlayerData d, InventoryClickEvent e, long now, String exemptReason) {
        if (!plugin.settings().evEnabled) {
            return null;
        }
        ClickType ct = e.getClick();
        int raw = e.getRawSlot();
        int topSize = e.getView().getTopInventory().getSize();
        boolean top = raw >= 0 && raw < topSize;
        String where = raw < 0 ? "FUERA" : top ? "ARRIBA:" + e.getView().getTopInventory().getType().name() : "JUGADOR";
        ItemStack cur = e.getCurrentItem();
        ItemStack cursor = e.getCursor();
        int ping = p.getPing();

        StringBuilder sb = new StringBuilder(200);
        sb.append(String.format(Locale.ROOT, "%-12s", ct.name()));
        sb.append(" acción=").append(e.getAction().name());
        sb.append(" | ").append(where).append(" slot=").append(e.getSlot())
                .append(" (raw ").append(raw).append(", ").append(e.getSlotType().name()).append(')');
        if (e.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY && raw >= 0) {
            sb.append(top ? " | destino=JUGADOR" : " | destino=ARRIBA");
        }
        sb.append(" | ítem=").append(describe(cur));
        if (cursor != null && !cursor.getType().isAir()) {
            sb.append(" | cursor=").append(describe(cursor));
        }
        if (ct == ClickType.NUMBER_KEY) {
            int b = e.getHotbarButton();
            sb.append(" | tecla=").append(b + 1);
            if (b >= 0 && b <= 8) {
                sb.append(" ítem-hotbar=").append(describe(p.getInventory().getItem(b)));
            }
        }
        sb.append(" | tick=").append(Bukkit.getCurrentTick());
        sb.append(" | ping=").append(ping).append("ms");
        if (d.lastFastMove > 0) {
            sb.append(String.format(Locale.ROOT, " | mov: racha=%d vel=%.2f hace=%dms",
                    d.moveStreak, d.lastMoveDist, now - d.lastFastMove));
        } else {
            sb.append(" | mov: quieto");
        }

        boolean hasItem = cur != null && !cur.getType().isAir();
        EvidenceEvent ev = new EvidenceEvent(now, EvidenceEvent.Kind.CLICK, ping, sb.toString(), ct.name(),
                hasItem ? cur.getType().name() : null, hasItem ? cur.getAmount() : 0);
        if (exemptReason != null) {
            ev.note = " [EXENTO: " + exemptReason + "]";
        }
        add(d, ev);
        return ev;
    }

    EvidenceEvent recordHeld(Player p, PlayerData d, int prev, int next, boolean scroll, long now, String exemptReason) {
        if (!plugin.settings().evEnabled || !plugin.settings().evHotbar) {
            return null;
        }
        int ping = p.getPing();
        String text = "hotbar " + (prev + 1) + " → " + (next + 1) + (scroll ? " (rueda)" : " (tecla)")
                + " | ítem=" + describe(p.getInventory().getItem(next)) + " | ping=" + ping + "ms";
        EvidenceEvent ev = new EvidenceEvent(now, EvidenceEvent.Kind.HELD, ping, text, "HELD", null, 0);
        if (exemptReason != null) {
            ev.note = " [EXENTO: " + exemptReason + "]";
        }
        add(d, ev);
        return ev;
    }

    EvidenceEvent recordSwap(Player p, PlayerData d, long now, String exemptReason) {
        if (!plugin.settings().evEnabled || !plugin.settings().evHotbar) {
            return null;
        }
        int ping = p.getPing();
        String text = "tecla F | mano principal=" + describe(p.getInventory().getItemInMainHand())
                + " | mano secundaria=" + describe(p.getInventory().getItemInOffHand()) + " | ping=" + ping + "ms";
        EvidenceEvent ev = new EvidenceEvent(now, EvidenceEvent.Kind.SWAP, ping, text, "SWAP", null, 0);
        if (exemptReason != null) {
            ev.note = " [EXENTO: " + exemptReason + "]";
        }
        add(d, ev);
        return ev;
    }

    void recordViolation(Player p, PlayerData d, Check check, int vl, int max, Map<String, String> details, long now) {
        Settings s = plugin.settings();
        if (!s.evEnabled) {
            return;
        }
        int ping = p.getPing();
        String text = check.display() + " VL " + vl + "/" + max + " | " + flatten(details) + " | ping=" + ping + "ms";
        EvidenceEvent ev = new EvidenceEvent(now, EvidenceEvent.Kind.VIOLATION, ping, text, check.display(), null, 0);
        add(d, ev);
        if (s.evEachViolation) {
            append(violationBlock(p, d, check, vl, max, details, now, ping));
        }
    }

    // ------------------------------------------------------------------
    //  Informes
    // ------------------------------------------------------------------

    /** Informe completo al sancionar. */
    void writePunishmentReport(Player p, PlayerData d, Check check, int vl, int max,
                               List<String> commands, String details, String proofId, long now, PingStats ps) {
        Settings s = plugin.settings();
        List<String[]> info = new ArrayList<>();
        info.add(new String[]{"Check", check.display() + "  (" + check.id() + ")"});
        info.add(new String[]{"Infracciones (VL)", vl + "/" + max + " dentro de " + s.decaySeconds + "s"});
        info.add(new String[]{"Último detalle", details});
        for (int i = 0; i < commands.size(); i++) {
            info.add(new String[]{i == 0 ? "Comando ejecutado" : "", commands.get(i)});
        }
        append(compose("INFORME DE SANCIÓN", proofId, p, d, now, ps, info));
    }

    /** Informe manual (/batmaninv evidence). @return el ID de prueba */
    String writeManualReport(Player p, PlayerData d, String requestedBy) {
        long now = System.currentTimeMillis();
        String id = newProofId(now);
        List<String[]> info = new ArrayList<>();
        info.add(new String[]{"Solicitado por", requestedBy});
        append(compose("INFORME MANUAL", id, p, d, now, pingStats(p, d, now), info));
        return id;
    }

    private void kv(StringBuilder sb, String key, String value) {
        sb.append(String.format(Locale.ROOT, "%-22s %s", key.isEmpty() ? "" : key + " :", value)).append('\n');
    }

    private String compose(String title, String proofId, Player p, PlayerData d, long now, PingStats ps, List<String[]> info) {
        Settings s = plugin.settings();
        StringBuilder sb = new StringBuilder(8192);
        sb.append('\n').append(LINE).append('\n');
        sb.append("BATMANINV · ").append(title).append(" · ID DE PRUEBA: ").append(proofId).append('\n');
        sb.append(LINE).append('\n');

        String brand = p.getClientBrandName();
        Location l = p.getLocation();
        double tps = currentTps();
        kv(sb, "Fecha", DATE.format(Instant.ofEpochMilli(now)) + "  (epoch ms " + now + ", zona " + ZONE + ")");
        kv(sb, "Jugador", p.getName() + "  (" + p.getUniqueId() + ")");
        kv(sb, "Cliente", brand == null || brand.isEmpty() ? "desconocido" : brand);
        kv(sb, "Ping", "actual " + ps.current() + "ms | promedio " + ps.avg() + "ms | máximo " + ps.max()
                + "ms (últimos " + s.evPingWindowSeconds + "s)");
        kv(sb, "TPS del servidor", String.format(Locale.ROOT, "%.1f (%.1f ms/tick)", tps, Bukkit.getAverageTickTime()));
        kv(sb, "Mundo / posición", p.getWorld().getName() + String.format(Locale.ROOT, " (%.1f, %.1f, %.1f)", l.getX(), l.getY(), l.getZ())
                + " | modo " + p.getGameMode().name());
        for (String[] row : info) {
            kv(sb, row[0], row[1]);
        }
        kv(sb, "Versión BatmanInv", plugin.getDescription().getVersion());

        List<EvidenceEvent> all = new ArrayList<>(d.evidence);

        // Infracciones
        List<EvidenceEvent> viol = new ArrayList<>();
        for (EvidenceEvent ev : all) {
            if (ev.kind == EvidenceEvent.Kind.VIOLATION) {
                viol.add(ev);
            }
        }
        sb.append('\n').append(SUB).append('\n');
        sb.append("INFRACCIONES REGISTRADAS (").append(viol.size()).append(")\n");
        sb.append(SUB).append('\n');
        if (viol.isEmpty()) {
            sb.append("  (ninguna en el historial reciente)\n");
        } else {
            appendEvents(sb, viol, "  ");
        }

        // Resumen sobre la ventana del informe
        int from = Math.max(0, all.size() - Math.max(1, s.evReportMaxEvents));
        List<EvidenceEvent> window = all.subList(from, all.size());
        sb.append('\n').append(SUB).append('\n');
        sb.append("RESUMEN (últimas ").append(window.size()).append(" acciones registradas)\n");
        sb.append(SUB).append('\n');
        appendSummary(sb, window);

        // Cronología
        sb.append('\n').append(SUB).append('\n');
        sb.append("CRONOLOGÍA COMPLETA (+N ms = milisegundos desde la acción anterior)\n");
        sb.append(SUB).append('\n');
        appendEvents(sb, window, "  ");

        sb.append('\n').append(SUB).append('\n');
        sb.append("Notas: los tiempos los mide el servidor al recibir cada acción (System.currentTimeMillis).\n");
        sb.append("  \"EXENTO\" = la acción ocurrió mientras el jugador estaba exento (lag, teletransporte...) y no se evaluó.\n");
        sb.append("  \"CANCELADO POR BATMANINV\" = el plugin impidió esa acción.\n");
        sb.append(LINE).append("\n\n");
        return sb.toString();
    }

    private String violationBlock(Player p, PlayerData d, Check check, int vl, int max,
                                  Map<String, String> details, long now, int ping) {
        Settings s = plugin.settings();
        StringBuilder sb = new StringBuilder(2048);
        sb.append('\n').append(">>> [").append(DATE.format(Instant.ofEpochMilli(now))).append("] ")
                .append(check.display()).append(" VL ").append(vl).append('/').append(max)
                .append(" | ").append(p.getName()).append(" (").append(p.getUniqueId()).append(")\n");
        sb.append(String.format(Locale.ROOT, "    ping=%dms | TPS=%.1f | mundo=%s\n", ping, currentTps(), p.getWorld().getName()));
        sb.append("    Detalles: ").append(flatten(details)).append('\n');

        List<EvidenceEvent> all = new ArrayList<>(d.evidence);
        int from = Math.max(0, all.size() - Math.max(1, s.evViolationContext));
        sb.append("    Últimas ").append(all.size() - from).append(" acciones:\n");
        appendEvents(sb, all.subList(from, all.size()), "      ");
        return sb.toString();
    }

    private static String label(EvidenceEvent ev) {
        return switch (ev.kind) {
            case CLICK -> "CLIC";
            case HELD -> "HOTBAR";
            case SWAP -> "TECLA-F";
            case VIOLATION -> ">>> INFRACCIÓN";
        };
    }

    private void appendEvents(StringBuilder sb, List<EvidenceEvent> evs, String indent) {
        long prev = -1;
        for (EvidenceEvent ev : evs) {
            long delta = prev < 0 ? 0 : ev.time - prev;
            prev = ev.time;
            sb.append(indent).append('[').append(TIME.format(Instant.ofEpochMilli(ev.time))).append("] (+")
                    .append(String.format(Locale.ROOT, "%5d", delta)).append("ms) ")
                    .append(String.format(Locale.ROOT, "%-14s", label(ev))).append(' ')
                    .append(ev.text).append(ev.note);
            if (ev.cancelled) {
                sb.append(" [CANCELADO POR BATMANINV]");
            }
            sb.append('\n');
        }
    }

    private static int maxWithin(List<EvidenceEvent> clicks, long ms) {
        int best = 0;
        int lo = 0;
        for (int hi = 0; hi < clicks.size(); hi++) {
            while (clicks.get(hi).time - clicks.get(lo).time > ms) {
                lo++;
            }
            best = Math.max(best, hi - lo + 1);
        }
        return best;
    }

    private void appendSummary(StringBuilder sb, List<EvidenceEvent> evs) {
        if (evs.isEmpty()) {
            sb.append("  (sin acciones registradas)\n");
            return;
        }
        long first = evs.get(0).time;
        long last = evs.get(evs.size() - 1).time;

        List<EvidenceEvent> clicks = new ArrayList<>();
        Map<String, Integer> types = new TreeMap<>();
        Map<String, int[]> items = new LinkedHashMap<>();
        Map<String, Integer> viol = new TreeMap<>();
        int held = 0;
        int swap = 0;
        int cancelled = 0;
        int exempt = 0;
        for (EvidenceEvent ev : evs) {
            switch (ev.kind) {
                case CLICK -> {
                    clicks.add(ev);
                    types.merge(ev.click, 1, Integer::sum);
                    if (ev.item != null) {
                        int[] a = items.computeIfAbsent(ev.item, k -> new int[2]);
                        a[0]++;
                        a[1] += ev.amount;
                    }
                }
                case HELD -> held++;
                case SWAP -> swap++;
                case VIOLATION -> viol.merge(ev.click, 1, Integer::sum);
            }
            if (ev.cancelled) {
                cancelled++;
            }
            if (ev.note.contains("EXENTO")) {
                exempt++;
            }
        }

        kv(sb, "  Periodo analizado", (last - first) + " ms  (" + TIME.format(Instant.ofEpochMilli(first)) + " → "
                + TIME.format(Instant.ofEpochMilli(last)) + ")");
        kv(sb, "  Clics de inventario", clicks.size() + (types.isEmpty() ? "" : "  " + types.toString().replace("=", " x")));
        if (clicks.size() >= 2) {
            long min = Long.MAX_VALUE;
            long max = 0;
            long sum = 0;
            for (int i = 1; i < clicks.size(); i++) {
                long dt = clicks.get(i).time - clicks.get(i - 1).time;
                min = Math.min(min, dt);
                max = Math.max(max, dt);
                sum += dt;
            }
            kv(sb, "  Intervalo entre clics", "mínimo " + min + " ms | medio "
                    + String.format(Locale.ROOT, "%.1f", (double) sum / (clicks.size() - 1)) + " ms | máximo " + max + " ms");
            kv(sb, "  Ráfaga máxima", maxWithin(clicks, 100) + " clics en 100 ms | "
                    + maxWithin(clicks, 1000) + " clics en 1 s");
        }
        kv(sb, "  Cambios de hotbar", held + " cambios de slot | " + swap + " pulsaciones de F");
        kv(sb, "  Infracciones", viol.isEmpty() ? "0" : viol.toString().replace("=", " x"));
        kv(sb, "  Acciones canceladas", String.valueOf(cancelled));
        kv(sb, "  Acciones exentas", String.valueOf(exempt));
        if (!items.isEmpty()) {
            sb.append("  Ítems agarrados / movidos (por tipo):\n");
            int shown = 0;
            for (Map.Entry<String, int[]> e : items.entrySet()) {
                if (shown++ >= 25) {
                    sb.append("    ... y más\n");
                    break;
                }
                sb.append("    - ").append(e.getKey()).append(": ").append(e.getValue()[0]).append(" clics, ")
                        .append(e.getValue()[1]).append(" unidades\n");
            }
        }
    }

    // ------------------------------------------------------------------
    //  Escritura asíncrona
    // ------------------------------------------------------------------

    private void append(String text) {
        if (writer == null || writer.isShutdown()) {
            return;
        }
        final Path f = file;
        final int maxMb = plugin.settings().evMaxFileMb;
        writer.execute(() -> {
            try {
                Files.createDirectories(f.getParent());
                if (maxMb > 0 && Files.exists(f) && Files.size(f) > maxMb * 1024L * 1024L) {
                    Files.move(f, f.resolveSibling("violations-" + ID_TIME.format(Instant.now()) + ".txt"),
                            StandardCopyOption.REPLACE_EXISTING);
                }
                Files.writeString(f, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ex) {
                plugin.getLogger().warning("No se pudo escribir violations.txt: " + ex.getMessage());
            }
        });
    }
}
