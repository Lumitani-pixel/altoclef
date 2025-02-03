package adris.altoclef.chains;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import adris.altoclef.chains.FoodChain;
import adris.altoclef.AltoClef;

class FoodChainTest {
    private AltoClef mod;

    @Test
    void testForStartingEating() {
        if (mod.getFoodChain().needsToEat()) {
            mod.getFoodChain();
        }
    }

    @Test
    void needsToEatCritical() {
    }

    @Test
    void hasFood() {
    }

    @Test
    void isShouldStop() {
    }
}