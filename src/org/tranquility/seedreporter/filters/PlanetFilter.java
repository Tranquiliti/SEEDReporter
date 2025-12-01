package org.tranquility.seedreporter.filters;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketConditionAPI;
import org.json.JSONObject;
import org.tranquility.seedreporter.SEEDUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class PlanetFilter {
    public String filterName;
    public float maxHazardValue;
    public Set<String> matchesPlanetTypes;
    public Set<String> matchesConditions;
    public int matchesAtLeast;
    public Set<String> avoidConditions;

    public PlanetFilter(JSONObject settings) {
        filterName = settings.optString("filterName", "Planet filter");

        // Convert from percentage (0-400) to decimal (0.0-4.0)
        // JSON config uses percentage for readability: 175 means 175% hazard
        // Game uses decimal: 1.75 means 175% hazard
        maxHazardValue = (float) settings.optDouble("maxHazardValue", Integer.MAX_VALUE) / 100f;

        matchesPlanetTypes = SEEDUtils.convertJSONArrayToSet(settings.optJSONArray("matchesPlanetTypes"));
        matchesConditions = SEEDUtils.convertJSONArrayToSet(settings.optJSONArray("matchesConditions"));
        avoidConditions = SEEDUtils.convertJSONArrayToSet(settings.optJSONArray("avoidConditions"));

        if (matchesConditions != null) matchesAtLeast = settings.optInt("matchesAtLeast", matchesConditions.size());
    }

    public Map<StarSystemAPI, Set<PlanetAPI>> run() {
        Map<StarSystemAPI, Set<PlanetAPI>> foundPlanetsBySystem = new HashMap<>();

        for (StarSystemAPI system : Global.getSector().getStarSystems()) {
            // Only check conditions if specified; otherwise, do not check them to slightly speed up search
            boolean checkAvoid = !(avoidConditions == null || avoidConditions.isEmpty());
            boolean checkMatch = !(matchesConditions == null || matchesConditions.isEmpty());

            for (PlanetAPI planet : system.getPlanets()) {
                if (matchesPlanetTypes != null && !matchesPlanetTypes.isEmpty() && !matchesPlanetTypes.contains(planet.getTypeId()))
                    continue;
                if (planet.getMarket() == null || planet.getMarket().getHazardValue() > maxHazardValue) continue;

                if (checkAvoid) {
                    boolean hasAvoidCondition = false;
                    for (MarketConditionAPI condition : planet.getMarket().getConditions())
                        if (avoidConditions.contains(condition.getId())) {
                            hasAvoidCondition = true;
                            break;
                        }

                    if (hasAvoidCondition) continue;
                }

                if (checkMatch) {
                    int numMatches = 0;
                    for (MarketConditionAPI condition : planet.getMarket().getConditions())
                        if (matchesConditions.contains(condition.getId())) numMatches++;
                    if (numMatches < matchesAtLeast) continue;
                }

                // Add planet to this system's set
                foundPlanetsBySystem.computeIfAbsent(system, k -> new HashSet<>()).add(planet);
            }
        }

        return foundPlanetsBySystem;
    }
}
