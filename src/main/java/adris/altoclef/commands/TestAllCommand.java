package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.multiversion.versionedfields.Blocks;
import adris.altoclef.tasks.*;

public class TestAllCommand extends Command {
    public TestAllCommand() {
        super("testall", "test if the code/functions works (In development)");
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) {
        mod.log("This command isn't finished yet");
        finish();
    }
}