package org.tranquility.seedreporter.filters;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.util.Misc;
import org.json.JSONArray;
import org.json.JSONObject;
import org.lwjgl.util.vector.Vector2f;
import org.tranquility.seedreporter.SEEDUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class StarSystemFilter {
    public String filterName;
    public String saveShorthand;
    public String systemId;
    public Set<String> avoidTags;
    public Set<String> searchTags;
    public Set<String> entityTags;
    public float distanceFromCOM;
    public int numStableLocations;
    public Set<String> hasPlanets;
    public Map<String, Float> nearSystemFilters;  // System filter ID -> max distance in LY

    public StarSystemFilter(JSONObject settings) {
        filterName = settings.optString("filterName", "Star system filter locations");
        saveShorthand = settings.optString("saveShorthand", null);
        systemId = settings.optString("systemId", null);

        avoidTags = SEEDUtils.convertJSONArrayToSet(settings.optJSONArray("avoidTags"));
        searchTags = SEEDUtils.convertJSONArrayToSet(settings.optJSONArray("hasTags"));
        entityTags = SEEDUtils.convertJSONArrayToSet(settings.optJSONArray("hasEntityWithTags"));

        distanceFromCOM = (float) settings.optDouble("distanceFromCOM", Float.MAX_VALUE);
        numStableLocations = settings.optInt("numStableLocations", 0);
        hasPlanets = SEEDUtils.convertJSONArrayToSet(settings.optJSONArray("hasPlanets"));

        // Parse nearSystemFilters: either ["filterName"] or [["filterName", distance]]
        JSONArray nearArray = settings.optJSONArray("nearSystemFilters");
        if (nearArray != null) {
            nearSystemFilters = new HashMap<>();
            for (int i = 0; i < nearArray.length(); i++) {
                JSONArray pairArray = nearArray.optJSONArray(i);
                if (pairArray == null) {
                    // Simple string format: ["filterName"] means 0 LY (must be in system)
                    nearSystemFilters.put(nearArray.optString(i), 0f);
                } else {
                    // Pair format: [["filterName", distance]]
                    nearSystemFilters.put(pairArray.optString(0), (float) pairArray.optDouble(1));
                }
            }
        }
    }

    public Set<StarSystemAPI> run(Vector2f centerOfMass,
                                   Map<String, Map<StarSystemAPI, Set<PlanetAPI>>> planetFilterResults,
                                   Map<String, Set<StarSystemAPI>> starSystemListMap) {
        Set<StarSystemAPI> foundSystems = new HashSet<>();

        Iterable<StarSystemAPI> systems;
        if (systemId == null) systems = Global.getSector().getStarSystems();
        else {
            StarSystemAPI singleSystem = Global.getSector().getStarSystem(systemId);
            if (singleSystem == null) return foundSystems;

            systems = Collections.singleton(singleSystem);
        }

        for (StarSystemAPI system : systems) {
            // Check distance from center of mass
            if (Misc.getDistanceLY(system.getLocation(), centerOfMass) > distanceFromCOM) continue;

            // Check system tags to avoid
            if (avoidTags != null && !Collections.disjoint(system.getTags(), avoidTags)) continue;

            // Check system must have all specified tags
            if (searchTags != null && !system.getTags().containsAll(searchTags)) continue;

            // Check system must have entity with all specified tags
            if (entityTags != null) {
                boolean foundEntity = false;
                for (SectorEntityToken entity : system.getAllEntities())
                    if (entity.getTags().containsAll(entityTags)) {
                        foundEntity = true;
                        break;
                    }
                if (!foundEntity) continue;
            }

            // Check number of stable locations
            if (numStableLocations > 0 && Misc.getNumStableLocations(system) < numStableLocations) continue;

            // Check proximity to other system filters
            if (nearSystemFilters != null && !nearSystemFilters.isEmpty()) {
                boolean nearAllRequiredSystems = true;
                for (String systemFilterId : nearSystemFilters.keySet()) {
                    float maxDistance = nearSystemFilters.get(systemFilterId);

                    // Check if this system filter exists and has results
                    if (!starSystemListMap.containsKey(systemFilterId)) {
                        nearAllRequiredSystems = false;
                        break;
                    }

                    boolean nearThisFilter = false;
                    if (maxDistance <= 0f) {
                        // Distance 0 means must be IN one of the filter systems
                        if (starSystemListMap.get(systemFilterId).contains(system))
                            nearThisFilter = true;
                    } else {
                        // Check if within maxDistance of ANY system from this filter
                        for (StarSystemAPI filterSystem : starSystemListMap.get(systemFilterId)) {
                            if (Misc.getDistanceLY(filterSystem.getLocation(), system.getLocation()) <= maxDistance) {
                                nearThisFilter = true;
                                break;
                            }
                        }
                    }

                    if (!nearThisFilter) {
                        nearAllRequiredSystems = false;
                        break;
                    }
                }
                if (!nearAllRequiredSystems) continue;
            }

            // Check system contains all required planet types
            if (hasPlanets != null && !hasPlanets.isEmpty()) {
                boolean hasAllRequiredPlanets = true;
                for (String planetFilterId : hasPlanets) {
                    // Check if this planet filter exists and has results for this system
                    if (!planetFilterResults.containsKey(planetFilterId) ||
                        !planetFilterResults.get(planetFilterId).containsKey(system)) {
                        hasAllRequiredPlanets = false;
                        break;
                    }
                }
                if (!hasAllRequiredPlanets) continue;
            }

            foundSystems.add(system);
        }

        return foundSystems;
    }
}
