package org.tranquility.seedreporter;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import lunalib.lunaSettings.LunaSettings;
import org.lazywizard.console.Console;
import org.tranquility.seedreporter.lunalib.SEEDLunaSettingsListener;

import static org.tranquility.seedreporter.SEEDUtils.LUNALIB_ENABLED;

@SuppressWarnings("unused")
public class SEEDModPlugin extends BaseModPlugin {
    @Override
    public void onApplicationLoad() {
        if (LUNALIB_ENABLED) LunaSettings.addSettingsListener(new SEEDLunaSettingsListener());
    }

    @Override
    public void onGameLoad(boolean newGame) {
        if (newGame && SEEDReport.runOnGameStart) try {
            String seedResult = new SEEDReport().run();
            if (Global.getSettings().getModManager().isModEnabled("lw_console")) Console.showMessage(seedResult);
            else Global.getLogger(SEEDModPlugin.class).info(seedResult);
        } catch (RuntimeException e) {
            if (Global.getSettings().getModManager().isModEnabled("lw_console"))
                Console.showException("Error occurred while running SEED report on game load:", e);
            else
                Global.getLogger(SEEDModPlugin.class).error("Error occurred while running SEED report on game load:\n", e);
        }
    }
}