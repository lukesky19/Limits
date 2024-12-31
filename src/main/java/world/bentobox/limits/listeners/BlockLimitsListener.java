package world.bentobox.limits.listeners;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import dev.rosewood.rosestacker.api.RoseStackerAPI;
import dev.rosewood.rosestacker.event.BlockStackEvent;
import dev.rosewood.rosestacker.event.BlockUnstackEvent;
import dev.rosewood.rosestacker.event.SpawnerStackEvent;
import dev.rosewood.rosestacker.event.SpawnerUnstackEvent;
import dev.rosewood.rosestacker.stack.StackedBlock;
import dev.rosewood.rosestacker.stack.StackedSpawner;
import dev.rosewood.rosestacker.utils.ItemUtils;
import org.bukkit.*;
import org.bukkit.World.Environment;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.TechnicalPiston;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.EntityBlockFormEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;

import com.google.common.base.Enums;

import world.bentobox.bentobox.api.events.island.IslandDeleteEvent;
import world.bentobox.bentobox.api.localization.TextVariables;
import world.bentobox.bentobox.api.user.User;
import world.bentobox.bentobox.database.Database;
import world.bentobox.bentobox.database.objects.Island;
import world.bentobox.bentobox.util.Util;
import world.bentobox.limits.Limits;
import world.bentobox.limits.Settings;
import world.bentobox.limits.objects.IslandBlockCount;

/**
 * @author tastybento
 */
public class BlockLimitsListener implements Listener {

    /** Blocks that are not counted */
    private static final List<NamespacedKey> DO_NOT_COUNT = List.of(Material.LAVA.getKey(), Material.WATER.getKey(),
            Material.AIR.getKey(), Material.FIRE.getKey(), Material.END_PORTAL.getKey(),
            Material.NETHER_PORTAL.getKey());
    private static final List<NamespacedKey> STACKABLE = List.of(Material.SUGAR_CANE.getKey(), Material.BAMBOO.getKey());

    /*
     * Materials added in Minecraft 1.21.9 ("Copper Age"). Resolved by name so the
     * addon links and runs on older servers (< 1.21.9) where these constants are
     * absent, instead of throwing NoSuchFieldError. Absent Optional == not on this server.
     */
    @Nullable
    private static final Material COPPER_WALL_TORCH = Enums.getIfPresent(Material.class, "COPPER_WALL_TORCH").orNull();
    @Nullable
    private static final Material COPPER_TORCH = Enums.getIfPresent(Material.class, "COPPER_TORCH").orNull();
    @Nullable
    private static final Material COPPER_CHEST = Enums.getIfPresent(Material.class, "COPPER_CHEST").orNull();
    /** All weathered/waxed copper chest variants that normalise to {@link #COPPER_CHEST}. */
    private static final List<Material> COPPER_CHEST_VARIANTS = List.of("EXPOSED_COPPER_CHEST", "WEATHERED_COPPER_CHEST",
            "OXIDIZED_COPPER_CHEST", "WAXED_COPPER_CHEST", "WAXED_EXPOSED_COPPER_CHEST", "WAXED_WEATHERED_COPPER_CHEST",
            "WAXED_OXIDIZED_COPPER_CHEST").stream().map(name -> Enums.getIfPresent(Material.class, name).orNull())
            .filter(Objects::nonNull).toList();

    /**
     * Variant block materials mapped to the canonical material we count them as.
     * Pistons are handled separately because the canonical form depends on block data.
     */
    private static final Map<Material, Material> VARIANT_MAP = new EnumMap<>(Material.class);
    static {
        VARIANT_MAP.put(Material.CHIPPED_ANVIL, Material.ANVIL);
        VARIANT_MAP.put(Material.DAMAGED_ANVIL, Material.ANVIL);
        VARIANT_MAP.put(Material.REDSTONE_WALL_TORCH, Material.REDSTONE_TORCH);
        VARIANT_MAP.put(Material.WALL_TORCH, Material.TORCH);
        VARIANT_MAP.put(Material.ZOMBIE_WALL_HEAD, Material.ZOMBIE_HEAD);
        VARIANT_MAP.put(Material.CREEPER_WALL_HEAD, Material.CREEPER_HEAD);
        VARIANT_MAP.put(Material.PLAYER_WALL_HEAD, Material.PLAYER_HEAD);
        VARIANT_MAP.put(Material.DRAGON_WALL_HEAD, Material.DRAGON_HEAD);
        VARIANT_MAP.put(Material.BAMBOO_SAPLING, Material.BAMBOO);
        // 1.21.9 materials: only mapped when present on this server
        if (COPPER_WALL_TORCH != null && COPPER_TORCH != null) {
            VARIANT_MAP.put(COPPER_WALL_TORCH, COPPER_TORCH);
        }
        if (COPPER_CHEST != null) {
            COPPER_CHEST_VARIANTS.forEach(variant -> VARIANT_MAP.put(variant, COPPER_CHEST));
        }
    }

    /** Save every 10 blocks of change */
    private static final Integer CHANGE_LIMIT = 9;
    private final Limits addon;
    private final Map<String, IslandBlockCount> islandCountMap = new HashMap<>();
    private final Map<String, Integer> saveMap = new HashMap<>();
    private final Database<IslandBlockCount> handler;
    private final Map<World, Map<NamespacedKey, Integer>> worldLimitMap = new HashMap<>();
    /**
     * Per-environment default limits. The "blocklimits" section seeds every env;
     * "blocklimits-nether" / "blocklimits-end" sections override one env each.
     */
    private final Map<Environment, Map<NamespacedKey, Integer>> envDefaultLimitMap = new EnumMap<>(Environment.class);

    public BlockLimitsListener(Limits addon) {
        this.addon = addon;
        for (Environment env : Settings.ENVIRONMENTS) {
            envDefaultLimitMap.put(env, new HashMap<>());
        }
        handler = new Database<>(addon, IslandBlockCount.class);
        List<String> toBeDeleted = new ArrayList<>();
        handler.loadObjects().forEach(ibc -> {
            if (addon.isCoveredGameMode(ibc.getGameMode())) {
                // Strip uncountable types from every env's count map
                ibc.getAllBlockCounts().values().forEach(m -> m.keySet().removeIf(DO_NOT_COUNT::contains));
                islandCountMap.put(ibc.getUniqueId(), ibc);
            } else {
                toBeDeleted.add(ibc.getUniqueId());
            }
        });
        toBeDeleted.forEach(handler::deleteID);
        loadAllLimits();
    }

    /**
     * Loads the default and world-specific limits
     */
    private void loadAllLimits() {
        // Pass 1: "blocklimits" applies to every env as the global default
        if (addon.getConfig().isConfigurationSection("blocklimits")) {
            ConfigurationSection limitConfig = addon.getConfig().getConfigurationSection("blocklimits");
            Map<NamespacedKey, Integer> base = loadLimits(Objects.requireNonNull(limitConfig));
            for (Environment env : Settings.ENVIRONMENTS) {
                envDefaultLimitMap.get(env).putAll(base);
            }
            addon.log("Loading default block limits");
        }
        // Pass 2: env-specific sections override the base for that env
        loadEnvSection("blocklimits-nether", Environment.NETHER);
        loadEnvSection("blocklimits-end", Environment.THE_END);

        // Per-world named overrides
        if (addon.getConfig().isConfigurationSection("worlds")) {
            ConfigurationSection worlds = addon.getConfig().getConfigurationSection("worlds");
            for (String worldName : Objects.requireNonNull(worlds).getKeys(false)) {
                World world = Bukkit.getWorld(worldName);
                if (world != null && addon.inGameModeWorld(world)) {
                    addon.log("Loading limits for " + world.getName());
                    ConfigurationSection matsConfig = worlds.getConfigurationSection(worldName);
                    worldLimitMap.put(world, loadLimits(Objects.requireNonNull(matsConfig)));
                }
            }
        }
    }

    private void loadEnvSection(String section, Environment env) {
        if (!addon.getConfig().isConfigurationSection(section)) return;
        ConfigurationSection cs = addon.getConfig().getConfigurationSection(section);
        addon.log("Loading " + env + " block limit overrides from " + section);
        envDefaultLimitMap.get(env).putAll(loadLimits(Objects.requireNonNull(cs)));
    }

    /**
     * Loads limit map from configuration section
     */
    private Map<NamespacedKey, Integer> loadLimits(ConfigurationSection cs) {
        Map<NamespacedKey, Integer> limits = new HashMap<>();
        for (String key : cs.getKeys(false)) {
            NamespacedKey nsKey = parseConfigKey(key);
            if (nsKey != null) {
                registerLimit(limits, nsKey, key, cs.getInt(key));
            }
        }
        return limits;
    }

    /** Parse a config key into a NamespacedKey, or null if it is an invalid namespaced key. */
    private NamespacedKey parseConfigKey(String key) {
        if (key.contains(":")) {
            NamespacedKey nsKey = NamespacedKey.fromString(key.toLowerCase(Locale.ROOT));
            if (nsKey == null) {
                Bukkit.getLogger().warning(() -> "Invalid namespaced key in config, skipping: " + key);
            }
            return nsKey;
        }
        return new NamespacedKey(NamespacedKey.MINECRAFT, key.toLowerCase(Locale.ROOT));
    }

    /** Resolve a key to a material or block tag and store its limit, warning on any mismatch. */
    private void registerLimit(Map<NamespacedKey, Integer> limits, NamespacedKey nsKey, String key, int limit) {
        Material mat = Registry.MATERIAL.get(nsKey);
        if (mat != null) {
            if (!mat.isBlock()) {
                Bukkit.getLogger().warning(() -> "Non-block material in block limits config: " + key);
            } else if (DO_NOT_COUNT.contains(mat.getKey())) {
                Bukkit.getLogger().warning(() -> "Uncountable material in block limits config: " + key);
            } else {
                limits.put(mat.getKey(), limit);
            }
            return;
        }
        Tag<Material> tag = Bukkit.getTag("blocks", nsKey, Material.class);
        if (tag != null) {
            limits.put(tag.getKey(), limit);
            return;
        }
        Bukkit.getLogger().warning(() -> "Unknown material or tag in config: " + key);
    }

    /** Save the count database completely */
    public void save() {
        islandCountMap.values().stream().filter(IslandBlockCount::isChanged).forEach(handler::saveObjectAsync);
    }

    /** Resolve the env, normalising any non-standard values to NORMAL. */
    private Environment envOf(World w) {
        Environment env = w.getEnvironment();
        return Settings.ENVIRONMENTS.contains(env) ? env : Environment.NORMAL;
    }

    // Player-related events
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlock(BlockPlaceEvent e) {
        // BlockMultiPlaceEvent is a subclass of BlockPlaceEvent and shares its HandlerList,
        // so it is delivered here too. Let the dedicated handler count it once (see #86).
        if (e instanceof BlockMultiPlaceEvent) {
            return;
        }

        addon.getPlugin().getServer().getScheduler().runTaskLater(addon.getPlugin(), () -> {
            // Only count the block if it is not a RoseStacker Stacked block
            if(isBlockNotStacked(e.getBlockPlaced())) {
                notify(e, User.getInstance(e.getPlayer()), process(e.getBlock(), true), e.getBlock().getType());
            }
        }, 1L);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockStack(BlockStackEvent blockStackEvent) {
        Player player = blockStackEvent.getPlayer();
        User user = User.getInstance(player);
        Block b = blockStackEvent.getStack().getBlock();

        addon.getIslands().getIslandAt(b.getLocation()).ifPresent(i -> {
            // Get total limit for this block's material or -1 if not limited
            int totalLimit = getMaterialLimits(b.getWorld(), i.getUniqueId()).getOrDefault(b.getType().getKey(), -1);
            if(totalLimit == -1) return;

            // Get current block count for the block's material
            IslandBlockCount ibc = islandCountMap.get(i.getUniqueId());
            int currentCount = ibc.getBlockCount(b.getType().getKey());

            int potentialCount = currentCount + blockStackEvent.getIncreaseAmount();
            if(potentialCount > totalLimit) {
                int remainder = potentialCount - totalLimit;
                int amountToAdd = totalLimit - currentCount;
                if(amountToAdd <= 0) {
                    notify(blockStackEvent, User.getInstance(player), totalLimit, b.getType());
                    return;
                }

                blockStackEvent.setIncreaseAmount(amountToAdd);

                // Give a stacked block itemstack for the spawners that didn't fit
                ItemStack itemStack = ItemUtils.getBlockAsStackedItemStack(b.getType(), remainder);

                user.notify("block-limits.hit-limit-leftover",
                        "[material]", Util.prettifyText(b.getType().toString()),
                        TextVariables.NUMBER, String.valueOf(totalLimit));

                HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(itemStack);
                if(!leftover.isEmpty()) {
                    for(ItemStack stack : leftover.values()) {
                        player.getWorld().dropItem(player.getLocation(), stack);
                    }
                }

                notify(blockStackEvent, User.getInstance(player), processStacked(b, true, amountToAdd), b.getType());
            } else {
                notify(blockStackEvent, User.getInstance(player), processStacked(b, true, blockStackEvent.getIncreaseAmount()), b.getType());
            }
        });
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockUnstack(BlockUnstackEvent blockUnstackEvent) {
        Player player = blockUnstackEvent.getPlayer();
        if(player != null) {
            notify(blockUnstackEvent, User.getInstance(player), processStacked(blockUnstackEvent.getStack().getBlock(), false, blockUnstackEvent.getDecreaseAmount()), blockUnstackEvent.getStack().getBlock().getType());
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSpawnerStack(SpawnerStackEvent spawnerStackEvent) {
        Player player = spawnerStackEvent.getPlayer();
        User user = User.getInstance(player);
        Block b = spawnerStackEvent.getStack().getBlock();
        StackedSpawner stackedSpawner = spawnerStackEvent.getStack();

        addon.getIslands().getIslandAt(b.getLocation()).ifPresent(i -> {
            // Get total limit for this block's material or -1 if not limited
            int totalLimit = getMaterialLimits(b.getWorld(), i.getUniqueId()).getOrDefault(b.getType().getKey(), -1);
            if(totalLimit == -1) return;

            // Get current block count for the block's material
            IslandBlockCount ibc = islandCountMap.get(i.getUniqueId());
            int currentCount = ibc.getBlockCount(b.getType().getKey());

            int potentialCount = currentCount + spawnerStackEvent.getIncreaseAmount();
            if(potentialCount > totalLimit) {
                int remainder = potentialCount - totalLimit;
                int amountToAdd = totalLimit - currentCount;
                if(amountToAdd <= 0) {
                    notify(spawnerStackEvent, user, totalLimit, b.getType());
                    return;
                }

                spawnerStackEvent.setIncreaseAmount(amountToAdd);

                // Give a stacked spawner itemstack for the spawners that didn't fit
                ItemStack itemStack = ItemUtils.getSpawnerAsStackedItemStack(stackedSpawner.getStackSettings().getSpawnerType(), remainder);

                user.notify("block-limits.hit-limit-leftover",
                        "[material]", Util.prettifyText(b.getType().toString()),
                        TextVariables.NUMBER, String.valueOf(totalLimit));

                HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(itemStack);
                if(!leftover.isEmpty()) {
                    for(ItemStack stack : leftover.values()) {
                        player.getWorld().dropItem(player.getLocation(), stack);
                    }
                }

                notify(spawnerStackEvent, user, processStacked(b, true, amountToAdd), b.getType());
            } else {
                notify(spawnerStackEvent, user, processStacked(b, true, spawnerStackEvent.getIncreaseAmount()), b.getType());
            }
        });
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSpawnerUnstack(SpawnerUnstackEvent spawnerUnstackEvent) {
        Player player = spawnerUnstackEvent.getPlayer();
        if(player != null) {
            notify(spawnerUnstackEvent, User.getInstance(player), processStacked(spawnerUnstackEvent.getStack().getBlock(), false, spawnerUnstackEvent.getDecreaseAmount()), spawnerUnstackEvent.getStack().getBlock().getType());
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlock(BlockBreakEvent e) {
        if (e.getBlock().hasMetadata("blockbreakevent-ignore")) {
            return;
        }
        handleBreak(e.getBlock());
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onTurtleEggBreak(PlayerInteractEvent e) {
        if (e.getAction().equals(Action.PHYSICAL) && e.getClickedBlock() != null
                && e.getClickedBlock().getType().equals(Material.TURTLE_EGG)) {
            handleBreak(e.getClickedBlock());
        }
    }

    private void handleBreak(Block b) {
        if (!addon.inGameModeWorld(b.getWorld())) {
            return;
        }
        Material mat = b.getType();
        if (STACKABLE.contains(b.getType().getKey())) {
            Block block = b;
            while (block.getRelative(BlockFace.UP).getType().equals(mat)
                    && block.getY() < b.getWorld().getMaxHeight()) {
                block = block.getRelative(BlockFace.UP);
                process(block, false);
            }
        }

        if(isBlockNotStacked(b)) {
            process(b, false);
        }

        if (b.getRelative(BlockFace.UP).getType() == Material.REDSTONE_WIRE
                || b.getRelative(BlockFace.UP).getType() == Material.REPEATER
                || b.getRelative(BlockFace.UP).getType() == Material.COMPARATOR
                || b.getRelative(BlockFace.UP).getType() == Material.REDSTONE_TORCH) {
            process(b.getRelative(BlockFace.UP), false);
        }
        if (b.getRelative(BlockFace.EAST).getType() == Material.REDSTONE_WALL_TORCH) {
            process(b.getRelative(BlockFace.EAST), false);
        }
        if (b.getRelative(BlockFace.WEST).getType() == Material.REDSTONE_WALL_TORCH) {
            process(b.getRelative(BlockFace.WEST), false);
        }
        if (b.getRelative(BlockFace.SOUTH).getType() == Material.REDSTONE_WALL_TORCH) {
            process(b.getRelative(BlockFace.SOUTH), false);
        }
        if (b.getRelative(BlockFace.NORTH).getType() == Material.REDSTONE_WALL_TORCH) {
            process(b.getRelative(BlockFace.NORTH), false);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlock(BlockMultiPlaceEvent e) {
        notify(e, User.getInstance(e.getPlayer()), process(e.getBlockPlaced(), true), e.getBlockPlaced().getType());
    }

    private void notify(Cancellable e, User user, int limit, Material m) {
        if (limit > -1) {
            user.notify("block-limits.hit-limit",
                    "[material]", Util.prettifyText(m.toString()),
                    TextVariables.NUMBER, String.valueOf(limit));
            e.setCancelled(true);
        }
    }

    // Non-player events
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlock(BlockBurnEvent e) {
        process(e.getBlock(), false);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlock(BlockExplodeEvent e) {
        e.blockList().forEach(b -> process(b, false));
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlock(BlockFadeEvent e) {
        process(e.getBlock(), false);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlock(BlockFormEvent e) {
        if (e instanceof EntityBlockFormEvent || e instanceof BlockSpreadEvent) {
            return;
        }
        process(e.getBlock(), false);
        if (process(e.getBlock(), e.getNewState().getBlockData(), true) > -1) {
            e.setCancelled(true);
            process(e.getBlock(), true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlock(BlockSpreadEvent e) {
        process(e.getBlock(), false);
        if (process(e.getBlock(), e.getNewState().getBlockData(), true) > -1) {
            e.setCancelled(true);
            process(e.getBlock(), true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlock(EntityBlockFormEvent e) {
        process(e.getBlock(), false);
        if (process(e.getBlock(), e.getNewState().getBlockData(), true) > -1) {
            e.setCancelled(true);
            process(e.getBlock(), true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlock(BlockGrowEvent e) {
        if (process(e.getNewState().getBlock(), true) > -1) {
            e.setCancelled(true);
            e.getBlock().getWorld().getBlockAt(e.getBlock().getLocation()).setBlockData(e.getBlock().getBlockData());
        } else {
            process(e.getBlock(), false);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlock(LeavesDecayEvent e) {
        process(e.getBlock(), false);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlock(EntityExplodeEvent e) {
        e.blockList().forEach(b -> process(b, false));
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlock(EntityChangeBlockEvent e) {
        if (e.getTo() != Material.AIR) {
            int limit = process(e.getBlock(), e.getBlockData(), true);
            if (limit > -1) {
                e.setCancelled(true);
                return;
            }
        }
        process(e.getBlock(), false);
        if (!e.isCancelled() && e.getBlock().getType().equals(Material.FARMLAND)) {
            process(e.getBlock().getRelative(BlockFace.UP), false);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlock(BlockFromToEvent e) {
        if (e.getBlock().isLiquid()
                && (e.getToBlock().getType() == Material.REDSTONE_WIRE
                        || e.getToBlock().getType() == Material.REPEATER
                        || e.getToBlock().getType() == Material.COMPARATOR
                        || e.getToBlock().getType() == Material.REDSTONE_TORCH
                        || e.getToBlock().getType() == Material.REDSTONE_WALL_TORCH)) {
            process(e.getToBlock(), false);
        }
    }

    /**
     * Map variant materials to their canonical form.
     */
    public NamespacedKey fixMaterial(BlockData b) {
        Material mat = b.getMaterial();
        if (mat == Material.PISTON_HEAD || mat == Material.MOVING_PISTON) {
            TechnicalPiston tp = (TechnicalPiston) b;
            return (tp.getType() == TechnicalPiston.Type.NORMAL ? Material.PISTON : Material.STICKY_PISTON).getKey();
        }
        return VARIANT_MAP.getOrDefault(mat, mat).getKey();
    }

    private int process(Block b, boolean add) {
        return process(b, b.getBlockData(), add);
    }

    /**
     * Check if a block can be placed or needs to be removed based on limits.
     * @return limit amount if over limit, or -1 if no limitation
     */
    private int process(Block b, BlockData blockData, boolean add) {
        if (DO_NOT_COUNT.contains(fixMaterial(blockData)) || !addon.inGameModeWorld(b.getWorld())) {
            return -1;
        }
        Environment env = envOf(b.getWorld());
        return addon.getIslands().getIslandAt(b.getLocation()).map(i -> {
            String id = i.getUniqueId();
            String gameMode = addon.getGameModeName(b.getWorld());
            if (gameMode.isEmpty()) {
                return -1;
            }
            if (addon.getConfig().getBoolean("ignore-center-block", true)
                    && i.getCenter().equals(b.getLocation())) {
                return -1;
            }

            islandCountMap.putIfAbsent(id, new IslandBlockCount(id, gameMode));
            NamespacedKey key = fixMaterial(blockData);
            if (add) {
                int limit = checkLimit(b.getWorld(), env, key, id);
                if (limit > -1) {
                    return limit;
                }
                islandCountMap.get(id).add(env, key, 1);
            } else {
                islandCountMap.get(id).remove(env, key, 1);
            }

            updateSaveMap(id, 1);
            return -1;
        }).orElse(-1);
    }

    /**
     * Check if a block can be
     *
     * @param b - block
     * @param add - true to add a block, false to remove
     * @param amount - The amount the stacked block increased or decreased
     * @return limit amount if over limit, or -1 if no limitation
     */
    private int processStacked(Block b, boolean add, int amount) {
        if (DO_NOT_COUNT.contains(fixMaterial(b.getBlockData())) || !addon.inGameModeWorld(b.getWorld())) {
            return -1;
        }

        Location blockLocation = b.getLocation();
        World blockWorld = b.getWorld();
        World.Environment worldEnvironment = blockWorld.getEnvironment();

        // Check if on island
        return addon.getIslands().getIslandAt(blockLocation).map(i -> {
            String id = i.getUniqueId();
            String gameMode = addon.getGameModeName(blockWorld);
            if (gameMode.isEmpty()) {
                // Invalid world
                return -1;
            }

            // Ignore the center block - usually bedrock, but for AOneBlock it's the magic block
            if (addon.getConfig().getBoolean("ignore-center-block", true) && i.getCenter().equals(blockLocation)) {
                return -1;
            }

            islandCountMap.putIfAbsent(id, new IslandBlockCount(id, gameMode));
            if (add) {
                // Check limit
                int limit = checkLimit(blockWorld, worldEnvironment, fixMaterial(b.getBlockData()), id);
                if (limit > -1) {
                    return limit;
                }

                islandCountMap.get(id).add(worldEnvironment, fixMaterial(b.getBlockData()), amount);
            } else {
                if(islandCountMap.containsKey(id)) {
                    islandCountMap.get(id).remove(worldEnvironment, fixMaterial(b.getBlockData()), amount);
                }
            }

            updateSaveMap(id, amount);
            return -1;
        }).orElse(-1);
    }

    /**
     * Remove a block from any island limit count
     */
    public void removeBlock(Block b) {
        addon.getIslands().getIslandAt(b.getLocation()).ifPresent(i -> {
            String id = i.getUniqueId();
            String gameMode = addon.getGameModeName(b.getWorld());
            if (gameMode.isEmpty()) return;

            int amount = getStackedAmount(b);

            Environment env = envOf(b.getWorld());
            islandCountMap.computeIfAbsent(id, k -> new IslandBlockCount(id, gameMode))
                    .remove(env, fixMaterial(b.getBlockData()), 1);
            updateSaveMap(id, amount);
        });
    }

    private void updateSaveMap(String id, int amount) {
        saveMap.putIfAbsent(id, 0);

        if(saveMap.merge(id, amount, Integer::sum) > CHANGE_LIMIT) {
            handler.saveObjectAsync(islandCountMap.get(id));
            saveMap.remove(id);
        }
    }

    /**
     * Resolve the active limit for this material in this world for this island.
     * Priority: island-env limit, world-named limit, env-default, none.
     *
     * @return limit if at or over, or -1 if no limit
     */
    private int checkLimit(World w, Environment env, NamespacedKey m, String id) {
        IslandBlockCount ibc = islandCountMap.get(id);
        if (ibc.isBlockLimited(env, m)) {
            int offset = ibc.getBlockLimitOffset(env, m);
            return ibc.isAtLimit(env, m) ? ibc.getBlockLimit(env, m) + offset : -1;
        }
        Map<NamespacedKey, Integer> worldMap = worldLimitMap.get(w);
        if (worldMap != null && worldMap.containsKey(m)) {
            int worldLimit = worldMap.get(m);
            int offset = ibc.getBlockLimitOffset(env, m);
            return ibc.isAtLimit(env, m, worldLimit) ? worldLimit + offset : -1;
        }
        Map<NamespacedKey, Integer> envDefaults = envDefaultLimitMap.get(env);
        if (envDefaults != null && envDefaults.containsKey(m)) {
            int envLimit = envDefaults.get(m);
            int offset = ibc.getBlockLimitOffset(env, m);
            if (ibc.isAtLimit(env, m, envLimit)) {
                return envLimit + offset;
            }
        }
        return -1;
    }

    /**
     * Aggregate map of the resolved limits for this island in the given world.
     */
    public Map<NamespacedKey, Integer> getMaterialLimits(World w, String id) {
        Environment env = envOf(w);
        Map<NamespacedKey, Integer> result = new HashMap<>();
        Map<NamespacedKey, Integer> envDefaults = envDefaultLimitMap.get(env);
        if (envDefaults != null) {
            result.putAll(envDefaults);
        }
        Map<NamespacedKey, Integer> worldMap = worldLimitMap.get(w);
        if (worldMap != null) {
            result.putAll(worldMap);
        }
        IslandBlockCount ibc = islandCountMap.get(id);
        if (ibc != null) {
            result.putAll(ibc.getBlockLimits(env));
            ibc.getBlockLimitsOffset(env).forEach(
                    (material, offset) -> result.put(material, result.getOrDefault(material, 0) + offset));
        }
        return result;
    }

    /**
     * Per-environment map of the env-default limits, used for tests and external introspection.
     */
    public Map<Environment, Map<NamespacedKey, Integer>> getEnvDefaultLimitMap() {
        return envDefaultLimitMap;
    }

    public Map<World, Map<NamespacedKey, Integer>> getWorldLimitMap() {
        return worldLimitMap;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onIslandDelete(IslandDeleteEvent e) {
        islandCountMap.remove(e.getIsland().getUniqueId());
        saveMap.remove(e.getIsland().getUniqueId());
        if (handler.objectExists(e.getIsland().getUniqueId())) {
            handler.deleteID(e.getIsland().getUniqueId());
        }
    }

    public void setIsland(String islandId, IslandBlockCount ibc) {
        islandCountMap.put(islandId, ibc);
        handler.saveObjectAsync(ibc);
    }

    @Nullable
    public IslandBlockCount getIsland(String islandId) {
        return islandCountMap.get(islandId);
    }

    @NonNull
    public IslandBlockCount getIsland(Island island) {
        return islandCountMap.computeIfAbsent(island.getUniqueId(),
                k -> new IslandBlockCount(k, island.getGameMode()));
    }

    /**
     * Get the number of blocks or spawners inside a RoseStacker stacked block/spawner.
     * @param block The Block to check and get the stacked count for.
     * @return The number of blocks in the stack or 1 if not a stacked block or RoseStacker is not enabled.
     */
    private int getStackedAmount(Block block) {
        if(!addon.isRoseStackersEnabled()) return 1;

        RoseStackerAPI rsAPI = RoseStackerAPI.getInstance();
        if(rsAPI.isSpawnerStacked(block)) {
            StackedSpawner stackedSpawner = rsAPI.getStackedSpawner(block);
            if(stackedSpawner != null) {
                return stackedSpawner.getStackSize();
            }
        }

        if(rsAPI.isBlockStacked(block)) {
            StackedBlock stackedBlock = rsAPI.getStackedBlock(block);
            if(stackedBlock != null) {
                return stackedBlock.getStackSize();
            }
        }

        return 1;
    }

    private boolean isBlockNotStacked(Block block) {
        if(!addon.isRoseStackersEnabled()) return true;

        RoseStackerAPI rsAPI = RoseStackerAPI.getInstance();
        return !rsAPI.isSpawnerStacked(block) && !rsAPI.isBlockStacked(block);
    }
}
