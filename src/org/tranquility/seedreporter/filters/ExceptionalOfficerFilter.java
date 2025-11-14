package org.tranquility.seedreporter.filters;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.SleeperPodsSpecial;
import org.json.JSONArray;
import org.json.JSONObject;
import org.tranquility.seedreporter.SEEDUtils;

import java.util.*;

import static com.fs.starfarer.api.impl.campaign.procgen.themes.SalvageSpecialAssigner.MAX_EXCEPTIONAL_PODS_OFFICERS;

public class ExceptionalOfficerFilter {
    public static Map<String, Set<String>> templateSkillSetMap;
    public static List<Map<String, Integer>> searchTemplateCombinationList;
    public static int minNumExceptionalOfficers = MAX_EXCEPTIONAL_PODS_OFFICERS;

    @SuppressWarnings("unchecked")
    public void refresh(JSONObject listOfTemplates, JSONArray arrayOfCombinations) {
        // Save the exceptional officer templates
        if (listOfTemplates != null) {
            templateSkillSetMap = new HashMap<>();
            for (Iterator<String> iter = listOfTemplates.keys(); iter.hasNext(); ) {
                String templateId = iter.next();
                Set<String> templateSkills = SEEDUtils.convertJSONArrayToSet(listOfTemplates.optJSONArray(templateId));
                if (templateSkills != null) templateSkillSetMap.put(templateId, templateSkills);
            }
        }

        // Save the template combinations that should be saved
        if (arrayOfCombinations != null) {
            searchTemplateCombinationList = new ArrayList<>(9);
            for (int i = 0; i < arrayOfCombinations.length(); i++) {
                JSONArray combinationsToSearch = arrayOfCombinations.optJSONArray(i);
                if (combinationsToSearch == null) continue;

                if (combinationsToSearch.length() < minNumExceptionalOfficers)
                    minNumExceptionalOfficers = combinationsToSearch.length();

                Map<String, Integer> templateCombination = new HashMap<>();
                for (int j = 0; j < combinationsToSearch.length(); j++) {
                    String templateId = combinationsToSearch.optString(j, null);
                    if (templateId == null) continue;

                    templateCombination.merge(templateId, 1, Integer::sum);
                }
                searchTemplateCombinationList.add(templateCombination);
            }
        }
    }

    public boolean run(Set<SectorEntityToken> officersInSalvage) {
        // Skip comparing templates if even the most permissive combination will fail
        if (officersInSalvage.size() < minNumExceptionalOfficers) return false;

        Map<String, Integer> templateCountMap = new HashMap<>();
        for (SectorEntityToken entity : officersInSalvage) {
            Object o = entity.getMemoryWithoutUpdate().get(MemFlags.SALVAGE_SPECIAL_DATA);
            PersonAPI officer = ((SleeperPodsSpecial.SleeperPodsSpecialData) o).officer;

            Set<String> officerSkills = new HashSet<>();
            for (MutableCharacterStatsAPI.SkillLevelAPI skill : officer.getStats().getSkillsCopy())
                officerSkills.add(skill.getSkill().getId());

            // Templates which are a subset of another template may get ignored here - that's fine for now
            for (String templateId : templateSkillSetMap.keySet())
                if (officerSkills.containsAll(templateSkillSetMap.get(templateId))) {
                    templateCountMap.merge(templateId, 1, Integer::sum);
                    break;
                }
        }

        for (Map<String, Integer> templateCombination : searchTemplateCombinationList) {
            int numTemplatesPass = 0;
            for (String templateId : templateCombination.keySet())
                if (templateCountMap.containsKey(templateId) && templateCountMap.get(templateId) >= templateCombination.get(templateId))
                    numTemplatesPass++;

            if (numTemplatesPass == templateCombination.size()) return true;
        }
        return false;
    }

    public static Set<SectorEntityToken> getExceptionalOfficerSalvage() {
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
