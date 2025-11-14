package org.tranquility.seedreporter.filters;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketConditionAPI;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.Pair;
import org.json.JSONArray;
import org.json.JSONObject;
import org.lwjgl.util.vector.Vector2f;
import org.tranquility.seedreporter.SEEDUtils;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class PlanetFilter {
    public String filterName;
    public String saveShorthand;
    public float distanceFromCOM;
    public Set<String> avoidSystemTags;
    public Set<Pair<String, Float>> searchSystems;
    public int numStableLocations;
    public float maxHazardValue;
    public Set<String> matchesPlanetTypes;
    public Set<String> matchesConditions;
    public int matchesAtLeast;
    public Set<String> avoidConditions;

    public PlanetFilter(JSONObject settings) {
        filterName = settings.optString("filterName", "Planet filter");
        saveShorthand = settings.optString("saveShorthand", null);
        distanceFromCOM = (float) settings.optDouble("distanceFromCOM", Float.MAX_VALUE);
        avoidSystemTags = SEEDUtils.convertJSONArrayToSet(settings.optJSONArray("avoidSystemTags"));

        JSONArray searchArray = settings.optJSONArray("inStarSystemFilters");
        if (searchArray != null) {
            searchSystems = new HashSet<>();
            for (int i = 0; i < searchArray.length(); i++) {
                JSONArray pairArray = searchArray.optJSONArray(i);
                if (pairArray == null) searchSystems.add(new Pair<>(searchArray.optString(i), 0f));
                else searchSystems.add(new Pair<>(pairArray.optString(0), (float) pairArray.optDouble(1)));
            }
        }

        numStableLocations = settings.optInt("numStableLocations", 0);
        maxHazardValue = settings.optInt("maxHazardValue", Integer.MAX_VALUE);

        matchesPlanetTypes = SEEDUtils.convertJSONArrayToSet(settings.optJSONArray("matchesPlanetTypes"));
        matchesConditions = SEEDUtils.convertJSONArrayToSet(settings.optJSONArray("matchesConditions"));
        avoidConditions = SEEDUtils.convertJSONArrayToSet(settings.optJSONArray("avoidConditions"));

        if (matchesConditions != null) matchesAtLeast = settings.optInt("matchesAtLeast", matchesConditions.size());
    }

    public Set<PlanetAPI> run(Vector2f centerOfMass, Map<String, Set<StarSystemAPI>> filterSystems) {
        Set<PlanetAPI> foundPlanets = new HashSet<>();

        for (StarSystemAPI system : Global.getSector().getStarSystems()) {
            if (Misc.getDistanceLY(system.getLocation(), centerOfMass) > distanceFromCOM) continue;

            if (avoidSystemTags != null && !Collections.disjoint(system.getTags(), avoidSystemTags)) continue;

            // Check if this system is in all the specified filter systems
            boolean allFilterSystemPass = true;
            if (searchSystems != null) for (Pair<String, Float> searchSystemPair : searchSystems) {
                if (!filterSystems.containsKey(searchSystemPair.one)) {
                    allFilterSystemPass = false;
                    break;
                }

                boolean thisFilterSystemPass = false;
                if (searchSystemPair.two <= 0f) {
                    if (filterSystems.get(searchSystemPair.one).contains(system)) thisFilterSystemPass = true;
                } else for (StarSystemAPI filterSystem : filterSystems.get(searchSystemPair.one))
                    if (Misc.getDistanceLY(filterSystem.getLocation(), system.getLocation()) <= searchSystemPair.two) {
                        thisFilterSystemPass = true;
                        break;
                    }

                if (!thisFilterSystemPass) {
                    allFilterSystemPass = false;
                    break;
                }
            }
            if (!allFilterSystemPass) continue;

            if (numStableLocations > 0) if (Misc.getNumStableLocations(system) < numStableLocations) continue;

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
                foundPlanets.add(planet);
            }
        }

        return foundPlanets;
    }
}