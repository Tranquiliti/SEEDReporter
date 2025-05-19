package org.tranquility.seedreporter;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketConditionAPI;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI.SkillLevelAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.econ.impl.ItemEffectsRepo;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.SleeperPodsSpecial;
import com.fs.starfarer.api.util.Misc;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.fs.starfarer.api.impl.campaign.econ.impl.Cryorevival.MAX_BONUS_DIST_LY;
import static com.fs.starfarer.api.impl.campaign.procgen.themes.SalvageSpecialAssigner.MAX_EXCEPTIONAL_PODS_OFFICERS;
import static org.tranquility.seedreporter.SEEDUtils.*;

public class SEEDReport {
    private final StringBuilder print = new StringBuilder();
    private boolean sameExceptionalOfficers;
    private boolean noExceptionalOfficers;
    private boolean sameTesseractVariants;
    private String planetSearchSaveSeed;
    private Set<StarSystemAPI> cryosleepers;
    private Set<StarSystemAPI> hypershunts;
    private Set<StarSystemAPI> gates;

    private final static String REPORT_FILE_NAME = "seedreporter_savedSeeds.json";
    private static JSONObject modSettings;

    static {
        reloadSettings();
    }

    /**
     * Reloads the "seedreporter" field in modSettings.json
     */
    public static void reloadSettings() {
        try {
            modSettings = Global.getSettings().getMergedJSON("data/config/modSettings.json").getJSONObject("seedreporter");
        } catch (IOException | JSONException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Runs a SEED report
     * @return String containing the report output
     */
    public String run() {
        print.append("-------------------- ").append(Global.getSector().getSeedString()).append(" --------------------\n");

        StarSystemAPI sentinel = getSentinelSystem();
        StarSystemAPI namelessRock = getNamelessRockSystem();
        Set<StarSystemAPI> motherships = getMothershipSystems();
        Set<StarSystemAPI> remnantStations = getRemnantStationSystems();
        cryosleepers = getCryosleeperSystems();
        hypershunts = getHypershuntSystems();
        gates = getGateSystems();

        if (sentinel != null)
            print.append(String.format("Sentinel found in %s %s\n\n", sentinel.getName(), getHyperspaceCoordinates(sentinel)));

        if (namelessRock != null)
            print.append(String.format("Nameless Rock found %s\n", getHyperspaceCoordinates(namelessRock)));

        print.append("\n=== Domain-era Cryosleeper locations ===\n");
        for (StarSystemAPI system : cryosleepers)
            print.append(String.format("%s %s\n", system.getName(), getHyperspaceCoordinates(system)));

        print.append("\n=== Domain-era Mothership locations ===\n");
        for (StarSystemAPI system : motherships)
            print.append(String.format("%s %s\n", system.getName(), getHyperspaceCoordinates(system)));

        print.append("\n=== Fully-operational Remnant station locations ===\n");
        for (StarSystemAPI system : remnantStations)
            print.append(String.format("%s %s\n", system.getName(), getHyperspaceCoordinates(system)));

        print.append("\n=== Inactive Gate locations ===\n");
        for (StarSystemAPI system : gates) {
            print.append(String.format("%s %s\n", system.getName(), getHyperspaceCoordinates(system)));
            if (cryosleepers.contains(system)) print.append(" - Domain-era Cryosleeper\n");
            if (remnantStations.contains(system)) print.append(" - Remnant Battlestation\n");
            if (hypershunts.contains(system)) print.append(" - Coronal Hypershunt\n");
        }

        print.append("\n=== Planet filter results ===\n");
        JSONArray suitablePlanets = modSettings.optJSONArray("planetsToSearch");
        if (suitablePlanets != null) {
            StringBuilder saveSeed = new StringBuilder();
            for (int i = 0; i < suitablePlanets.length(); i++) {
                JSONObject planetSettings = suitablePlanets.optJSONObject(i);
                if (planetSettings == null || !planetSettings.optBoolean("isEnabled")) continue;

                print.append(String.format("\"%s\":\n", planetSettings.optString("filterName", "Filter #" + (i + 1))));
                List<PlanetAPI> planetsFound = findMatchingPlanets(planetSettings);

                for (PlanetAPI planet : planetsFound) {
                    StarSystemAPI planetSystem = planet.getStarSystem();
                    print.append(String.format(" - %s (Hazard %.0f%%) in %s %s\n", planet.getName(), planet.getMarket().getHazardValue() * 100f, planetSystem, getHyperspaceCoordinates(planetSystem)));
                    if (cryosleepers.contains(planetSystem)) print.append("  * Domain-era Cryosleeper\n");
                    if (remnantStations.contains(planetSystem)) print.append("  * Remnant Battlestation\n");
                    if (hypershunts.contains(planetSystem)) print.append("  * Coronal Hypershunt\n");
                    if (gates.contains(planetSystem)) print.append("  * Inactive Gate\n");
                }

                if (planetSettings.optBoolean("saveSeedIfFound", false) && !planetsFound.isEmpty())
                    saveSeed.append('_').append(i);
            }
            planetSearchSaveSeed = saveSeed.toString();
        }

        print.append("\n=== Exceptional officer locations ===\n");
        String exceptionals = findExceptionalOfficers();

        print.append("\n=== Coronal Hypershunt locations ===\n");
        Set<String> variants = new HashSet<>();
        StringBuilder varString = new StringBuilder();
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
                    print.append(String.format(" - %s (%s)\n", variant.getDisplayName(), variantId));
                    varString.append(variantId.charAt(variantId.indexOf('_') + 1)).append(variantId.charAt(variantId.length() - 1));
                }
            }
        }
        if (variants.size() == 1) sameTesseractVariants = true;
        if (sameTesseractVariants)
            print.append("ALL IDENTICAL TESSERACT VARIANTS! CHECK EACH HYPERSHUNT FOR ACTUAL DROPS!\n");

        print.append("-------------------- ").append(Global.getSector().getSeedString()).append(" --------------------");

        if (sameTesseractVariants || noExceptionalOfficers || sameExceptionalOfficers || !planetSearchSaveSeed.isEmpty()) {
            try {
                JSONObject json;
                if (Global.getSettings().fileExistsInCommon(REPORT_FILE_NAME)) {
                    json = Global.getSettings().readJSONFromCommon(REPORT_FILE_NAME, false);
                    JSONArray versionSeeds = json.getJSONArray(Global.getSettings().getGameVersion());
                    versionSeeds.put(createSeedString(exceptionals, varString));
                    Global.getSettings().writeJSONToCommon(REPORT_FILE_NAME, json, false);
                } else {
                    json = new JSONObject();
                    JSONArray versionSeeds = new JSONArray();
                    json.put(Global.getSettings().getGameVersion(), versionSeeds);
                    versionSeeds.put(createSeedString(exceptionals, varString));
                    Global.getSettings().writeJSONToCommon(REPORT_FILE_NAME, json, false);
                }
                print.append(String.format("\nWrote seed to Starsector/saves/common/%s!", REPORT_FILE_NAME));
            } catch (JSONException | IOException e) {
                print.append("\nFailed to write seed to file!");
                return print.toString();
            }
        }

        return print.toString();
    }

    public List<PlanetAPI> findMatchingPlanets(JSONObject planetSettings) {
        Set<String> planetTypes = convertJSONArray(planetSettings.optJSONArray("matchesPlanetTypes"));
        Set<String> conditionsToMatch = convertJSONArray(planetSettings.optJSONArray("matchesConditions"));
        Set<String> conditionsToAvoid = convertJSONArray(planetSettings.optJSONArray("avoidConditions"));

        Iterable<StarSystemAPI> systems;
        boolean inGateSystem = planetSettings.optBoolean("inGateSystem", false);
        if (inGateSystem) {
            systems = gates;
        } else {
            systems = Global.getSector().getStarSystems();
        }

        int distanceFromCore = planetSettings.optInt("distanceFromCore", -1);
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

            if (distanceFromCore > 0) {
                float dist = Misc.getDistanceLY(CORE_WORLD_CENTER, system.getLocation());
                if (dist > distanceFromCore) continue;
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

                if (planet.getMarket() == null) continue;
                if (planet.getMarket().getHazardValue() > maxHazardValue) continue;

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
            for (int i = 0; i < jsonArray.length(); i++) {
                stringSet.add(jsonArray.getString(i));
            }
            return stringSet;
        } catch (JSONException e) {
            return new HashSet<>();
        }
    }

    private String createSeedString(String s, StringBuilder stringBuilder) {
        String seedString = Global.getSector().getSeedString() + " [";
        if (sameExceptionalOfficers) seedString += "4EO";
        if (noExceptionalOfficers) seedString += "0EO";
        if (sameTesseractVariants) seedString += "STV";
        if (!planetSearchSaveSeed.isEmpty()) seedString += "{F" + planetSearchSaveSeed + "}";
        if (!s.isEmpty()) seedString += " " + s;
        seedString += " (" + stringBuilder + ")";
        return seedString + "]";
    }

    public Set<StarSystemAPI> getCryosleeperSystems() {
        Set<StarSystemAPI> cryosleeperSystems = new HashSet<>();
        for (StarSystemAPI system : Global.getSector().getStarSystems())
            if (system.hasTag(Tags.THEME_DERELICT_CRYOSLEEPER)) cryosleeperSystems.add(system);

        return cryosleeperSystems;
    }

    public Set<StarSystemAPI> getMothershipSystems() {
        Set<StarSystemAPI> mothershipSystems = new HashSet<>();
        for (StarSystemAPI system : Global.getSector().getStarSystems())
            if (system.hasTag(Tags.THEME_DERELICT_MOTHERSHIP)) mothershipSystems.add(system);

        return mothershipSystems;
    }

    public Set<StarSystemAPI> getRemnantStationSystems() {
        Set<StarSystemAPI> systems = new HashSet<>();
        for (StarSystemAPI system : Global.getSector().getStarSystems())
            // See RemnantThemeGenerator.java for how Remnant battlestations spawn
            if (system.hasTag(Tags.THEME_REMNANT_MAIN) && system.hasTag(Tags.THEME_REMNANT_RESURGENT))
                systems.add(system);

        return systems;
    }

    public Set<StarSystemAPI> getHypershuntSystems() {
        Set<StarSystemAPI> hypershuntSystems = new HashSet<>();
        for (StarSystemAPI system : Global.getSector().getStarSystems())
            if (system.hasTag(Tags.HAS_CORONAL_TAP)) hypershuntSystems.add(system);

        return hypershuntSystems;
    }

    public Set<StarSystemAPI> getGateSystems() {
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

    public String findExceptionalOfficers() {
        Set<SectorEntityToken> officerSalvage = getExceptionalOfficerSalvage();

        if (officerSalvage.isEmpty()) {
            print.append("No exceptional pod officers found!\n");
            noExceptionalOfficers = true;
            return "";
        }

        HashSet<List<String>> skillSet = new HashSet<>();
        StringBuilder shorthand = new StringBuilder("[");
        for (SectorEntityToken entity : officerSalvage) {
            Object o = entity.getMemoryWithoutUpdate().get(MemFlags.SALVAGE_SPECIAL_DATA);
            PersonAPI officer = ((SleeperPodsSpecial.SleeperPodsSpecialData) o).officer;

            print.append(String.format("%s found within %s in %s %s:\n", officer.getName().getFullName(), entity.getFullName(), entity.getContainingLocation().getName(), getHyperspaceCoordinates(entity.getContainingLocation())));

            List<String> skillIds = new ArrayList<>(7);
            shorthand.append('(');
            for (SkillLevelAPI skill : officer.getStats().getSkillsCopy())
                if (skill.getSkill().isCombatOfficerSkill()) {
                    skillIds.add(skill.getSkill().getId());
                    shorthand.append(skill.getSkill().getName().charAt(0));
                    print.append(skill.getSkill().getName()).append(skill.getLevel() > 1f ? "*, " : ", ");
                }
            print.replace(print.length() - 2, print.length(), "\n");
            shorthand.append("),");
            skillSet.add(skillIds);
        }
        shorthand.replace(shorthand.length() - 1, shorthand.length(), "]");

        int exceptionalCount = Global.getSector().getMemoryWithoutUpdate().getInt("$SleeperPodsSpecialCreator_exceptionalCount");
        if (exceptionalCount < MAX_EXCEPTIONAL_PODS_OFFICERS) {
            print.append(MAX_EXCEPTIONAL_PODS_OFFICERS - exceptionalCount).append(" more may spawn from newly-generated salvage, like those in distress calls.\n");
        } else {
            print.append("No more exceptional pod officers can spawn in this sector!\n");

            switch (skillSet.size()) {
                case 1:
                    print.append("A FOUR-OF-A-KIND!!!\n");
                    sameExceptionalOfficers = true;
                    break;
                case 2:
                    print.append("A two two-of-a-kind or a three-of-a-kind found!\n");
                    break;
            }
        }

        return shorthand.toString();
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
}
