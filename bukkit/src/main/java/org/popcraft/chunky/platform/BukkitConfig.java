package org.popcraft.chunky.platform;

import org.bukkit.configuration.file.FileConfigurationOptions;
import org.popcraft.chunky.ChunkyBukkit;
import org.popcraft.chunky.util.Input;
import org.popcraft.chunky.util.Translator;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public class BukkitConfig implements Config {
    private static final List<String> HEADER = Arrays.asList("Chunky Configuration", "https://github.com/pop4959/Chunky/wiki/Configuration");
    private final ChunkyBukkit plugin;

    public BukkitConfig(final ChunkyBukkit plugin) {
        this.plugin = plugin;
        final FileConfigurationOptions options = plugin.getConfig().options();
        options.copyDefaults(true);
        try {
            FileConfigurationOptions.class.getMethod("header", String.class).invoke(options, String.join("\n", HEADER));
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            options.setHeader(HEADER);
        }
        plugin.saveConfig();
        Translator.setLanguage(getLanguage());
    }

    @Override
    public Path getDirectory() {
        return plugin.getDataFolder().toPath();
    }

    @Override
    public int getVersion() {
        return plugin.getConfig().getInt("version", 0);
    }

    @Override
    public String getLanguage() {
        return Input.checkLanguage(plugin.getConfig().getString("language", "en"));
    }

    @Override
    public boolean getContinueOnRestart() {
        return plugin.getConfig().getBoolean("continue-on-restart", false);
    }

    @Override
    public boolean isForceLoadExistingChunks() {
        return plugin.getConfig().getBoolean("force-load-existing-chunks", false);
    }

    @Override
    public boolean isSilent() {
        return plugin.getConfig().getBoolean("silent", false);
    }

    @Override
    public void setSilent(final boolean silent) {
        plugin.getConfig().set("silent", silent);
    }

    @Override
    public int getUpdateInterval() {
        return plugin.getConfig().getInt("update-interval", 1);
    }

    @Override
    public void setUpdateInterval(final int updateInterval) {
        plugin.getConfig().set("update-interval", updateInterval);
    }

    @Override
    public void reload() {
        plugin.reloadConfig();
    }

    @Override
    public int getMaxWorkingCount() {
        return plugin.getConfig().getInt("performance.max-working-count",
                Input.tryInteger(System.getProperty("chunky.maxWorkingCount")).orElse(0));
    }

    @Override
    public boolean isPrecheckExistingChunks() {
        return plugin.getConfig().getBoolean("performance.precheck-existing-chunks",
                Boolean.getBoolean("chunky.precheck"));
    }

    @Override
    public boolean isUrgentLoad() {
        return plugin.getConfig().getBoolean("performance.urgent-load",
                Boolean.getBoolean("chunky.urgentLoad"));
    }

    @Override
    public boolean isLoadOutdatedChunks() {
        return plugin.getConfig().getBoolean("performance.load-outdated-chunks",
                Boolean.getBoolean("chunky.loadOutdatedChunks"));
    }

    @Override
    public int getTickingLoadDuration() {
        return plugin.getConfig().getInt("performance.ticking-load-duration",
                Input.tryInteger(System.getProperty("chunky.tickingLoadDuration")).orElse(0));
    }

    @Override
    public boolean isAwaitTicketRemoval() {
        return plugin.getConfig().getBoolean("performance.await-ticket-removal",
                Boolean.getBoolean("chunky.awaitTicketRemoval"));
    }

    @Override
    public int getLoadThreads() {
        return plugin.getConfig().getInt("performance.load-threads",
                Input.tryInteger(System.getProperty("chunky.loadThreads")).orElse(0));
    }
}
