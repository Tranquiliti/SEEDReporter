package org.tranquility.seedreporter;

import com.fs.starfarer.api.Global;
import org.json.JSONArray;
import org.json.JSONException;

import java.util.HashSet;
import java.util.Set;

public final class SEEDUtils {
    public static final boolean LUNALIB_ENABLED = Global.getSettings().getModManager().isModEnabled("lunalib");

    public static Set<String> convertJSONArrayToSet(JSONArray jsonArray) {
        if (jsonArray == null) return null;

        try {
            Set<String> stringSet = new HashSet<>();
            for (int i = 0; i < jsonArray.length(); i++)
                stringSet.add(jsonArray.getString(i));
            return stringSet;
        } catch (JSONException e) {
            return null;
        }
    }
}
