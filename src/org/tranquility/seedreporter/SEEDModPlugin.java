package org.tranquility.seedreporter;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import lunalib.lunaSettings.LunaSettings;
import org.lazywizard.console.Console;
import org.tranquility.seedreporter.lunalib.SEEDLunaSettingsListener;

import static org.tranquility.seedreporter.SEEDUtils.LUNALIB_ENABLED;

@SuppressWarnings("unused")
public class SEEDModPlugin extends BaseModPlugin {
    // TODO: review seedreporterSettings.json to ensure quality feature use (default filters may not be leveraging all available features)
    // TODO: change "hasEntityWithTags" (or add new option) to look for all entities with any specified tag, rather than finding an entity with all specified tags
    // TODO: add new option to search for specific SectorEntityTokens (e.g., research stations, derelict ships, caches, etc.)
    // TODO: update the print formatting for exceptional officers and Tesseract to match the new system filter printing
    @Override
    public void onApplicationLoad() {
        if (LUNALIB_ENABLED) LunaSettings.addSettingsListener(new SEEDLunaSettingsListener());
    }

    @Override
    public void onGameLoad(boolean newGame) {
        if (newGame && SEEDReport.runOnGameStart) {
            String seedResult = new SEEDReport().run();
            if (Global.getSettings().getModManager().isModEnabled("lw_console")) Console.showMessage(seedResult);
            else Global.getLogger(SEEDModPlugin.class).info(seedResult);
        }
    }
}