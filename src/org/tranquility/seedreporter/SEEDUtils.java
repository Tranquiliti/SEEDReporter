package org.tranquility.seedreporter;

import com.fs.starfarer.api.Global;

public final class SEEDUtils {
    public static final boolean LUNALIB_ENABLED = Global.getSettings().getModManager().isModEnabled("lunalib");
}
