package com.rylinaux.plugman.util;

import com.rylinaux.plugman.PlugMan;
import com.rylinaux.plugman.pojo.UpdateResult;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClients;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

import java.io.IOException;

/**
 * Utilities for dealing with the GeyserMC API.
 */
public class GeyserMCUtil {

    /**
     * The base URL for the GeyserMC API.
     */
    public static final String API_BASE_URL = "https://download.geysermc.org/v2/projects/";

    /**
     * Check if the installed plugin version is up-to-date with the GeyserMC version.
     *
     * @param pluginName the plugin name (must be "Geyser-Spigot" or "floodgate").
     * @return the reflective UpdateResult.
     */
    public static UpdateResult checkUpToDate(String pluginName) {
        
        // Map plugin names to GeyserMC project names
        String projectName;
        if (pluginName.equalsIgnoreCase("Geyser-Spigot") || pluginName.equalsIgnoreCase("Geyser")) {
            projectName = "geyser";
        } else if (pluginName.equalsIgnoreCase("floodgate")) {
            projectName = "floodgate";
        } else {
            // Not a GeyserMC plugin
            Plugin plugin = Bukkit.getPluginManager().getPlugin(pluginName);
            if (plugin == null)
                return new UpdateResult(UpdateResult.ResultType.NOT_INSTALLED);
            return new UpdateResult(UpdateResult.ResultType.INVALID_PLUGIN, plugin.getDescription().getVersion());
        }

        String latestVersion = getLatestVersion(projectName);

        if (latestVersion == null) {
            Plugin plugin = Bukkit.getPluginManager().getPlugin(pluginName);
            if (plugin == null)
                return new UpdateResult(UpdateResult.ResultType.NOT_INSTALLED);
            return new UpdateResult(UpdateResult.ResultType.INVALID_PLUGIN, plugin.getDescription().getVersion());
        }

        String currentVersion = PlugMan.getInstance().getPluginUtil().getPluginVersion(pluginName);

        if (currentVersion == null)
            return new UpdateResult(UpdateResult.ResultType.NOT_INSTALLED, currentVersion, latestVersion);

        Boolean isActual = UpdateUtil.isActualVersion(currentVersion, latestVersion);
        if (isActual != null && isActual)
            return new UpdateResult(UpdateResult.ResultType.UP_TO_DATE, currentVersion, latestVersion);
        else
            return new UpdateResult(UpdateResult.ResultType.OUT_OF_DATE, currentVersion, latestVersion);
    }

    /**
     * Get the latest version for a GeyserMC project.
     *
     * @param projectName the project name ("geyser" or "floodgate").
     * @return the latest version string, or null if failed.
     */
    public static String getLatestVersion(String projectName) {

        HttpClient client = HttpClients.createMinimal();

        HttpGet get = new HttpGet(API_BASE_URL + projectName + "/versions/latest");
        get.setHeader("User-Agent", "PlugManX");

        try {

            HttpResponse response = client.execute(get);
            String body = IOUtils.toString(response.getEntity().getContent());

            JSONObject json = (JSONObject) JSONValue.parse(body);
            
            if (json != null && json.containsKey("version")) {
                return (String) json.get("version");
            }

        } catch (IOException e) {
            e.printStackTrace();
        }

        return null;
    }

    /**
     * Get the download URL for a GeyserMC project.
     *
     * @param projectName the project name ("geyser" or "floodgate").
     * @return the download URL.
     */
    public static String getDownloadURL(String projectName) {
        return API_BASE_URL + projectName + "/versions/latest/builds/latest/downloads/spigot";
    }
}
