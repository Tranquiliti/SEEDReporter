package org.tranquility.seedreporter.commands;

import org.lazywizard.console.BaseCommand;
import org.lazywizard.console.CommonStrings;
import org.lazywizard.console.Console;
import org.tranquility.seedreporter.SEEDReport;

public class RunSEEDReport implements BaseCommand {
    @Override
    public CommandResult runCommand(String args, CommandContext context) {
        if (!context.isInCampaign()) {
            Console.showMessage(CommonStrings.ERROR_CAMPAIGN_ONLY);
            return CommandResult.WRONG_CONTEXT;
        }

        SEEDReport.reloadSettings();
        String seedResult = new SEEDReport().run();
        Console.showMessage(seedResult);

        return CommandResult.SUCCESS;
    }
}