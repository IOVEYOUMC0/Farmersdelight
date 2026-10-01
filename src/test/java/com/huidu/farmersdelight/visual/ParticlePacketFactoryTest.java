package com.huidu.farmersdelight.visual;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ParticlePacketFactoryTest {
    public record ModernPacket(String option, boolean force, boolean alwaysShow, double x, double y, double z,
                               float ox, float oy, float oz, float speed, int count) { }
    public record OlderPacket(String option, boolean force, double x, double y, double z,
                              float ox, float oy, float oz, float speed, int count) { }

    @Test void modernPacketsPreserveCoordinatesCountAndClientParticleSettings() throws Exception {
        var constructor = ParticlePacketFactory.findConstructor(ModernPacket.class, String.class);
        var packet = (ModernPacket) ParticlePacketFactory.instantiate(constructor, "smoke",
                -32.5, 72.25, 16.125, 7, 0.1, 0.2, 0.3, 0.04);
        assertEquals("smoke", packet.option());
        assertFalse(packet.force());
        assertFalse(packet.alwaysShow());
        assertEquals(-32.5, packet.x());
        assertEquals(72.25, packet.y());
        assertEquals(16.125, packet.z());
        assertEquals(7, packet.count());
        assertEquals(0.1f, packet.ox());
        assertEquals(0.2f, packet.oy());
        assertEquals(0.3f, packet.oz());
        assertEquals(0.04f, packet.speed());
    }

    @Test void olderPacketsKeepDirectedParticleSemantics() throws Exception {
        var constructor = ParticlePacketFactory.findConstructor(OlderPacket.class, String.class);
        var packet = (OlderPacket) ParticlePacketFactory.instantiate(constructor, "flame",
                1, 2, 3, 0, 4, 5, 6, 0.5);
        assertEquals(0, packet.count());
        assertEquals(0.5f, packet.speed());
        assertFalse(packet.force());
    }

    @Test void unknownProtocolsFailAtBindingInsteadOfGuessingParameters() {
        assertThrows(NoSuchMethodException.class, () -> ParticlePacketFactory.findConstructor(Object.class, String.class));
    }
}
