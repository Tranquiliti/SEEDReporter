package org.tranquility.seedreporter;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI.SkillLevelAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.fleets.FleetFactoryV3;
import com.fs.starfarer.api.impl.campaign.fleets.FleetParamsV3;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.FleetTypes;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.procgen.DefenderDataOverride;
import com.fs.starfarer.api.impl.campaign.procgen.SalvageEntityGenDataSpec;
import com.fs.starfarer.api.impl.campaign.procgen.themes.SalvageEntityGeneratorOld;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.SalvageGenFromSeed;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.SleeperPodsSpecial;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.campaign.econ.reach.CommodityMarketData;
import lunalib.lunaSettings.LunaSettings;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.lwjgl.util.vector.Vector2f;
import org.tranquility.seedreporter.filters.ExceptionalOfficerFilter;
import org.tranquility.seedreporter.filters.PlanetFilter;
import org.tranquility.seedreporter.filters.StarSystemFilter;

import java.io.IOException;
import java.util.*;

import static com.fs.starfarer.api.impl.campaign.procgen.themes.SalvageSpecialAssigner.MAX_EXCEPTIONAL_PODS_OFFICERS;
import static com.fs.starfarer.api.ui.MapParams.GRID_SIZE_MAP_UNITS;

public class SEEDReport {
    public static boolean runOnGameStart;

    private final static String REPORT_FILE_NAME = "seedreporter_savedSeeds.json";
    private final static String REPORT_BORDER = "---------------------------------------- %s ----------------------------------------";
    private final static String SECTION_TITLE_BORDER = "\n========== %s ==========\n";
    private static Map<String, StarSystemFilter> starSystemFilterMap;
    private static Map<String, PlanetFilter> planetFilterMap;
    private static ExceptionalOfficerFilter exceptionalFilter;
    private static String tesseractStarSystemFilter;

    private Vector2f centerOfMass;

    static {
        reloadSettings();
    }

    /**
     * Reloads the "seedreporter" field in seedreporterSettings.json
     */
    @SuppressWarnings("unchecked")
    public static void reloadSettings() {
        JSONObject modSettings;
        try {
            modSettings = Global.getSettings().getMergedJSON("data/config/seedreporterConfig/seedreporterSettings.json");
        } catch (IOException | JSONException e) {
            throw new RuntimeException(e);
        }

        if (SEEDUtils.LUNALIB_ENABLED)
            runOnGameStart = Boolean.TRUE.equals(LunaSettings.getBoolean("seedreporter", "runSEEDReportOnGameStart"));
        else runOnGameStart = modSettings.optBoolean("runSEEDReportOnGameStart", true);

        JSONObject starSystemFilterList = modSettings.optJSONObject("starSystemFilters");
        if (starSystemFilterList != null) {
            starSystemFilterMap = new HashMap<>();
            for (Iterator<String> iter = starSystemFilterList.keys(); iter.hasNext(); ) {
                String filterId = iter.next();

                JSONObject starSystemFilterSetting = starSystemFilterList.optJSONObject(filterId);
                if (starSystemFilterSetting == null) continue;

                if (starSystemFilterSetting.optBoolean("isEnabled", true)) {
                    StarSystemFilter newFilter = new StarSystemFilter(starSystemFilterSetting);
                    starSystemFilterMap.put(filterId, newFilter);
                }
            }
        }

        tesseractStarSystemFilter = modSettings.optString("tesseractStarSystemFilter", null);

        JSONObject planetFilterList = modSettings.optJSONObject("planetFilters");
        if (planetFilterList != null) {
            planetFilterMap = new HashMap<>();
            for (Iterator<String> iter = planetFilterList.keys(); iter.hasNext(); ) {
                String filterId = iter.next();

                JSONObject planetFilterSetting = planetFilterList.optJSONObject(filterId);
                if (planetFilterSetting == null) continue;

                // Load all planet filters
                PlanetFilter newFilter = new PlanetFilter(planetFilterSetting);
                planetFilterMap.put(filterId, newFilter);
            }
        }

        JSONObject exceptionalTemplateList = modSettings.optJSONObject("exceptionalOfficerTemplates");
        JSONArray exceptionalSearchArray = modSettings.optJSONArray("exceptionalOfficerSearch");

        // This filter is merely refreshed to avoid creating another object
        if (exceptionalFilter == null) exceptionalFilter = new ExceptionalOfficerFilter();
        exceptionalFilter.refresh(exceptionalTemplateList, exceptionalSearchArray);
    }

    /**
     * Runs a SEED report
     *
     * @return String containing the report output
     */
    public String run() {
        centerOfMass = CommodityMarketData.computeCenterOfMass(null, null);
        
        // Run all planet filters
        Map<String, Map<StarSystemAPI, Set<PlanetAPI>>> planetFilterResults = new HashMap<>();
        for (String filterId : planetFilterMap.keySet()) {
            PlanetFilter planetFilter = planetFilterMap.get(filterId);
            planetFilterResults.put(filterId, planetFilter.run());
        }

        // Run all star system filters (can use planet filter results and other system filter results)
        // Filters are executed in order, so later filters can reference earlier ones via nearSystemFilters
        Map<String, Set<StarSystemAPI>> starSystemListMap = new HashMap<>();
        for (String filterId : starSystemFilterMap.keySet()) {
            StarSystemFilter systemFilter = starSystemFilterMap.get(filterId);
            starSystemListMap.put(filterId, systemFilter.run(centerOfMass, planetFilterResults, starSystemListMap));
        }

        Set<SectorEntityToken> officersInSalvage = ExceptionalOfficerFilter.getExceptionalOfficerSalvage();
        boolean exceptionalFilterPass = exceptionalFilter.run(officersInSalvage);

        StringBuilder print = new StringBuilder();
        print.append(String.format(REPORT_BORDER, Global.getSector().getSeedString()));
        print.append(String.format("\nMarket center of mass: (%.2f, %.2f)\n", centerOfMass.getX() / GRID_SIZE_MAP_UNITS, centerOfMass.getY() / GRID_SIZE_MAP_UNITS));

        // Printing star system filter lists
        StringBuilder filterShorthand = new StringBuilder();
        for (String filterId : starSystemListMap.keySet()) {
            StarSystemFilter systemFilter = starSystemFilterMap.get(filterId);
            print.append(String.format(SECTION_TITLE_BORDER, systemFilter.filterName));

            for (StarSystemAPI system : starSystemListMap.get(filterId)) {
                print.append(String.format("%s - %s\n", getHyperspaceCoordinates(system), system.getName()));
                
                // If this filter specifies planet requirements, show which planets matched
                if ((systemFilter.hasPlanetsRequired != null && !systemFilter.hasPlanetsRequired.isEmpty()) ||
                    (systemFilter.hasPlanetsOneOf != null && !systemFilter.hasPlanetsOneOf.isEmpty()) ||
                    (systemFilter.hasPlanetsOptional != null && !systemFilter.hasPlanetsOptional.isEmpty())) {
                    
                    Set<PlanetAPI> requiredPlanets = new HashSet<>();
                    Set<PlanetAPI> oneOfPlanets = new HashSet<>();
                    Set<PlanetAPI> optionalPlanets = new HashSet<>();
                    
                    // Map planets to their matching filter names
                    Map<PlanetAPI, Set<String>> planetToFilterNames = new HashMap<>();
                    
                    // Collect required planets and their filter names
                    if (systemFilter.hasPlanetsRequired != null) {
                        for (String planetFilterId : systemFilter.hasPlanetsRequired.keySet()) {
                            if (planetFilterResults.containsKey(planetFilterId) && 
                                planetFilterResults.get(planetFilterId).containsKey(system)) {
                                PlanetFilter filter = planetFilterMap.get(planetFilterId);
                                for (PlanetAPI planet : planetFilterResults.get(planetFilterId).get(system)) {
                                    requiredPlanets.add(planet);
                                    planetToFilterNames.computeIfAbsent(planet, k -> new HashSet<>()).add(filter.filterName);
                                }
                            }
                        }
                    }
                    
                    // Collect oneOf planets and their filter names
                    if (systemFilter.hasPlanetsOneOf != null) {
                        for (String planetFilterId : systemFilter.hasPlanetsOneOf) {
                            if (planetFilterResults.containsKey(planetFilterId) && 
                                planetFilterResults.get(planetFilterId).containsKey(system)) {
                                PlanetFilter filter = planetFilterMap.get(planetFilterId);
                                for (PlanetAPI planet : planetFilterResults.get(planetFilterId).get(system)) {
                                    oneOfPlanets.add(planet);
                                    planetToFilterNames.computeIfAbsent(planet, k -> new HashSet<>()).add(filter.filterName);
                                }
                            }
                        }
                    }
                    
                    // Collect optional planets and their filter names
                    if (systemFilter.hasPlanetsOptional != null) {
                        for (String planetFilterId : systemFilter.hasPlanetsOptional) {
                            if (planetFilterResults.containsKey(planetFilterId) && 
                                planetFilterResults.get(planetFilterId).containsKey(system)) {
                                PlanetFilter filter = planetFilterMap.get(planetFilterId);
                                for (PlanetAPI planet : planetFilterResults.get(planetFilterId).get(system)) {
                                    optionalPlanets.add(planet);
                                    planetToFilterNames.computeIfAbsent(planet, k -> new HashSet<>()).add(filter.filterName);
                                }
                            }
                        }
                    }
                    
                    // Print required and oneOf planets (no prefix)
                    Set<PlanetAPI> allMatchingPlanets = new HashSet<>();
                    allMatchingPlanets.addAll(requiredPlanets);
                    allMatchingPlanets.addAll(oneOfPlanets);
                    for (PlanetAPI planet : allMatchingPlanets) {
                        Set<String> filterNames = planetToFilterNames.get(planet);
                        String filterNameStr = filterNames != null ? String.join(", ", filterNames) : "";
                        print.append(String.format("  %.0f%%, %s (%s)\n", 
                            planet.getMarket().getHazardValue() * 100f, 
                            planet.getName(),
                            filterNameStr));
                    }
                    
                    // Print optional planets (with + prefix)
                    for (PlanetAPI planet : optionalPlanets) {
                        if (!allMatchingPlanets.contains(planet)) {  // Don't duplicate if already shown
                            Set<String> filterNames = planetToFilterNames.get(planet);
                            String filterNameStr = filterNames != null ? String.join(", ", filterNames) : "";
                            print.append(String.format("  + %.0f%%, %s (%s)\n", 
                                planet.getMarket().getHazardValue() * 100f, 
                                planet.getName(),
                                filterNameStr));
                        }
                    }
                }
                
                // Show optional proximity features
                if (systemFilter.nearSystemFiltersOptional != null && !systemFilter.nearSystemFiltersOptional.isEmpty()) {
                    for (String systemFilterId : systemFilter.nearSystemFiltersOptional.keySet()) {
                        float maxDistance = systemFilter.nearSystemFiltersOptional.get(systemFilterId);
                        
                        if (starSystemListMap.containsKey(systemFilterId)) {
                            boolean isNear = false;
                            StarSystemAPI nearestSystem = null;
                            float nearestDistance = Float.MAX_VALUE;
                            
                            if (maxDistance <= 0f) {
                                // Check if system IS in the filter
                                if (starSystemListMap.get(systemFilterId).contains(system)) {
                                    isNear = true;
                                }
                            } else {
                                // Check proximity
                                for (StarSystemAPI filterSystem : starSystemListMap.get(systemFilterId)) {
                                    float distance = Misc.getDistanceLY(filterSystem.getLocation(), system.getLocation());
                                    if (distance <= maxDistance && distance < nearestDistance) {
                                        isNear = true;
                                        nearestSystem = filterSystem;
                                        nearestDistance = distance;
                                    }
                                }
                            }
                            
                            if (isNear) {
                                StarSystemFilter optionalFilter = starSystemFilterMap.get(systemFilterId);
                                if (maxDistance <= 0f) {
                                    print.append(String.format("  + Matches '%s'\n", optionalFilter.filterName));
                                } else {
                                    print.append(String.format("  + Within %.1f LY of '%s' (%s)\n", 
                                        nearestDistance, optionalFilter.filterName, nearestSystem.getName()));
                                }
                            }
                        }
                    }
                }
            }

            if (systemFilter.saveShorthand != null && !starSystemListMap.get(filterId).isEmpty()) {
                if (filterShorthand.length() > 0) filterShorthand.append(",");
                filterShorthand.append(systemFilter.saveShorthand);
            }
        }

        // Printing exceptional officers
        StringBuilder exceptionalShorthand = new StringBuilder("[");
        print.append(String.format(SECTION_TITLE_BORDER, "Exceptional officer pod locations"));
        if (officersInSalvage.isEmpty()) print.append("No exceptional officer pods found!\n");
        for (SectorEntityToken entity : officersInSalvage) {
            Object o = entity.getMemoryWithoutUpdate().get(MemFlags.SALVAGE_SPECIAL_DATA);
            PersonAPI officer = ((SleeperPodsSpecial.SleeperPodsSpecialData) o).officer;

            print.append(String.format("%s - %s within %s (%s)\n * ", getHyperspaceCoordinates(entity.getContainingLocation()), officer.getName().getFullName(), entity.getFullName(), entity.getContainingLocation().getName()));

            for (SkillLevelAPI skill : officer.getStats().getSkillsCopy())
                if (skill.getSkill().isCombatOfficerSkill()) {
                    print.append(skill.getSkill().getName()).append(skill.getLevel() > 1f ? "+, " : ", ");
                    exceptionalShorthand.append(skill.getSkill().getName().charAt(0));
                }
            print.replace(print.length() - 2, print.length(), "\n");
            exceptionalShorthand.append(",");
        }
        print.append("\n");
        if (exceptionalShorthand.length() > 1)
            exceptionalShorthand.replace(exceptionalShorthand.length() - 1, exceptionalShorthand.length(), "]");
        else exceptionalShorthand.append("]");

        int exceptionalCount = Global.getSector().getMemoryWithoutUpdate().getInt("$SleeperPodsSpecialCreator_exceptionalCount");
        if (exceptionalCount < MAX_EXCEPTIONAL_PODS_OFFICERS)
            print.append(MAX_EXCEPTIONAL_PODS_OFFICERS - exceptionalCount).append(" more may spawn from newly-generated salvage, like those in distress calls.\n");
        else print.append("No more exceptional officer pods can spawn in this sector!\n");

        // Printing Tesseract variants
        StringBuilder variantShorthand = new StringBuilder();
        boolean sameTesseractVariants = false;
        if (tesseractStarSystemFilter != null) {
            print.append(String.format(SECTION_TITLE_BORDER, "Tesseract locations"));
            Set<String> variants = new HashSet<>();
            for (StarSystemAPI system : starSystemListMap.get(tesseractStarSystemFilter)) {
                print.append(String.format("%s - %s\n", getHyperspaceCoordinates(system), system.getName()));

                for (SectorEntityToken entity : system.getAllEntities()) {
                    if (!entity.hasTag(Tags.CORONAL_TAP)) continue;

                    CampaignFleetAPI defenders = generateSalvageDefenders(entity);
                    if (defenders == null) continue;

                    for (FleetMemberAPI member : defenders.getMembersWithFightersCopy()) {
                        ShipVariantAPI variant = member.getVariant();
                        String variantId = variant.getHullVariantId();
                        variants.add(variantId);
                        print.append(String.format(" * %s (%s)\n", variant.getDisplayName(), variantId));
                        variantShorthand.append(variantId.charAt(variantId.indexOf("_") + 1)).append(variantId.charAt(variantId.length() - 1));
                    }
                }
            }
            if (variants.size() == 1) sameTesseractVariants = true;
        }

        print.append(String.format(REPORT_BORDER, Global.getSector().getSeedString()));

        if (!filterShorthand.isEmpty() || exceptionalFilterPass || sameTesseractVariants) {
            try {
                String seedString = createSeedString(exceptionalFilterPass, sameTesseractVariants, exceptionalShorthand, variantShorthand, filterShorthand);

                JSONObject json;
                if (Global.getSettings().fileExistsInCommon(REPORT_FILE_NAME)) {
                    json = Global.getSettings().readJSONFromCommon(REPORT_FILE_NAME, false);
                    JSONArray versionSeeds = json.getJSONArray(Global.getSettings().getGameVersion());
                    versionSeeds.put(seedString);
                } else {
                    json = new JSONObject();
                    JSONArray versionSeeds = new JSONArray();
                    json.put(Global.getSettings().getGameVersion(), versionSeeds);
                    versionSeeds.put(seedString);
                }
                Global.getSettings().writeJSONToCommon(REPORT_FILE_NAME, json, false);

                if (!filterShorthand.isEmpty())
                    print.append("\nFound a system or planet matching a \"saveShorthand\" filter!");

                if (exceptionalFilterPass)
                    print.append("\nFound a combination of exceptional officers with the specified requirements!");

                if (sameTesseractVariants)
                    print.append("\nFound all Tesseract variants to be identical! Defeat them in-battle to verify their weapon drops!");

                print.append(String.format("\nWrote seed to Starsector/saves/common/%s:\n\"%s\"", REPORT_FILE_NAME, seedString));
            } catch (JSONException | IOException e) {
                print.append("\nFailed to write seed to file!");
                return print.toString();
            }
        }

        return print.toString();
    }

    private String createSeedString(boolean foundExceptional, boolean foundSameTesseract, StringBuilder exceptionalShorthand, StringBuilder variantShorthand, StringBuilder shorthandPrint) {
        String seedString = Global.getSector().getSeedString() + ": ";
        if (foundExceptional) seedString += "{!EO}";
        if (foundSameTesseract) seedString += "{!TV}";
        if (!shorthandPrint.isEmpty()) seedString += "{" + shorthandPrint + "}";
        if (!exceptionalShorthand.isEmpty()) seedString += " " + exceptionalShorthand;
        seedString += " (" + variantShorthand + ")";
        return seedString;
    }

    // From execute() in SalvageGenFromSeed.java
    private CampaignFleetAPI generateSalvageDefenders(SectorEntityToken entity) {
        String specId = entity.getCustomEntityType();
        if (specId == null || entity.getMemoryWithoutUpdate().contains(MemFlags.SALVAGE_SPEC_ID_OVERRIDE))
            specId = entity.getMemoryWithoutUpdate().getString(MemFlags.SALVAGE_SPEC_ID_OVERRIDE);

        SalvageEntityGenDataSpec spec = SalvageEntityGeneratorOld.getSalvageSpec(specId);

        MemoryAPI memory = entity.getMemoryWithoutUpdate();

        long seed = memory.getLong(MemFlags.SALVAGE_SEED);

        // don't use seed directly so that results from different uses of it aren't tied together
        Random random = Misc.getRandom(seed, 0);

        DefenderDataOverride override = null;
        if (memory.contains(MemFlags.SALVAGE_DEFENDER_OVERRIDE))
            override = (DefenderDataOverride) memory.get(MemFlags.SALVAGE_DEFENDER_OVERRIDE);

        Random fleetRandom = Misc.getRandom(seed, 1);
        float strength = spec.getMinStr() + Math.round((spec.getMaxStr() - spec.getMinStr()) * fleetRandom.nextFloat());
        float prob = spec.getProbDefenders();

        if (override != null) {
            strength = override.minStr + Math.round((override.maxStr - override.minStr) * fleetRandom.nextFloat());
            prob = override.probDefenders;
        }

        String factionId = entity.getFaction().getId();
        if (spec.getDefFaction() != null) factionId = spec.getDefFaction();
        if (override != null && override.defFaction != null) factionId = override.defFaction;

        SalvageGenFromSeed.SDMParams p = new SalvageGenFromSeed.SDMParams();
        p.entity = entity;
        p.factionId = factionId;

        SalvageGenFromSeed.SalvageDefenderModificationPlugin plugin = Global.getSector().getGenericPlugins().pickPlugin(SalvageGenFromSeed.SalvageDefenderModificationPlugin.class, p);

        if (plugin != null) {
            strength = plugin.getStrength(p, strength, random, override != null);
            prob = plugin.getProbability(p, prob, random, override != null);
        }

        float probStation = spec.getProbStation();
        if (override != null) probStation = override.probStation;
        String stationRole = null;
        if (fleetRandom.nextFloat() < probStation) {
            stationRole = spec.getStationRole();
            if (override != null && override.stationRole != null) stationRole = override.stationRole;
        }

        // Create fleet
        if (((int) strength > 0 || stationRole != null) && random.nextFloat() < prob && !memory.getBoolean("$defenderFleetDefeated")) {
            float quality = spec.getDefQuality();
            if (plugin != null) quality = plugin.getQuality(p, quality, fleetRandom, override != null);

            FleetParamsV3 fParams = new FleetParamsV3(null, null, factionId, quality, FleetTypes.PATROL_SMALL, (int) strength, 0, 0, 0, 0, 0, 0);
            fParams.random = fleetRandom;
            FactionAPI faction = Global.getSector().getFaction(factionId);
            fParams.withOfficers = faction.getCustomBoolean(Factions.CUSTOM_OFFICERS_ON_AUTOMATED_DEFENSES);

            fParams.maxShipSize = (int) spec.getMaxDefenderSize();
            if (override != null) fParams.maxShipSize = override.maxDefenderSize;

            if (plugin != null)
                fParams.maxShipSize = (int) (plugin.getMaxSize(p, fParams.maxShipSize, random, override != null));

            fParams.minShipSize = (int) spec.getMinDefenderSize();
            if (override != null) fParams.minShipSize = override.minDefenderSize;

            if (plugin != null)
                fParams.minShipSize = (int) (plugin.getMinSize(p, fParams.minShipSize, random, override != null));

            CampaignFleetAPI defenders = FleetFactoryV3.createFleet(fParams);

            if (!defenders.isEmpty()) {
                defenders.getInflater().setRemoveAfterInflating(false);
                defenders.setName("Automated Defenses");

                if (stationRole != null) {
                    defenders.getFaction().pickShipAndAddToFleet(stationRole, FactionAPI.ShipPickParams.all(), defenders, fleetRandom);
                    defenders.getFleetData().sort();
                }

                defenders.clearAbilities();

                if (plugin != null) plugin.modifyFleet(p, defenders, fleetRandom, override != null);

                defenders.getFleetData().sort();
            }

            return defenders;
        }
        return null;
    }

    private String getHyperspaceCoordinates(LocationAPI loc) {
        Vector2f vec = loc.getLocation();
        return String.format("%.2f LY (%.2f, %.2f)", Misc.getDistanceLY(centerOfMass, vec), vec.getX() / GRID_SIZE_MAP_UNITS, vec.getY() / GRID_SIZE_MAP_UNITS);
    }
}
