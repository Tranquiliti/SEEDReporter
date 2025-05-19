package org.tranquility.seedreporter;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import org.lazywizard.console.Console;

@SuppressWarnings("unused")
public class SEEDModPlugin extends BaseModPlugin {
    @Override
    public void onGameLoad(boolean newGame) {
        if (newGame) {
            String seedResult = new SEEDReport().run();
            if (Global.getSettings().getModManager().isModEnabled("lw_console")) Console.showMessage(seedResult);
            else Global.getLogger(SEEDModPlugin.class).info(seedResult);
        }
    }
}