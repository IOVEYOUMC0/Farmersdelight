package com.huidu.farmersdelight.util.compat;

import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.block.entity.tick.BlockEntityTicker;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Optional CraftEngine sleeping ticker bindings. All transitions must run on the block's owner. */
public final class SleepingTickerBridge<C extends BlockEntityController> {
    private static final Bindings BINDINGS = resolveBindings();
    private final BlockEntityTicker<C> ticker;
    private volatile boolean sleeping;
    private volatile long sleeps;
    private volatile long wakes;

    private record Bindings(Constructor<?> constructor, Method sleep, Method wake) {
    }

    private SleepingTickerBridge(BlockEntityTicker<C> ticker) {
        this.ticker = ticker;
    }

    private static Bindings resolveBindings() {
        try {
            Class<?> type = Class.forName(
                    "net.momirealms.craftengine.core.block.entity.tick.SleepingBlockEntityTicker",
                    false, BlockEntityTicker.class.getClassLoader());
            return new Bindings(type.getConstructor(BlockEntityTicker.class),
                    type.getMethod("sleep"), type.getMethod("wakeUp"));
        } catch (ClassNotFoundException | NoSuchMethodException unavailable) {
            return null;
        }
    }

    public static boolean isSupported() {
        return BINDINGS != null;
    }

    @SuppressWarnings("unchecked")
    public static <C extends BlockEntityController> SleepingTickerBridge<C> create(BlockEntityTicker<C> delegate) {
        if (BINDINGS == null) return null;
        try {
            return new SleepingTickerBridge<>((BlockEntityTicker<C>) BINDINGS.constructor().newInstance(delegate));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not create CraftEngine sleeping ticker", failure);
        }
    }

    public BlockEntityTicker<C> ticker() { return ticker; }
    public boolean isSleeping() { return sleeping; }
    public long sleepCount() { return sleeps; }
    public long wakeCount() { return wakes; }

    public void sleep() {
        if (!sleeping) {
            invoke(BINDINGS.sleep());
            sleeping = true;
            sleeps++;
        }
    }

    public void wakeUp() {
        if (sleeping) {
            invoke(BINDINGS.wake());
            sleeping = false;
            wakes++;
        }
    }

    private void invoke(Method method) {
        try {
            method.invoke(ticker);
        } catch (IllegalAccessException | InvocationTargetException failure) {
            throw new IllegalStateException("CraftEngine ticker transition failed: " + method.getName(), failure);
        }
    }
}
