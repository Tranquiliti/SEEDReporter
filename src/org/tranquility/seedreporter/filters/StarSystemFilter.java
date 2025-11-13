package org.tranquility.seedreporter.filters;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import org.json.JSONObject;
import org.tranquility.seedreporter.SEEDUtils;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class StarSystemFilter {
    public String filterName;
    public String saveShorthand;
    public String systemId;
    public Set<String> avoidTags;
    public Set<String> searchTags;
    public Set<String> entityTags;

    public StarSystemFilter(JSONObject settings) {
        filterName = settings.optString("filterName", "Star system filter locations");
        saveShorthand = settings.optString("saveShorthand", null);
        systemId = settings.optString("systemId", null);

        avoidTags = SEEDUtils.convertJSONArrayToSet(settings.optJSONArray("avoidTags"));
        searchTags = SEEDUtils.convertJSONArrayToSet(settings.optJSONArray("hasTags"));
        entityTags = SEEDUtils.convertJSONArrayToSet(settings.optJSONArray("hasEntityWithTags"));
    }

    public Set<StarSystemAPI> run() {
        Set<StarSystemAPI> foundSystems = new HashSet<>();

        Iterable<StarSystemAPI> systems;
        if (systemId == null) systems = Global.getSector().getStarSystems();
        else {
            StarSystemAPI singleSystem = Global.getSector().getStarSystem(systemId);
            if (singleSystem == null) return foundSystems;

            systems = Collections.singleton(singleSystem);
        }

        for (StarSystemAPI system : systems) {
            if (avoidTags != null) if (!Collections.disjoint(system.getTags(), avoidTags)) continue;

            if (searchTags != null && !system.getTags().containsAll(searchTags)) continue;

            if (entityTags != null) {
                boolean foundEntity = false;
                for (SectorEntityToken entity : system.getAllEntities())
                    if (entity.getTags().containsAll(entityTags)) {
                        foundEntity = true;
                        break;
                    }
                if (!foundEntity) continue;
            }

            foundSystems.add(system);
        }

        return foundSystems;
    }
}
