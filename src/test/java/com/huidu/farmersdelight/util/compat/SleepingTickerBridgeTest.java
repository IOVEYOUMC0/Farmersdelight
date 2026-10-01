package com.huidu.farmersdelight.util.compat;

import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.Proxy;
import net.momirealms.craftengine.core.world.BlockPos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleepingTickerBridgeTest {
    @Test
    void nativeSchedulerStopsVisitingSleepingEntriesAndRejoinsOnWake() throws Exception {
        if (!SleepingTickerBridge.isSupported()) {
            assertNull(SleepingTickerBridge.create((w, p, s, c) -> { }));
            return;
        }
        AtomicInteger businessCalls = new AtomicInteger();
        AtomicInteger dispatchCalls = new AtomicInteger();
        SleepingTickerBridge<BlockEntityController> bridge = SleepingTickerBridge.create((w, p, s, c) -> businessCalls.incrementAndGet());
        ClassLoader loader = bridge.ticker().getClass().getClassLoader();
        String packageName = "net.momirealms.craftengine.core.block.entity.tick.";
        Class<?> entryType = Class.forName(packageName + "TickingBlockEntity", true, loader);
        Class<?> schedulerType = Class.forName(packageName + "BlockEntityTickerScheduler", true, loader);
        Object entry = Proxy.newProxyInstance(loader, new Class<?>[]{entryType}, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "tick" -> {
                    dispatchCalls.incrementAndGet();
                    bridge.ticker().tick(null, null, null, null);
                    yield null;
                }
                case "isValid" -> true;
                case "pos" -> new BlockPos(0, 0, 0);
                case "isSleeping" -> bridge.ticker().getClass().getMethod("isSleeping").invoke(bridge.ticker());
                case "setTickingStateListener" -> {
                    bridge.ticker().getClass().getMethod(method.getName(), method.getParameterTypes()).invoke(bridge.ticker(), args);
                    yield null;
                }
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "sleeping-ticker-test";
                default -> throw new UnsupportedOperationException(method.getName());
            };
        });
        Object scheduler = schedulerType.getConstructor().newInstance();
        schedulerType.getMethod("add", entryType).invoke(scheduler, entry);
        schedulerType.getMethod("tick").invoke(scheduler);
        assertEquals(1, businessCalls.get());
        bridge.sleep();
        assertEquals(0, schedulerType.getMethod("activeSize").invoke(scheduler));
        for (int i = 0; i < 100; i++) schedulerType.getMethod("tick").invoke(scheduler);
        assertEquals(1, dispatchCalls.get(), "Sleeping entries leave the active prefix instead of being polled");
        bridge.wakeUp();
        assertEquals(1, schedulerType.getMethod("activeSize").invoke(scheduler));
        schedulerType.getMethod("tick").invoke(scheduler);
        assertEquals(2, dispatchCalls.get());
        assertEquals(2, businessCalls.get());
    }

    @Test
    void usesRealSnapshotSleepTransitionsAndKeepsOlderVersionsOptional() {
        AtomicInteger calls = new AtomicInteger();
        SleepingTickerBridge<BlockEntityController> bridge = SleepingTickerBridge.create((w, p, s, c) -> calls.incrementAndGet());
        if (!SleepingTickerBridge.isSupported()) {
            assertNull(bridge);
            assertEquals(0, calls.get());
            return;
        }

        bridge.ticker().tick(null, null, null, null);
        assertEquals(1, calls.get());
        bridge.sleep();
        bridge.sleep();
        assertTrue(bridge.isSleeping());
        assertEquals(1, bridge.sleepCount());
        bridge.ticker().tick(null, null, null, null);
        assertEquals(1, calls.get());

        bridge.wakeUp();
        bridge.wakeUp();
        assertFalse(bridge.isSleeping());
        assertEquals(1, bridge.wakeCount());
        assertEquals(1, calls.get(), "Waking changes eligibility without executing business code inline");
        bridge.ticker().tick(null, null, null, null);
        assertEquals(2, calls.get());
    }
}
