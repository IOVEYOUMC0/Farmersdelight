package com.huidu.farmersdelight.manager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Indexed desired states with bounded rotation and immediate invalidation of dispatched work. */
final class ActiveWorkRegistry<K, G, W> {
    record Change<K>(K key, boolean active, boolean reset) { }
    record Selection<K>(K key, long generation) { }
    record Counts(int active, int additions, int removals) { }
    private record State(boolean active, long generation) { }

    private final class Entry {
        K key;
        final G group;
        volatile State desired;
        State committed;

        Entry(K key, G group, State desired) {
            this.key = key;
            this.group = group;
            this.desired = desired;
        }
    }

    private final Function<K, G> grouping;
    private final Function<G, W> worldOf;
    private final Map<K, Entry> entries = new ConcurrentHashMap<>();
    private final Map<G, Set<K>> groups = new HashMap<>();
    private final Map<W, Set<G>> worlds = new HashMap<>();
    private final Map<K, Change<K>> pending = new LinkedHashMap<>();
    private final List<Entry> rotation = new ArrayList<>();
    private final Map<K, Integer> positions = new HashMap<>();
    private long sequence;
    private int cursor;
    private int additions;
    private int removals;

    ActiveWorkRegistry(Function<K, G> grouping, Function<G, W> worldOf) {
        this.grouping = grouping;
        this.worldOf = worldOf;
    }

    synchronized void submit(K key, boolean active) {
        Entry entry = entries.get(key);
        if (entry == null) {
            if (!active) return;
            G group = grouping.apply(key);
            entry = new Entry(key, group, new State(true, ++sequence));
            entries.put(key, entry);
            groups.computeIfAbsent(group, ignored -> new HashSet<>()).add(key);
            worlds.computeIfAbsent(worldOf.apply(group), ignored -> new HashSet<>()).add(group);
        } else {
            if (entry.desired.active() == active) return;
            entry.desired = new State(active, ++sequence);
            if (active) {
                // Equal keys can carry a replacement world/context after unload and reactivation.
                K oldKey = entry.key;
                entry.key = key;
                entries.remove(oldKey);
                entries.put(key, entry);
                Integer position = positions.remove(oldKey);
                if (position != null) positions.put(key, position);
                Set<K> group = groups.get(entry.group);
                group.remove(oldKey);
                group.add(key);
            }
        }
        Change<K> previous = pending.put(key, new Change<>(key, active,
                !active || entry.committed == null || (pending.containsKey(key) && pending.get(key).reset())));
        if (previous != null) {
            if (previous.active()) additions--; else removals--;
        }
        if (active) additions++; else removals++;
    }

    synchronized void retireGroup(G group) {
        Set<K> keys = groups.get(group);
        if (keys != null) for (K key : keys) submit(key, false);
    }

    synchronized void retireWorld(W world) {
        Set<G> values = worlds.get(world);
        if (values != null) for (G group : values) retireGroup(group);
    }

    synchronized List<Change<K>> drain(int budget) {
        List<Change<K>> changes = new ArrayList<>(Math.min(Math.max(0, budget), pending.size()));
        var iterator = pending.values().iterator();
        while (iterator.hasNext() && changes.size() < budget) {
            Change<K> change = iterator.next();
            iterator.remove();
            Entry entry = entries.get(change.key());
            if (change.active()) {
                additions--;
                entry.committed = entry.desired;
                if (!positions.containsKey(entry.key)) {
                    positions.put(entry.key, rotation.size());
                    rotation.add(entry);
                }
            } else {
                removals--;
                removeFromRotation(entry.key);
                entries.remove(entry.key);
                Set<K> group = groups.get(entry.group);
                group.remove(entry.key);
                if (group.isEmpty()) {
                    groups.remove(entry.group);
                    W world = worldOf.apply(entry.group);
                    Set<G> worldGroups = worlds.get(world);
                    worldGroups.remove(entry.group);
                    if (worldGroups.isEmpty()) worlds.remove(world);
                }
            }
            changes.add(change);
        }
        return changes;
    }

    private void removeFromRotation(K key) {
        Integer position = positions.remove(key);
        if (position == null) return;
        Entry last = rotation.remove(rotation.size() - 1);
        if (position < rotation.size()) {
            rotation.set(position, last);
            positions.put(last.key, position);
        }
        if (cursor >= rotation.size()) cursor = 0;
    }

    synchronized List<Selection<K>> select(int budget) {
        int count = Math.min(Math.max(0, budget), rotation.size());
        List<Selection<K>> selected = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Entry entry = rotation.get(cursor);
            selected.add(new Selection<>(entry.key, entry.committed.generation()));
            cursor = (cursor + 1) % rotation.size();
        }
        return selected;
    }

    boolean isCurrent(Selection<K> selection) {
        Entry entry = entries.get(selection.key());
        if (entry == null) return false;
        State state = entry.desired;
        return state.active() && state.generation() == selection.generation();
    }

    synchronized Counts counts() { return new Counts(rotation.size(), additions, removals); }
    synchronized boolean isIdle() { return rotation.isEmpty() && pending.isEmpty(); }

    synchronized void clear() {
        entries.clear();
        groups.clear();
        worlds.clear();
        pending.clear();
        rotation.clear();
        positions.clear();
        cursor = additions = removals = 0;
        // Keep generations unique across a stop/start cycle.
    }
}
