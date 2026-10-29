package me.batmaninv;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class BatmanInvPlugin extends JavaPlugin {
    private Settings settings;
    private Messages messages;
    private Storage storage;
    private ViolationManager violations;
    private BukkitTask autosave;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = new Settings();
        settings.load(getConfig());

        messages = new Messages(this);
        messages.load();

        storage = new Storage(this);
        storage.load();

        violations = new ViolationManager(this);

        getServer().getPluginManager().registerEvents(new GuardListener(this), this);

        PluginCommand cmd = getCommand("batmaninv");
        if (cmd != null) {
            BatmanInvCommand handler = new BatmanInvCommand(this);
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }

        scheduleAutosave();
        getLogger().info("BatmanInv activado (FastHotbar, InvMove, FastMouse).");
    }

    @Override
    public void onDisable() {
        if (autosave != null) {
            autosave.cancel();
        }
        if (storage != null) {
            storage.save();
        }
    }

    private void scheduleAutosave() {
        if (autosave != null) {
            autosave.cancel();
            autosave = null;
        }
        if (settings.storageEnabled && settings.autosaveMinutes > 0) {
            long ticks = settings.autosaveMinutes * 60L * 20L;
            autosave = getServer().getScheduler().runTaskTimerAsynchronously(this, () -> storage.save(), ticks, ticks);
        }
    }

    /** Recarga config.yml y messages.yml. */
    public void reloadAll() {
        reloadConfig();
        settings.load(getConfig());
        messages.load();
        scheduleAutosave();
    }

    public Settings settings() {
        return settings;
    }

    public Messages messages() {
        return messages;
    }

    public Storage storage() {
        return storage;
    }

    public ViolationManager violations() {
        return violations;
    }
}
