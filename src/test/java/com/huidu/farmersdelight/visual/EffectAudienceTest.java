package com.huidu.farmersdelight.visual;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class EffectAudienceTest {
    @Test void movementRetainsTheSessionAndPublishesANewSnapshot() {
        var audience = new EffectAudience();
        UUID player = UUID.randomUUID(), world = UUID.randomUUID();
        audience.publish(player, world, 0, 64, 0);
        var before = audience.get(player);
        audience.publish(player, world, 31, 64, 0);
        var after = audience.get(player);
        assertEquals(before.session(), after.session());
        assertEquals(0, before.x());
        assertEquals(31, after.x());
        assertTrue(after.inRange(world, 0, 64, 0, 32 * 32));
        assertFalse(after.inRange(UUID.randomUUID(), 0, 64, 0, 32 * 32));
        audience.publish(player, world, 31, 64, 0);
        assertSame(after, audience.get(player));
        audience.publish(player, UUID.randomUUID(), 31, 64, 0);
        assertNotEquals(after.session(), audience.get(player).session());
    }

    @Test void teleportAndReconnectRejectOldEmissionsEvenAtTheSamePosition() {
        var audience = new EffectAudience();
        UUID player = UUID.randomUUID(), world = UUID.randomUUID();
        audience.publish(player, world, 0, 64, 0);
        var old = audience.get(player);
        var emission = new ParticleDispatcher.Emission(new Object(), world, 0, 64, 0, 1024, old.session(), 1000);
        assertTrue(emission.visible(old, 1001));
        audience.invalidate(player);
        assertFalse(emission.visible(audience.get(player), 1001));
        audience.publish(player, world, 0, 64, 0);
        assertNotEquals(old.session(), audience.get(player).session());
        assertFalse(emission.visible(audience.get(player), 1001));
        audience.clear();
        audience.publish(player, world, 0, 64, 0);
        assertFalse(emission.visible(audience.get(player), 1001));
    }

    @Test void delayedPacketsAlsoCheckCurrentRangeAndWorld() {
        UUID world = UUID.randomUUID();
        var emission = new ParticleDispatcher.Emission(new Object(), world, 0, 64, 0, 1024, 1, 1000);
        assertFalse(emission.visible(new EffectAudience.Position(1, world, 33, 64, 0), 1001));
        assertFalse(emission.visible(new EffectAudience.Position(1, UUID.randomUUID(), 0, 64, 0), 1001));
        var nearby = new EffectAudience.Position(1, world, 32, 64, 0);
        assertTrue(emission.visible(nearby, 1001));
        assertFalse(emission.visible(nearby, 250_001_001));
    }
}
