package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.util.CraftEngineAdapter;
import com.huidu.farmersdelight.util.SoilRuleSupport;
import com.huidu.farmersdelight.util.SoilRuleSupport.SoilRules;
import com.huidu.farmersdelight.util.BehaviorArgParser;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.core.block.CustomBlock;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehavior;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.properties.Property;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class MushroomColonyBehavior extends BlockBehavior {
    private static final Map<Key, MushroomColonyBehavior> BEHAVIORS = new ConcurrentHashMap<>();

    private static final SoilRules FALLBACK_SOIL_RULES = new SoilRules(
            Set.of(Material.MYCELIUM, Material.MUSHROOM_STEM, Material.PODZOL),
            Set.of(),
            Set.of(),
            List.of(),
            Set.of()
    );

    private final Property<Integer> ageProperty;
    private final int maxAge;
    private final Material mushroomMaterial;
    private final float growSpeed;
    private final int minGrowLight;
    private final int harvestMinDrop;
    private final int harvestMaxDrop;
    private final int resetAge;
    private final List<Key> harvestToolTags;
    private final List<Key> harvestToolItems;
    private final SoilRules soilRules;

    private MushroomColonyBehavior(
            CustomBlock block,
            Property<Integer> ageProperty,
            int maxAge,
            Material mushroomMaterial,
            float growSpeed,
            int minGrowLight,
            int harvestMinDrop,
            int harvestMaxDrop,
            int resetAge,
            List<Key> harvestToolTags,
            List<Key> harvestToolItems,
            SoilRules soilRules
    ) {
        super(block);
        this.ageProperty = ageProperty;
        this.maxAge = maxAge;
        this.mushroomMaterial = mushroomMaterial;
        this.growSpeed = growSpeed;
        this.minGrowLight = minGrowLight;
        this.harvestMinDrop = harvestMinDrop;
        this.harvestMaxDrop = harvestMaxDrop;
        this.resetAge = resetAge;
        this.harvestToolTags = harvestToolTags;
        this.harvestToolItems = harvestToolItems;
        this.soilRules = soilRules;
    }

    @SuppressWarnings("unchecked")
    public static final BlockBehaviorFactory<MushroomColonyBehavior> FACTORY = new BlockBehaviorFactory<MushroomColonyBehavior>() {
        @Override
        public MushroomColonyBehavior create(CustomBlock block, Map<String, Object> arguments) {
            String agePropertyName = BehaviorArgParser.getString(arguments, "age-property", "age");
            Property<Integer> ageProperty = (Property<Integer>) block.getProperty(agePropertyName);
            if (ageProperty == null) {
                ageProperty = (Property<Integer>) block.getProperty("age");
            }

            int maxAge = BehaviorArgParser.hasArgument(arguments, "max-age")
                    ? BehaviorArgParser.getInt(arguments, "max-age", 3)
                    : BehaviorArgParser.inferMaxIntegerValue(ageProperty, 3);
            String mushroomType = BehaviorArgParser.getString(arguments, "mushroom-type", "minecraft:brown_mushroom");
            Material mushroomMaterial = Material.matchMaterial(mushroomType);
            if (mushroomMaterial == null || !mushroomMaterial.isItem()) {
                mushroomMaterial = Material.BROWN_MUSHROOM;
            }
            float growSpeed = BehaviorArgParser.getFloat(arguments, "grow-speed", 0.2f);
            int minGrowLight = BehaviorArgParser.getInt(arguments, "light-requirement", 0);
            int harvestMinDrop = BehaviorArgParser.getInt(arguments, "harvest-min-drop", 1);
            int harvestMaxDrop = BehaviorArgParser.getInt(arguments, "harvest-max-drop", 4);
            int resetAge = BehaviorArgParser.getInt(arguments, "reset-age", 1);
            List<Key> harvestToolTags = parseKeyList(arguments, "harvest-tool-tags");
            List<Key> harvestToolItems = parseKeyList(arguments, "harvest-tool-items");
            SoilRules soilRules = SoilRuleSupport.parseSoilRules(arguments);

            MushroomColonyBehavior behavior = new MushroomColonyBehavior(
                    block,
                    ageProperty,
                    maxAge,
                    mushroomMaterial,
                    growSpeed,
                    minGrowLight,
                    harvestMinDrop,
                    harvestMaxDrop,
                    resetAge,
                    harvestToolTags,
                    harvestToolItems,
                    soilRules
            );
            BEHAVIORS.put(block.id(), behavior);
            return behavior;
        }
    };

    public static MushroomColonyBehavior getBehavior(Key blockId) {
        if (blockId == null) return null;
        return BEHAVIORS.get(blockId);
    }

    public static MushroomColonyBehavior getBehavior(ImmutableBlockState state) {
        if (state == null || state.isEmpty()) return null;
        return getBehavior(state.owner().value().id());
    }

    public int getAge(ImmutableBlockState state) {
        if (ageProperty == null || state == null || state.isEmpty()) return 0;
        Integer value = state.get(ageProperty);
        return value != null ? value : 0;
    }

    @Override
    public InteractionResult useOnBlock(UseOnContext context, ImmutableBlockState state) {
        if (context.getPlayer() == null) return InteractionResult.PASS;
        BlockPos pos = context.getClickedPos();

        Player bukkitPlayer = Bukkit.getPlayer(context.getPlayer().uuid());
        if (bukkitPlayer == null) return InteractionResult.PASS;

        int currentAge = getAge(state);
        if (currentAge < maxAge) return InteractionResult.PASS;

        ItemStack mainHand = bukkitPlayer.getInventory().getItemInMainHand();
        if (mainHand == null || mainHand.getType().isAir()) return InteractionResult.PASS;

        if (!isHarvestTool(mainHand)) return InteractionResult.PASS;

        World world = bukkitPlayer.getWorld();
        Location dropLoc = new Location(world, pos.x() + 0.5, pos.y() + 0.5, pos.z() + 0.5);
        Block bukkitBlock = world.getBlockAt(pos.x(), pos.y(), pos.z());
        org.bukkit.block.data.BlockData blockData = bukkitBlock.getBlockData().clone();

        int dropCount = harvestMinDrop + ThreadLocalRandom.current().nextInt(harvestMaxDrop - harvestMinDrop + 1);
        ItemStack mushroomDrop = new ItemStack(mushroomMaterial, Math.max(1, dropCount));
        world.dropItemNaturally(dropLoc, mushroomDrop);

        if (bukkitPlayer.getGameMode() != org.bukkit.GameMode.CREATIVE) {
            if (mainHand.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable damageable) {
                int maxDurability = mainHand.getType().getMaxDurability();
                int currentDamage = damageable.getDamage();
                if (currentDamage + 1 >= maxDurability) {
                    mainHand.setAmount(0);
                } else {
                    damageable.setDamage(currentDamage + 1);
                    mainHand.setItemMeta(damageable);
                }
            }
        }

        ImmutableBlockState resetState = state.with(ageProperty, resetAge);
        CraftEngineBlocks.place(bukkitBlock.getLocation(), resetState, false);

        world.playSound(dropLoc, org.bukkit.Sound.BLOCK_BEEHIVE_SHEAR, 0.8f, 0.8f);
        world.spawnParticle(org.bukkit.Particle.BLOCK_CRUMBLE, dropLoc, 8, 0.15, 0.15, 0.15, blockData);

        return InteractionResult.SUCCESS_AND_CANCEL;
    }

    @Override
    public void randomTick(Object thisBlock, Object[] args, Callable<Object> superMethod) {
        if (args.length < 3) return;

        ImmutableBlockState state = BlockStateUtils.getOptionalCustomBlockState(args[0]).orElse(null);
        if (state == null || state.isEmpty()) return;

        int currentAge = getAge(state);
        if (currentAge >= maxAge) return;

        if (minGrowLight > 0) {
            World world = CraftEngineAdapter.toWorld(args[1]);
            BlockPos pos = CraftEngineAdapter.toBlockPos(args[2]);
            if (world != null && pos != null) {
                Block bukkitBlock = world.getBlockAt(pos.x(), pos.y(), pos.z());
                if (bukkitBlock.getLightLevel() < minGrowLight) return;
            }
        }

        if (ThreadLocalRandom.current().nextFloat() >= growSpeed) return;

        ImmutableBlockState newState = state.with(ageProperty, currentAge + 1);
        World world = CraftEngineAdapter.toWorld(args[1]);
        BlockPos pos = CraftEngineAdapter.toBlockPos(args[2]);
        if (world != null && pos != null) {
            Block bukkitBlock = world.getBlockAt(pos.x(), pos.y(), pos.z());
            CraftEngineBlocks.place(bukkitBlock.getLocation(), newState, false);
        }
    }

    @Override
    public void placeMultiState(Object thisBlock, Object[] args, Callable<Object> superMethod) throws Exception {
        if (args.length >= 5) {
            World world = CraftEngineAdapter.toWorld(args[0]);
            BlockPos pos = CraftEngineAdapter.toBlockPos(args[1]);
            if (world != null && pos != null) {
                Block blockBelow = world.getBlockAt(pos.x(), pos.y() - 1, pos.z());
                if (!isValidSoil(blockBelow)) {
                    return;
                }
                Block targetBlock = world.getBlockAt(pos.x(), pos.y(), pos.z());
                if (!targetBlock.getType().isAir() && !targetBlock.isLiquid()) {
                    return;
                }
            }
        }
        superMethod.call();
    }

    @Override
    public void onRemove(Object thisBlock, Object[] args, Callable<Object> superMethod) throws Exception {
        superMethod.call();
    }

    boolean isValidSoil(Block block) {
        SoilRules rules = soilRules != null && soilRules.isConfigured() ? soilRules : FALLBACK_SOIL_RULES;
        return SoilRuleSupport.matches(block, rules);
    }

    private boolean isHarvestTool(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;

        boolean hasConfig = !harvestToolTags.isEmpty() || !harvestToolItems.isEmpty();
        if (hasConfig) {
            if (!harvestToolItems.isEmpty()) {
                String customId = com.huidu.farmersdelight.util.ItemUtils.getCustomItemId(item);
                String vanillaId = "minecraft:" + item.getType().name().toLowerCase();
                for (Key itemKey : harvestToolItems) {
                    String keyStr = itemKey.toString();
                    if (keyStr.equals(vanillaId)) return true;
                    if (customId != null && keyStr.equals(customId)) return true;
                }
            }

            if (!harvestToolTags.isEmpty()) {
                String customId = com.huidu.farmersdelight.util.ItemUtils.getCustomItemId(item);
                if (customId != null) {
                    com.huidu.farmersdelight.FarmersDelightPlugin plugin =
                            com.huidu.farmersdelight.FarmersDelightPlugin.getInstance();
                    var customItem = plugin.getCraftEngine().itemManager().getCustomItem(Key.of(customId)).orElse(null);
                    if (customItem != null) {
                        for (Key tag : harvestToolTags) {
                            if (customItem.settings().tags().contains(tag)) return true;
                        }
                    }
                }

                for (Key tag : harvestToolTags) {
                    var vanillaItems = plugin().getCraftEngine().itemManager().vanillaItemIdsByTag(tag);
                    String vanillaId = "minecraft:" + item.getType().name().toLowerCase();
                    if (vanillaItems.stream().anyMatch(k -> k.toString().equals(vanillaId))) return true;
                }
            }
            return false;
        }

        String customId = com.huidu.farmersdelight.util.ItemUtils.getCustomItemId(item);
        if (customId != null) {
            java.util.List<String> configuredKnives = com.huidu.farmersdelight.FarmersDelightPlugin.getInstance()
                    .getConfig()
                    .getStringList("knife-config.items");
            if (configuredKnives.stream().anyMatch(id -> id.equalsIgnoreCase(customId))) {
                return true;
            }
        }

        Material type = item.getType();
        return type.name().endsWith("_SWORD")
                || type.name().endsWith("_HOE")
                || type == Material.SHEARS;
    }

    private com.huidu.farmersdelight.FarmersDelightPlugin plugin() {
        return com.huidu.farmersdelight.FarmersDelightPlugin.getInstance();
    }

    private static List<Key> parseKeyList(Map<String, Object> arguments, String key) {
        if (arguments == null) return List.of();
        Object value = arguments.get(key);
        if (value instanceof Iterable<?> iterable) {
            List<Key> result = new java.util.ArrayList<>();
            for (Object item : iterable) {
                String text = String.valueOf(item).trim();
                if (!text.isEmpty()) {
                    result.add(Key.of(text));
                }
            }
            return result;
        }
        return List.of();
    }
}
