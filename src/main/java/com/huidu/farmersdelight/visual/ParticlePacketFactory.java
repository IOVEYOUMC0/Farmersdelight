package com.huidu.farmersdelight.visual;

import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundLevelParticlesPacketProxy;
import org.bukkit.Bukkit;
import org.bukkit.Particle;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Resolves server bindings once; particle registry conversion runs on the emitter's owning thread. */
final class ParticlePacketFactory {
    private final Constructor<?> constructor;
    private final Method convert;
    private final Map<Particle, Object> options = new ConcurrentHashMap<>();

    ParticlePacketFactory() throws ReflectiveOperationException {
        Class<?> server = Bukkit.getServer().getClass();
        Class<?> craftParticle = Class.forName(server.getPackageName() + ".CraftParticle", true, server.getClassLoader());
        convert = craftParticle.getMethod("createParticleParam", Particle.class, Object.class);
        constructor = findConstructor(ClientboundLevelParticlesPacketProxy.CLASS, convert.getReturnType());
    }

    static Constructor<?> findConstructor(Class<?> packet, Class<?> option) throws NoSuchMethodException {
        Class<?>[] tail = {double.class, double.class, double.class, float.class, float.class,
                float.class, float.class, int.class};
        for (int flags : new int[]{2, 1}) {
            Class<?>[] parameters = new Class<?>[1 + flags + tail.length];
            parameters[0] = option;
            for (int i = 1; i <= flags; i++) parameters[i] = boolean.class;
            System.arraycopy(tail, 0, parameters, 1 + flags, tail.length);
            try {
                return packet.getConstructor(parameters);
            } catch (NoSuchMethodException unsupported) {
                // Older protocol versions have only the override-limiter flag.
            }
        }
        throw new NoSuchMethodException("Unsupported particle packet constructor: " + packet.getName());
    }

    Object create(Particle particle, double x, double y, double z, int count,
                  double offsetX, double offsetY, double offsetZ, double speed) throws ReflectiveOperationException {
        if (particle.getDataType() != Void.class) throw new IllegalArgumentException("Particle requires data: " + particle);
        Object option = options.get(particle);
        if (option == null) {
            option = convert.invoke(null, particle, null);
            options.put(particle, option);
        }
        return instantiate(constructor, option, x, y, z, count, offsetX, offsetY, offsetZ, speed);
    }

    static Object instantiate(Constructor<?> constructor, Object option, double x, double y, double z, int count,
                              double offsetX, double offsetY, double offsetZ, double speed) throws ReflectiveOperationException {
        if (constructor.getParameterCount() == 11) {
            return constructor.newInstance(option, false, false, x, y, z,
                    (float) offsetX, (float) offsetY, (float) offsetZ, (float) speed, count);
        }
        return constructor.newInstance(option, false, x, y, z,
                (float) offsetX, (float) offsetY, (float) offsetZ, (float) speed, count);
    }
}
