package com.rylinaux.plugman;

import com.rylinaux.plugman.pojo.UpdateResult;
import com.rylinaux.plugman.util.GeyserMCUtil;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class AutoUpdateChecker extends BukkitRunnable {

    private final PlugMan plugman;
    private final FileConfiguration config;
    private final List<String> updatedPlugins = new ArrayList<>();

    public AutoUpdateChecker(PlugMan plugman) {
        this.plugman = plugman;
        this.config = plugman.getConfig();
    }

    @Override
    public void run() {
        plugman.getLogger().info("[AutoUpdate] Check cycle started");
        
        if (!config.getBoolean("auto-update.enabled", true)) {
            plugman.getLogger().info("[AutoUpdate] Disabled in config, skipping");
            return;
        }

        updatedPlugins.clear();
        List<String> pluginsToCheck = config.getStringList("auto-update.plugins");
        
        plugman.getLogger().info("[AutoUpdate] Checking " + pluginsToCheck.size() + " plugins for updates...");
        
        for (String pluginName : pluginsToCheck) {
            checkAndUpdatePlugin(pluginName);
        }
        
        // If any plugins were updated and auto-restart is enabled, schedule restart
        if (!updatedPlugins.isEmpty() && config.getBoolean("auto-update.auto-restart", true)) {
            scheduleRestart();
        }
        
        plugman.getLogger().info("[AutoUpdate] Check cycle completed");
    }

    private void checkAndUpdatePlugin(String pluginName) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(pluginName);
        if (plugin == null) {
            plugman.getLogger().warning("[AutoUpdate] Plugin " + pluginName + " not found, skipping");
            return;
        }

        plugman.getLogger().info("[AutoUpdate] Checking " + pluginName + "...");
        UpdateResult result = GeyserMCUtil.checkUpToDate(pluginName);
        
        // Check if update is available by examining the result type
        if (result != null && result.getType() == UpdateResult.ResultType.OUT_OF_DATE) {
            String message = pluginName + " has an update available! Current: " + 
                           result.getCurrentVersion() + " | Latest: " + result.getLatestVersion();
            
            plugman.getLogger().warning(message);
            
            if (config.getBoolean("auto-update.notify-admins", true)) {
                notifyAdmins(pluginName, result, false);
            }

            if (config.getBoolean("auto-update.auto-install", true)) {
                if (downloadAndInstall(pluginName)) {
                    updatedPlugins.add(pluginName);
                }
            }
        } else if (result != null && result.getType() == UpdateResult.ResultType.UP_TO_DATE) {
            plugman.getLogger().info("[AutoUpdate] " + pluginName + " is up to date (" + result.getCurrentVersion() + ")");
        } else {
            plugman.getLogger().warning("[AutoUpdate] Could not check " + pluginName + " (API may be down)");
        }
    }

    private void notifyAdmins(String pluginName, UpdateResult result, boolean downloaded) {
        Bukkit.getScheduler().runTask(plugman, () -> {
            Bukkit.getOnlinePlayers().stream()
                .filter(player -> player.hasPermission("plugman.admin"))
                .forEach(player -> {
                    if (!downloaded) {
                        player.sendMessage("§e[PlugManX] §c" + pluginName + " has an update available!");
                        player.sendMessage("§e[PlugManX] §7Current: " + result.getCurrentVersion() + 
                                         " | Latest: " + result.getLatestVersion());
                    } else {
                        player.sendMessage("§e[PlugManX] §aSuccessfully downloaded update for " + pluginName + "!");
                    }
                });
        });
    }

    private boolean downloadAndInstall(String pluginName) {
        plugman.getLogger().info("Downloading update for " + pluginName + "...");
        
        // Map plugin name to project name
        String projectName;
        if (pluginName.equalsIgnoreCase("Geyser-Spigot") || pluginName.equalsIgnoreCase("Geyser")) {
            projectName = "geyser";
        } else if (pluginName.equalsIgnoreCase("floodgate")) {
            projectName = "floodgate";
        } else {
            plugman.getLogger().warning("Unknown plugin for auto-update: " + pluginName);
            return false;
        }

        String downloadURL = GeyserMCUtil.getDownloadURL(projectName);
        
        try {
            // Download the file
            File pluginsFolder = plugman.getDataFolder().getParentFile();
            File tempFile = new File(pluginsFolder, pluginName + "-UPDATE.jar.tmp");
            
            if (downloadFile(downloadURL, tempFile)) {
                // Find old file
                Plugin plugin = Bukkit.getPluginManager().getPlugin(pluginName);
                if (plugin != null) {
                    File oldFile = getPluginFile(plugin);
                    if (oldFile != null && oldFile.exists()) {
                        // Create backup
                        File oldBackup = new File(pluginsFolder, oldFile.getName() + ".old");
                        if (oldBackup.exists()) {
                            oldBackup.delete();
                        }
                        
                        // Delete old file and rename new file
                        String originalName = oldFile.getName();
                        oldFile.renameTo(oldBackup);
                        
                        File newFile = new File(pluginsFolder, originalName);
                        tempFile.renameTo(newFile);
                        
                        plugman.getLogger().info("Successfully downloaded update for " + pluginName);
                        plugman.getLogger().info("Old version backed up as: " + oldBackup.getName());
                        
                        if (config.getBoolean("auto-update.notify-admins", true)) {
                            notifyAdmins(pluginName, null, true);
                        }
                        
                        return true;
                    }
                }
                
                // Fallback: just use plugin name
                File oldFile = findPluginJar(pluginsFolder, pluginName);
                if (oldFile != null && oldFile.exists()) {
                    File oldBackup = new File(pluginsFolder, oldFile.getName() + ".old");
                    if (oldBackup.exists()) {
                        oldBackup.delete();
                    }
                    
                    String originalName = oldFile.getName();
                    oldFile.renameTo(oldBackup);
                    
                    File newFile = new File(pluginsFolder, originalName);
                    tempFile.renameTo(newFile);
                    
                    plugman.getLogger().info("Successfully downloaded update for " + pluginName);
                    return true;
                }
            }
            
            // Clean up temp file if failed
            if (tempFile.exists()) {
                tempFile.delete();
            }
            
        } catch (IOException e) {
            plugman.getLogger().severe("Failed to download update for " + pluginName + ": " + e.getMessage());
            e.printStackTrace();
        }
        
        return false;
    }

    private File findPluginJar(File pluginsFolder, String pluginName) {
        // Try common naming patterns
        String[] patterns = {
            pluginName + ".jar",
            pluginName + "-Spigot.jar",
            pluginName.toLowerCase() + ".jar",
            pluginName.replace("-", "") + ".jar"
        };
        
        for (String pattern : patterns) {
            File file = new File(pluginsFolder, pattern);
            if (file.exists()) {
                return file;
            }
        }
        
        // Search for files containing the plugin name
        File[] files = pluginsFolder.listFiles((dir, name) -> 
            name.toLowerCase().contains(pluginName.toLowerCase()) && name.endsWith(".jar")
        );
        
        if (files != null && files.length > 0) {
            return files[0];
        }
        
        return null;
    }

    private boolean downloadFile(String urlString, File outputFile) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestProperty("User-Agent", "PlugManX");
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(30000);
        
        int responseCode = connection.getResponseCode();
        if (responseCode != 200) {
            plugman.getLogger().severe("Failed to download file. HTTP " + responseCode);
            return false;
        }
        
        long fileSize = connection.getContentLengthLong();
        plugman.getLogger().info("Downloading " + (fileSize / 1024) + " KB...");
        
        try (InputStream in = connection.getInputStream();
             FileOutputStream out = new FileOutputStream(outputFile)) {
            
            byte[] buffer = new byte[8192];
            int bytesRead;
            long totalRead = 0;
            
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                totalRead += bytesRead;
            }
            
            plugman.getLogger().info("Downloaded " + (totalRead / 1024) + " KB successfully");
        }
        
        return true;
    }

    private File getPluginFile(Plugin plugin) {
        try {
            java.lang.reflect.Method method = plugin.getClass().getMethod("getFile");
            method.setAccessible(true);
            return (File) method.invoke(plugin);
        } catch (Exception e) {
            // Try alternative method
            try {
                return new File(plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            } catch (Exception ex) {
                return null;
            }
        }
    }

    private void scheduleRestart() {
        int delaySeconds = config.getInt("auto-update.restart-delay", 60);
        
        plugman.getLogger().warning("Updates downloaded. Server will restart in " + delaySeconds + " seconds...");
        
        Bukkit.getScheduler().runTask(plugman, () -> {
            Bukkit.broadcastMessage("§c§l[PlugManX] Server restarting in " + delaySeconds + " seconds to apply updates!");
            Bukkit.broadcastMessage("§e§lUpdated plugins: §f" + String.join(", ", updatedPlugins));
        });
        
        // Warning at 30 seconds
        if (delaySeconds >= 30) {
            Bukkit.getScheduler().runTaskLater(plugman, () -> {
                Bukkit.broadcastMessage("§c§l[PlugManX] Server restarting in 30 seconds...");
            }, (delaySeconds - 30) * 20L);
        }
        
        // Warning at 10 seconds
        if (delaySeconds >= 10) {
            Bukkit.getScheduler().runTaskLater(plugman, () -> {
                Bukkit.broadcastMessage("§c§l[PlugManX] Server restarting in 10 seconds...");
            }, (delaySeconds - 10) * 20L);
        }
        
        // Warning at 5 seconds
        if (delaySeconds >= 5) {
            Bukkit.getScheduler().runTaskLater(plugman, () -> {
                Bukkit.broadcastMessage("§c§l[PlugManX] Server restarting in 5 seconds...");
            }, (delaySeconds - 5) * 20L);
        }
        
        // Restart
        Bukkit.getScheduler().runTaskLater(plugman, () -> {
            Bukkit.broadcastMessage("§c§l[PlugManX] Restarting now!");
            
            try {
                Bukkit.spigot().restart();
            } catch (Exception e) {
                // Fallback to shutdown if restart fails
                plugman.getLogger().severe("Restart command failed, using shutdown instead");
                Bukkit.shutdown();
            }
        }, delaySeconds * 20L);
    }

    public void start() {
        int intervalMinutes = config.getInt("auto-update.check-interval", 60);
        long intervalTicks = intervalMinutes * 60 * 20L;
        
        plugman.getLogger().info("Auto-update checker starting...");
        plugman.getLogger().info("First check in 5 minutes, then every " + intervalMinutes + " minutes");
        
        // Run first check after 5 minutes, then repeat
        this.runTaskTimerAsynchronously(plugman, 5 * 60 * 20L, intervalTicks);
        
        plugman.getLogger().info("Auto-update checker scheduled successfully");
        
        List<String> plugins = config.getStringList("auto-update.plugins");
        plugman.getLogger().info("Monitoring plugins: " + String.join(", ", plugins));
        
        if (config.getBoolean("auto-update.auto-restart", true)) {
            plugman.getLogger().info("Auto-restart is ENABLED - server will restart automatically after updates");
        }
    }
}
