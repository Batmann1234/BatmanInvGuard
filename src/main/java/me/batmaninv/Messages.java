package me.batmaninv;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Messages {
    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.builder().character('&').hexColors().build();
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final BatmanInvPlugin plugin;
    private YamlConfiguration yml = new YamlConfiguration();
    private boolean mini;

    Messages(BatmanInvPlugin plugin) {
        this.plugin = plugin;
    }

    void load() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        yml = YamlConfiguration.loadConfiguration(file);
        try (InputStream in = plugin.getResource("messages.yml")) {
            if (in != null) {
                yml.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        } catch (Exception ignored) {
            // sin defaults
        }
        mini = "MINIMESSAGE".equalsIgnoreCase(yml.getString("format", "LEGACY"));
    }

    public String raw(String path) {
        String v = yml.getString(path);
        return v == null ? "" : v;
    }

    public static Map<String, String> ph(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    /** Reemplaza %prefix% y los placeholders dados (dos pasadas por si hay anidados). */
    public String apply(String text, Map<String, String> ph) {
        String out = text;
        for (int pass = 0; pass < 2; pass++) {
            out = out.replace("%prefix%", raw("prefix"));
            if (ph != null) {
                for (Map.Entry<String, String> e : ph.entrySet()) {
                    out = out.replace("%" + e.getKey() + "%", e.getValue());
                }
            }
        }
        return out;
    }

    public Component parse(String text) {
        return mini ? MINI.deserialize(text) : LEGACY.deserialize(text);
    }

    public void send(CommandSender to, String path, Map<String, String> ph) {
        sendText(to, apply(raw(path), ph));
    }

    /** Envía un texto de varias líneas, una línea por mensaje. */
    public void sendText(CommandSender to, String text) {
        for (String line : text.split("\n", -1)) {
            to.sendMessage(parse(line));
        }
    }

    public String detail(String key) {
        String v = raw("details." + key);
        return v.isEmpty() ? key : v;
    }
}
