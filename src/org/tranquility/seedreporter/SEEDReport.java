package org.tranquility.seedreporter;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.econ.MarketConditionAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI.SkillLevelAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.econ.impl.ItemEffectsRepo;
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

import java.io.IOException;
import java.util.*;

import static com.fs.starfarer.api.impl.campaign.econ.impl.Cryorevival.MAX_BONUS_DIST_LY;
import static com.fs.starfarer.api.impl.campaign.procgen.themes.SalvageSpecialAssigner.MAX_EXCEPTIONAL_PODS_OFFICERS;
import static com.fs.starfarer.api.impl.campaign.world.NamelessRock.NAMELESS_ROCK_LOCATION_ID;
import static com.fs.starfarer.api.ui.MapParams.GRID_SIZE_MAP_UNITS;
import static org.tranquility.seedreporter.SEEDUtils.LUNALIB_ENABLED;

public class SEEDReport {
    public static boolean runOnGameStart;

    private final static String REPORT_FILE_NAME = "seedreporter_savedSeeds.json";
    private final static String REPORT_BORDER = "---------------------------------------- %s ----------------------------------------";
    private final static String SECTION_TITLE_BORDER = "\n========== %s ==========\n";
    private static JSONObject modSettings;
    private static Map<String, Set<String>> templateSkillMap;
    private static List<Map<String, Integer>> templateCombinationsToSearch;
    private static int minNumExceptionalOfficers = MAX_EXCEPTIONAL_PODS_OFFICERS;

    private final StringBuilder print = new StringBuilder();
    private Vector2f centerOfMass;
    private boolean foundExceptionalOfficerCombination;
    private boolean sameTesseractVariants;
    private String planetFilterSaveSeed;
    private Set<StarSystemAPI> cryosleepers;
    private Set<StarSystemAPI> hypershunts;
    private Set<StarSystemAPI> gates;

    static {
        reloadSettings();
    }

    /**
     * Reloads the "seedreporter" field in seedreporterSettings.json
     */
    @SuppressWarnings("unchecked")
    public static void reloadSettings() {
        try {
            modSettings = Global.getSettings().getMergedJSON("data/config/seedreporterConfig/seedreporterSettings.json");
        } catch (IOException | JSONException e) {
            throw new RuntimeException(e);
        }

        if (LUNALIB_ENABLED)
            runOnGameStart = Boolean.TRUE.equals(LunaSettings.getBoolean("seedreporter", "runSEEDReportOnGameStart"));
        else runOnGameStart = modSettings.optBoolean("runSEEDReportOnGameStart", true);

        // Save the exceptional officer templates
        JSONObject templateList = modSettings.optJSONObject("exceptionalOfficerTemplates");
        if (templateList != null) {
            templateSkillMap = new HashMap<>();
            for (Iterator<String> iter = templateList.keys(); iter.hasNext(); ) {
                String templateName = iter.next();

                JSONArray skillListToRead = templateList.optJSONArray(templateName);
                if (skillListToRead == null) continue;

                Set<String> templateSkills = new HashSet<>();
                for (int i = 0; i < skillListToRead.length(); i++) {
                    String skillId = skillListToRead.optString(i, null);
                    if (skillId == null) continue;
                    templateSkills.add(skillId);
                }

                templateSkillMap.put(templateName, templateSkills);
            }
        }
        modSettings.remove("exceptionalOfficerTemplates"); // No longer needed once it has been read

        // Save the template combinations that should be saved
        JSONArray searchList = modSettings.optJSONArray("exceptionalOfficerSearch");
        if (searchList != null) {
            templateCombinationsToSearch = new ArrayList<>(9);
            for (int i = 0; i < searchList.length(); i++) {
                JSONArray combinationsToSearch = searchList.optJSONArray(i);
                if (combinationsToSearch == null) continue;

                if (combinationsToSearch.length() < minNumExceptionalOfficers)
                    minNumExceptionalOfficers = combinationsToSearch.length();

                Map<String, Integer> templateCombination = new HashMap<>();
                for (int j = 0; j < combinationsToSearch.length(); j++) {
                    String templateId = combinationsToSearch.optString(j, null);
                    if (templateId == null) continue;

                    templateCombination.merge(templateId, 1, Integer::sum);
                }
                templateCombinationsToSearch.add(templateCombination);
            }
        }
        modSettings.remove("exceptionalOfficerSearch");
    }

    /**
     * Runs a SEED report
     *
     * @return String containing the report output
     */
    public String run() {
        centerOfMass = CommodityMarketData.computeCenterOfMass(null, null);
        StarSystemAPI sentinel = getSentinelSystem();
        StarSystemAPI namelessRock = getNamelessRockSystem();
        Set<StarSystemAPI> motherships = getMothershipSystems();
        Set<StarSystemAPI> remnantStations = getRemnantStationSystems();
        cryosleepers = getCryosleeperSystems();
        hypershunts = getHypershuntSystems();
        gates = getGateSystems();

        print.append(String.format(REPORT_BORDER, Global.getSector().getSeedString())).append("\n");

        if (sentinel != null)
            print.append(String.format("Sentinel found in %s %s\n\n", sentinel.getName(), getHyperspaceCoordinates(sentinel)));

        if (namelessRock != null)
            print.append(String.format("Nameless Rock found %s\n", getHyperspaceCoordinates(namelessRock)));

        print.append(String.format(SECTION_TITLE_BORDER, "Domain-era Cryosleeper locations"));
        for (StarSystemAPI system : cryosleepers)
            print.append(String.format("%s %s\n", system.getName(), getHyperspaceCoordinates(system)));

        print.append(String.format(SECTION_TITLE_BORDER, "Domain-era Mothership locations"));
        for (StarSystemAPI system : motherships)
            print.append(String.format("%s %s\n", system.getName(), getHyperspaceCoordinates(system)));

        print.append(String.format(SECTION_TITLE_BORDER, "Fully-operational Remnant station locations"));
        for (StarSystemAPI system : remnantStations)
            print.append(String.format("%s %s\n", system.getName(), getHyperspaceCoordinates(system)));

        print.append(String.format(SECTION_TITLE_BORDER, "Inactive Gate locations"));
        for (StarSystemAPI system : gates) {
            print.append(String.format("%s %s\n", system.getName(), getHyperspaceCoordinates(system)));
            if (cryosleepers.contains(system)) print.append(" * Domain-era Cryosleeper\n");
            if (remnantStations.contains(system)) print.append(" * Remnant Battlestation\n");
            if (hypershunts.contains(system)) print.append(" * Coronal Hypershunt\n");
        }

        print.append(String.format(SECTION_TITLE_BORDER, "Planet filter results"));
        JSONArray suitablePlanets = modSettings.optJSONArray("planetFilters");
        if (suitablePlanets != null) {
            StringBuilder saveSeed = new StringBuilder();
            for (int i = 0; i < suitablePlanets.length(); i++) {
                JSONObject planetSettings = suitablePlanets.optJSONObject(i);
                if (planetSettings == null || !planetSettings.optBoolean("isEnabled")) continue;

                print.append(String.format("\"%s\":\n", planetSettings.optString("filterName", "Filter #" + (i + 1))));
                List<PlanetAPI> planetsFound = findMatchingPlanets(planetSettings);

                for (PlanetAPI planet : planetsFound) {
                    StarSystemAPI planetSystem = planet.getStarSystem();
                    print.append(String.format(" * %s (Hazard %.0f%%) in %s %s\n", planet.getName(), planet.getMarket().getHazardValue() * 100f, planetSystem, getHyperspaceCoordinates(planetSystem)));
                    if (cryosleepers.contains(planetSystem)) print.append("  - Domain-era Cryosleeper\n");
                    if (remnantStations.contains(planetSystem)) print.append("  - Remnant Battlestation\n");
                    if (hypershunts.contains(planetSystem)) print.append("  - Coronal Hypershunt\n");
                    if (gates.contains(planetSystem)) print.append("  - Inactive Gate\n");
                }

                if (planetSettings.optBoolean("saveSeedIfFound", false) && !planetsFound.isEmpty())
                    saveSeed.append("_").append(i);
            }
            planetFilterSaveSeed = saveSeed.toString();
        }

        print.append(String.format(SECTION_TITLE_BORDER, "Exceptional officer pod locations"));
        String exceptionalOfficers = findExceptionalOfficers();

        print.append(String.format(SECTION_TITLE_BORDER, "Coronal Hypershunt locations"));
        Set<String> variants = new HashSet<>();
        StringBuilder variantShorthand = new StringBuilder();
        for (StarSystemAPI system : hypershunts) {
            print.append(String.format("%s %s:\n", system.getName(), getHyperspaceCoordinates(system)));

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

        print.append(String.format(REPORT_BORDER, Global.getSector().getSeedString()));

        if (sameTesseractVariants || foundExceptionalOfficerCombination || !planetFilterSaveSeed.isEmpty()) {
            try {
                JSONObject json;
                if (Global.getSettings().fileExistsInCommon(REPORT_FILE_NAME)) {
                    json = Global.getSettings().readJSONFromCommon(REPORT_FILE_NAME, false);
                    JSONArray versionSeeds = json.getJSONArray(Global.getSettings().getGameVersion());
                    versionSeeds.put(createSeedString(exceptionalOfficers, variantShorthand));
                    Global.getSettings().writeJSONToCommon(REPORT_FILE_NAME, json, false);
                } else {
                    json = new JSONObject();
                    JSONArray versionSeeds = new JSONArray();
                    json.put(Global.getSettings().getGameVersion(), versionSeeds);
                    versionSeeds.put(createSeedString(exceptionalOfficers, variantShorthand));
                    Global.getSettings().writeJSONToCommon(REPORT_FILE_NAME, json, false);
                }

                if (sameTesseractVariants)
                    print.append("\nFound all Tesseract variants to be identical! Defeat them in-battle to verify their weapon drops!");

                if (!planetFilterSaveSeed.isEmpty())
                    print.append("\nFound a planet matching one of the save-seed filters!");

                if (foundExceptionalOfficerCombination)
                    print.append("\nFound a combination of exceptional officers with the specified requirements!");

                print.append(String.format("\nWrote seed to Starsector/saves/common/%s!", REPORT_FILE_NAME));
            } catch (JSONException | IOException e) {
                print.append("\nFailed to write seed to file!");
                return print.toString();
            }
        }

        return print.toString();
    }

    private List<PlanetAPI> findMatchingPlanets(JSONObject planetSettings) {
        Set<String> planetTypes = convertJSONArray(planetSettings.optJSONArray("matchesPlanetTypes"));
        Set<String> conditionsToMatch = convertJSONArray(planetSettings.optJSONArray("matchesConditions"));
        Set<String> conditionsToAvoid = convertJSONArray(planetSettings.optJSONArray("avoidConditions"));

        Iterable<StarSystemAPI> systems;
        boolean inGateSystem = planetSettings.optBoolean("inGateSystem", false);
        if (inGateSystem) systems = gates;
        else systems = Global.getSector().getStarSystems();

        int distanceFromCOM = planetSettings.optInt("distanceFromCOM", -1);
        boolean inCoronalHypershuntRange = planetSettings.optBoolean("inCoronalHypershuntRange", false);
        boolean inDomainCryosleeperRange = planetSettings.optBoolean("inDomainCryosleeperRange", false);
        int numStableLocations = planetSettings.optInt("numStableLocations", 0);
        float maxHazardValue = planetSettings.optInt("maxHazardValue", Integer.MAX_VALUE) / 100f;
        int matchesAtLeast = planetSettings.optInt("matchesAtLeast", conditionsToMatch.size());

        // Only check conditions if specified; otherwise, do not check them to slightly speed up search
        boolean checkConditions = !conditionsToMatch.isEmpty() || !conditionsToAvoid.isEmpty();

        List<PlanetAPI> matchingPlanets = new ArrayList<>();
        for (StarSystemAPI system : systems) {
            if (system.hasTag(Tags.THEME_HIDDEN) || system.hasTag(Tags.THEME_CORE) || system.hasTag(Tags.SYSTEM_ABYSSAL))
                continue;

            if (distanceFromCOM > 0) {
                float dist = Misc.getDistanceLY(system.getLocation(), centerOfMass);
                if (dist > distanceFromCOM) continue;
            }

            if (inDomainCryosleeperRange) {
                boolean isInRange = false;
                for (StarSystemAPI cryosleeperSystem : cryosleepers) {
                    float dist = Misc.getDistanceLY(cryosleeperSystem.getLocation(), system.getLocation());
                    if (dist <= MAX_BONUS_DIST_LY) {
                        isInRange = true;
                        break;
                    }
                }
                if (!isInRange) continue;
            }

            if (inCoronalHypershuntRange) {
                boolean isInRange = false;
                for (StarSystemAPI hypershuntSystem : hypershunts) {
                    float dist = Misc.getDistanceLY(hypershuntSystem.getLocation(), system.getLocation());
                    if (dist <= ItemEffectsRepo.CORONAL_TAP_LIGHT_YEARS) {
                        isInRange = true;
                        break;
                    }
                }
                if (!isInRange) continue;
            }

            if (numStableLocations > 0) if (Misc.getNumStableLocations(system) < numStableLocations) continue;

            for (PlanetAPI planet : system.getPlanets()) {
                if (!planetTypes.isEmpty() && !planetTypes.contains(planet.getTypeId())) continue;
                if (planet.getMarket() == null || planet.getMarket().getHazardValue() > maxHazardValue) continue;

                if (checkConditions) {
                    int numMatches = 0;
                    boolean hasAvoidCondition = false;
                    for (MarketConditionAPI condition : planet.getMarket().getConditions()) {
                        String conditionId = condition.getId();
                        if (conditionsToAvoid.contains(conditionId)) {
                            hasAvoidCondition = true;
                            break;
                        }
                        if (conditionsToMatch.contains(conditionId)) numMatches++;
                    }
                    if (hasAvoidCondition || numMatches < matchesAtLeast) continue;
                }

                matchingPlanets.add(planet);
            }
        }

        return matchingPlanets;
    }

    private Set<String> convertJSONArray(JSONArray jsonArray) {
        Set<String> stringSet = new HashSet<>();
        if (jsonArray == null || jsonArray.length() == 0) return stringSet;

        try {
            for (int i = 0; i < jsonArray.length(); i++)
                stringSet.add(jsonArray.getString(i));
            return stringSet;
        } catch (JSONException e) {
            return new HashSet<>();
        }
    }

    private String createSeedString(String s, StringBuilder variantShorthand) {
        String seedString = Global.getSector().getSeedString() + ": ";
        if (foundExceptionalOfficerCombination) seedString += "!EO";
        if (sameTesseractVariants) seedString += "STV";
        if (!planetFilterSaveSeed.isEmpty()) seedString += "{F" + planetFilterSaveSeed + "}";
        if (!s.isEmpty()) seedString += " " + s;
        seedString += " (" + variantShorthand + ")";
        return seedString;
    }

    private StarSystemAPI getSentinelSystem() {
        for (StarSystemAPI system : Global.getSector().getStarSystems())
            if (system.hasTag(Tags.PK_SYSTEM)) return system;

        return null;
    }

    private StarSystemAPI getNamelessRockSystem() {
        return Global.getSector().getStarSystem(NAMELESS_ROCK_LOCATION_ID);
    }

    private Set<StarSystemAPI> getCryosleeperSystems() {
        Set<StarSystemAPI> cryosleeperSystems = new HashSet<>();
        for (StarSystemAPI system : Global.getSector().getStarSystems())
            if (system.hasTag(Tags.THEME_DERELICT_CRYOSLEEPER)) cryosleeperSystems.add(system);

        return cryosleeperSystems;
    }

    private Set<StarSystemAPI> getMothershipSystems() {
        Set<StarSystemAPI> mothershipSystems = new HashSet<>();
        for (StarSystemAPI system : Global.getSector().getStarSystems())
            if (system.hasTag(Tags.THEME_DERELICT_MOTHERSHIP)) mothershipSystems.add(system);

        return mothershipSystems;
    }

    private Set<StarSystemAPI> getRemnantStationSystems() {
        Set<StarSystemAPI> systems = new HashSet<>();
        for (StarSystemAPI system : Global.getSector().getStarSystems())
            // See RemnantThemeGenerator.java for how Remnant battlestations spawn
            if (system.hasTag(Tags.THEME_REMNANT_MAIN) && system.hasTag(Tags.THEME_REMNANT_RESURGENT))
                systems.add(system);

        return systems;
    }

    private Set<StarSystemAPI> getHypershuntSystems() {
        Set<StarSystemAPI> hypershuntSystems = new HashSet<>();
        for (StarSystemAPI system : Global.getSector().getStarSystems())
            if (system.hasTag(Tags.HAS_CORONAL_TAP)) hypershuntSystems.add(system);

        return hypershuntSystems;
    }

    private Set<StarSystemAPI> getGateSystems() {
        Set<StarSystemAPI> gates = new HashSet<>();
        for (StarSystemAPI system : Global.getSector().getStarSystems()) {
            if (system.hasTag(Tags.THEME_HIDDEN) || system.hasTag(Tags.THEME_CORE) || system.hasTag(Tags.SYSTEM_ABYSSAL))
                continue;
            for (SectorEntityToken entity : system.getAllEntities())
                if (entity.hasTag(Tags.GATE)) {
                    gates.add(system);
                    break;
                }
        }

        return gates;
    }

    private String findExceptionalOfficers() {
        Set<SectorEntityToken> officerSalvage = getExceptionalOfficerSalvage();

        String result;
        if (officerSalvage.isEmpty()) {
            print.append("No exceptional officer pods found!\n");
            result = "";
        } else result = getExceptionalOfficers(officerSalvage);

        int exceptionalCount = Global.getSector().getMemoryWithoutUpdate().getInt("$SleeperPodsSpecialCreator_exceptionalCount");
        if (exceptionalCount < MAX_EXCEPTIONAL_PODS_OFFICERS)
            print.append(MAX_EXCEPTIONAL_PODS_OFFICERS - exceptionalCount).append(" more may spawn from newly-generated salvage, like those in distress calls.\n");
        else print.append("No more exceptional officer pods can spawn in this sector!\n");

        return result;
    }

    private Set<SectorEntityToken> getExceptionalOfficerSalvage() {
        Set<SectorEntityToken> salvage = new HashSet<>();
        for (StarSystemAPI system : Global.getSector().getStarSystems()) {
            for (SectorEntityToken entity : system.getAllEntities()) {
                Object o = entity.getMemoryWithoutUpdate().get(MemFlags.SALVAGE_SPECIAL_DATA);
                if (!(o instanceof SleeperPodsSpecial.SleeperPodsSpecialData)) continue;

                PersonAPI officer = ((SleeperPodsSpecial.SleeperPodsSpecialData) o).officer;
                if (officer != null && officer.getMemoryWithoutUpdate().getBoolean(MemFlags.EXCEPTIONAL_SLEEPER_POD_OFFICER))
                    salvage.add(entity);
            }
        }

        return salvage;
    }

    private String getExceptionalOfficers(Set<SectorEntityToken> officersInSalvage) {
        StringBuilder shorthand = new StringBuilder("[");

        // Skip comparing templates if even the most permissive combination will fail
        boolean checkTemplates = officersInSalvage.size() >= minNumExceptionalOfficers;

        Map<String, Integer> templatesInSalvage = new HashMap<>();
        for (SectorEntityToken entity : officersInSalvage) {
            Object o = entity.getMemoryWithoutUpdate().get(MemFlags.SALVAGE_SPECIAL_DATA);
            PersonAPI officer = ((SleeperPodsSpecial.SleeperPodsSpecialData) o).officer;

            print.append(String.format("%s found within %s in %s %s:\n * ", officer.getName().getFullName(), entity.getFullName(), entity.getContainingLocation().getName(), getHyperspaceCoordinates(entity.getContainingLocation())));

            Set<String> officerSkills = new HashSet<>();
            shorthand.append("(");
            for (SkillLevelAPI skill : officer.getStats().getSkillsCopy())
                if (skill.getSkill().isCombatOfficerSkill()) {
                    officerSkills.add(skill.getSkill().getId());
                    print.append(skill.getSkill().getName()).append(skill.getLevel() > 1f ? "+, " : ", ");
                    shorthand.append(skill.getSkill().getName().charAt(0));
                }
            print.replace(print.length() - 2, print.length(), "\n");
            shorthand.append("),");

            // Templates which are a subset of another template may get ignored here - that's fine for now
            if (checkTemplates) for (String templateId : templateSkillMap.keySet()) {
                Set<String> difference = new HashSet<>(templateSkillMap.get(templateId));
                difference.removeAll(officerSkills);
                if (difference.isEmpty()) {
                    templatesInSalvage.merge(templateId, 1, Integer::sum);
                    break;
                }
            }
        }
        print.append("\n");
        shorthand.replace(shorthand.length() - 1, shorthand.length(), "]");

        if (checkTemplates) for (Map<String, Integer> templateCombination : templateCombinationsToSearch) {
            int numTemplatesPass = 0;
            for (String templateId : templateCombination.keySet())
                if (templatesInSalvage.containsKey(templateId) && templatesInSalvage.get(templateId) >= templateCombination.get(templateId))
                    numTemplatesPass++;

            if (numTemplatesPass == templateCombination.size()) {
                foundExceptionalOfficerCombination = true;
                break;
            }
        }

        return shorthand.toString();
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
        return String.format("%.2f LY away (%.2f, %.2f)", Misc.getDistanceLY(centerOfMass, vec), vec.getX() / GRID_SIZE_MAP_UNITS, vec.getY() / GRID_SIZE_MAP_UNITS);
    }
}