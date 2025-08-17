package org.tranquility.seedreporter;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
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
import com.fs.starfarer.api.util.Misc;
import org.lwjgl.util.vector.Vector2f;

import java.util.Random;

import static com.fs.starfarer.api.impl.campaign.world.NamelessRock.NAMELESS_ROCK_LOCATION_ID;
import static com.fs.starfarer.api.ui.MapParams.GRID_SIZE_MAP_UNITS;

public final class SEEDUtils {
    public static final Vector2f CORE_WORLD_CENTER = new Vector2f(-4531, -5865);

    public static StarSystemAPI getSentinelSystem() {
        for (StarSystemAPI system : Global.getSector().getStarSystems())
            if (system.hasTag(Tags.PK_SYSTEM)) return system;

        return null;
    }

    public static StarSystemAPI getNamelessRockSystem() {
        return Global.getSector().getStarSystem(NAMELESS_ROCK_LOCATION_ID);
    }

    // From execute() in SalvageGenFromSeed.java
    public static CampaignFleetAPI generateSalvageDefenders(SectorEntityToken entity) {
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

    public static String getHyperspaceCoordinates(LocationAPI loc) {
        Vector2f vec = loc.getLocation();
        return String.format("%.2f LY away (%.2f, %.2f)", Misc.getDistanceLY(CORE_WORLD_CENTER, vec), vec.getX() / GRID_SIZE_MAP_UNITS, vec.getY() / GRID_SIZE_MAP_UNITS);
    }
}
