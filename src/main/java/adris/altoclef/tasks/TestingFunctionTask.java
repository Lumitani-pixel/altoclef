package adris.altoclef.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.control.InputControls;
import adris.altoclef.tasks.construction.PlaceStructureBlockTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.time.TimerGame;
import baritone.api.utils.input.Input;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.state.property.Properties;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

public class TestingFunctionTask extends Task {
    private final TimerGame wait = new TimerGame(1);
    private boolean keyReset = false;
    private boolean finished = false;
    private List<BlockPos> positions = null;
    private List<Direction> directions = null;
    private Direction.Axis axis = null;

    @Override
    protected void onStart() {
        AltoClef.getInstance().getClientBaritone().getInputOverrideHandler().clearAllKeys();
        wait.reset();
    }

    @Override
    protected Task onTick() {
       return null;
    }

    @Override
    protected void onStop(Task interruptTask) {

    }

    @Override
    protected boolean isEqual(Task other) {
        return true;
    }

    @Override
    protected String toDebugString() {
        return "Testing all (almost all) functions";
    }

    @Override
    public boolean isFinished() {
        return finished;
    }
}