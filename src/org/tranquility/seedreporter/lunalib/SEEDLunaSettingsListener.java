package org.tranquility.seedreporter.lunalib;

import lunalib.lunaSettings.LunaSettings;
import lunalib.lunaSettings.LunaSettingsListener;
import org.tranquility.seedreporter.SEEDReport;

public class SEEDLunaSettingsListener implements LunaSettingsListener {
    @Override
    public void settingsChanged(String s) {
        SEEDReport.runOnGameStart = Boolean.TRUE.equals(LunaSettings.getBoolean("seedreporter", "runSEEDReportOnGameStart"));
    }
}
