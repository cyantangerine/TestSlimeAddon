package me.cyantangerine.slimefunaddon.item;

import io.github.thebusybiscuit.slimefun4.api.events.PlayerRightClickEvent;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.attributes.NotPlaceable;
import io.github.thebusybiscuit.slimefun4.core.handlers.ItemUseHandler;
import io.github.thebusybiscuit.slimefun4.libraries.dough.chat.ChatInput;
import io.github.thebusybiscuit.slimefun4.utils.LoreBuilder;
import me.cyantangerine.slimefunaddon.model.ResourceLib;
import me.cyantangerine.slimefunaddon.utils.Utils;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

/**
 * 智能存储无线交互终端：不可放置的手持物品。
 *
 * <p><b>绑定</b>：右键一个已放置的智能存储交互终端 → 提示当前绑定与目标坐标 →
 * 在聊天里输入名称（与 Slimefun 背包命名同一套 ChatInput）→ 把坐标和名称写进物品 PDC，
 * 显示名变成「名称 [无线存储终端]」。再次右键其它交互终端即可重新绑定。</p>
 *
 * <p><b>使用</b>：手持或在物品栏里右键该物品 → 未绑定则提示绑定方式；已绑定则先检查
 * 目标终端方块是否还在，还在就打开与右键该终端完全相同的存储界面（含原 ACL 校验），
 * 已被破坏则拒绝打开并把物品恢复成未绑定状态。这个检查<b>只在使用时做</b>，
 * 不会在服务器运行期间主动轮询。</p>
 */
public class SmartStorageWirelessTerminal extends SlimefunItem implements Listener, NotPlaceable {

    public static final String ID = "SMART_STORAGE_WIRELESS_TERMINAL";
    private static final String SUFFIX = " [无线存储终端]";
    private static final int MAX_NAME_LENGTH = 24;

    private static final String NEED_BIND = colorize(
            "&c尚未绑定：请手持本物品右键一个已放置的智能存储交互终端进行绑定。");
    private static final String BROKEN = colorize(
            "&c绑定的智能存储交互终端已不存在，已恢复为未绑定状态；请重新右键一个终端绑定。");
    private static final String BLOCKED = colorize("&c智能存储无线交互终端不可放置。");

    private final SmartStorageInterface interfaceItem;

    private Plugin plugin;
    private ResourceLib resourceLib;
    private NamespacedKey keyWorld;
    private NamespacedKey keyX;
    private NamespacedKey keyY;
    private NamespacedKey keyZ;
    private NamespacedKey keyName;

    public SmartStorageWirelessTerminal(ItemGroup itemGroup, SlimefunItemStack item,
                                        RecipeType recipeType, ItemStack[] recipe,
                                        SmartStorageInterface interfaceItem) {
        super(itemGroup, item, recipeType, recipe);
        this.interfaceItem = interfaceItem;
    }

    @Override
    public void preRegister() {
        super.preRegister();
        plugin = getAddon().getJavaPlugin();
        resourceLib = Utils.initialCheck(plugin);
        keyWorld = new NamespacedKey(plugin, "wireless_world");
        keyX = new NamespacedKey(plugin, "wireless_x");
        keyY = new NamespacedKey(plugin, "wireless_y");
        keyZ = new NamespacedKey(plugin, "wireless_z");
        keyName = new NamespacedKey(plugin, "wireless_name");
        addItemHandler((ItemUseHandler) this::onRightClick);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public static SlimefunItemStack createItemStack() {
        return new SlimefunItemStack(ID, Material.ENDER_EYE,
                "&b智能存储无线交互终端",
                "&7需右键已放置的智能存储交互终端并输入名称完成绑定",
                "&7绑定后手持/在物品栏右键即可打开该终端的存储界面",
                "&7若绑定的终端方块被破坏，使用时会自动解除绑定",
                LoreBuilder.RIGHT_CLICK_TO_OPEN);
    }

    // ------------------------------------------------------------------
    // 右键入口
    // ------------------------------------------------------------------

    private void onRightClick(PlayerRightClickEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (SlimefunItem.getByItem(item) != this) {
            return;
        }
        event.cancel();
        event.getInteractEvent().setUseItemInHand(Event.Result.DENY);
        event.getInteractEvent().setUseInteractedBlock(Event.Result.DENY);

        Block target = event.getClickedBlock().orElse(null);
        if (target != null && BlockStorage.check(target) == interfaceItem) {
            int slot = event.getHand() == EquipmentSlot.OFF_HAND
                    ? 40 : player.getInventory().getHeldItemSlot();
            beginBinding(player, item, target, slot);
            return;
        }
        use(player, item, event.getHand(), null);
    }

    /** 物品栏里右键也可以「使用」（绑定仍然只能手持右键交互终端完成）。 */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryRightClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ClickType click = event.getClick();
        if (click != ClickType.RIGHT && click != ClickType.SHIFT_RIGHT) {
            return;
        }
        // 只有「没打开外部容器」时，玩家背包里的右键才算使用
        if (!(event.getInventory() instanceof PlayerInventory)) {
            return;
        }
        ItemStack current = event.getCurrentItem();
        if (SlimefunItem.getByItem(current) != this) {
            return;
        }
        event.setCancelled(true);
        use(player, current, null, event);
    }

    /** 双保险：除了 NotPlaceable 标记，自己也拦一次放置，给出明确提示。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (SlimefunItem.getByItem(event.getItemInHand()) == this) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(BLOCKED);
        }
    }

    // ------------------------------------------------------------------
    // 绑定
    // ------------------------------------------------------------------

    private void beginBinding(Player player, ItemStack item, Block block, int slot) {
        Location target = block.getLocation();
        Binding existing = readBinding(item);
        if (existing != null) {
            player.sendMessage(colorize("&7当前已绑定：&f" + existing.name()
                    + " &7@ &f" + coordinates(existing.location())));
        }
        player.sendMessage(colorize("&7目标终端坐标：&f" + coordinates(target)));
        player.sendMessage(colorize("&e请在聊天里输入这个终端的名称（输入后即完成绑定）"));

        ChatInput.waitForPlayer(plugin, player, (p, raw) -> {
            String name = raw == null ? "" : raw.trim();
            if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
                resourceLib.runAtEntity(p, () -> p.sendMessage(colorize(
                        "&c名称需为 1~" + MAX_NAME_LENGTH + " 个字符，请重新右键终端绑定。")));
                return;
            }
            resourceLib.runAtEntity(p, () -> {
                ItemStack updated = writeBinding(item, target, name);
                putBack(p, updated, slot);
                p.sendMessage(colorize("&a绑定成功：&f" + name
                        + " &7@ &f" + coordinates(target)));
            });
        });
    }

    /**
     * 把改过的物品写回玩家手上 / 快捷栏；找不到就落回背包（绝不吞物品）。
     *
     * <p>只写回「开始绑定 / 解绑时所持物品所在的那个槽位」(slot)。若输入名称期间玩家切换 /
     * 移动了手持物品（该槽位已不再是本无线终端），则<b>不补发</b>，避免旧逻辑
     * {@code inventory.addItem(updated)} 兜底凭空刷出新的无线终端副本（无限刷物品漏洞）。
     * 原物品仍由玩家持有，仅本次绑定 / 解绑作废，稍后重新右键交互终端即可。</p>
     */
    private void putBack(Player player, ItemStack updated, int slot) {
        PlayerInventory inventory = player.getInventory();
        ItemStack current = inventory.getItem(slot);
        if (SlimefunItem.getByItem(current) == this) {
            inventory.setItem(slot, updated);
        } else {
            player.sendMessage(colorize(
                    "&c绑定已取消：手持的无线终端已不在原位置，请重新右键交互终端绑定。"));
        }
        player.updateInventory();
    }

    // ------------------------------------------------------------------
    // 使用：检查方块 -> 打开界面
    // ------------------------------------------------------------------

    private void use(Player player, ItemStack item, EquipmentSlot hand, InventoryClickEvent clickEvent) {
        Binding binding = readBinding(item);
        if (binding == null) {
            player.sendMessage(NEED_BIND);
            return;
        }
        Location location = binding.location();
        Block block = location == null ? null : location.getBlock();
        // 既查 Slimefun 方块数据，也查材质：方块被替换成别的方块时不能还当成终端
        if (block == null || block.getType() != Material.OBSERVER
                || BlockStorage.check(block) != interfaceItem) {
            ItemStack cleared = clearBinding(item);
            if (clickEvent != null) {
                clickEvent.setCurrentItem(cleared);
            } else {
                int slot = hand == EquipmentSlot.OFF_HAND
                        ? 40 : player.getInventory().getHeldItemSlot();
                putBack(player, cleared, slot);
            }
            player.sendMessage(BROKEN);
            return;
        }
        // 与右键该交互终端完全相同的入口（含 ACL 校验）
        interfaceItem.openFrom(player, block);
    }

    // ------------------------------------------------------------------
    // PDC 读写
    // ------------------------------------------------------------------

    private record Binding(Location location, String name) { }

    private Binding readBinding(ItemStack item) {
        if (item == null || SlimefunItem.getByItem(item) != this) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        try {
            String world = meta.getPersistentDataContainer().get(keyWorld, PersistentDataType.STRING);
            Integer x = meta.getPersistentDataContainer().get(keyX, PersistentDataType.INTEGER);
            Integer y = meta.getPersistentDataContainer().get(keyY, PersistentDataType.INTEGER);
            Integer z = meta.getPersistentDataContainer().get(keyZ, PersistentDataType.INTEGER);
            String name = meta.getPersistentDataContainer().get(keyName, PersistentDataType.STRING);
            if (world == null || x == null || y == null || z == null) {
                return null;
            }
            World bukkitWorld = Bukkit.getWorld(UUID.fromString(world));
            if (bukkitWorld == null) {
                return null;
            }
            return new Binding(new Location(bukkitWorld, x, y, z),
                    name == null || name.isEmpty() ? "终端" : name);
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private ItemStack writeBinding(ItemStack original, Location location, String name) {
        ItemStack item = original.clone();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.setDisplayName(colorize("&b" + name + SUFFIX));
        meta.getPersistentDataContainer().set(keyWorld, PersistentDataType.STRING,
                location.getWorld().getUID().toString());
        meta.getPersistentDataContainer().set(keyX, PersistentDataType.INTEGER, location.getBlockX());
        meta.getPersistentDataContainer().set(keyY, PersistentDataType.INTEGER, location.getBlockY());
        meta.getPersistentDataContainer().set(keyZ, PersistentDataType.INTEGER, location.getBlockZ());
        meta.getPersistentDataContainer().set(keyName, PersistentDataType.STRING, name);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack clearBinding(ItemStack original) {
        ItemStack item = original.clone();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.setDisplayName(colorize("&b智能存储无线交互终端 &7(未绑定)"));
        meta.getPersistentDataContainer().remove(keyWorld);
        meta.getPersistentDataContainer().remove(keyX);
        meta.getPersistentDataContainer().remove(keyY);
        meta.getPersistentDataContainer().remove(keyZ);
        meta.getPersistentDataContainer().remove(keyName);
        item.setItemMeta(meta);
        return item;
    }

    private static String coordinates(Location location) {
        if (location == null) {
            return "(?, ?, ?)";
        }
        return "(" + location.getBlockX() + ", " + location.getBlockY()
                + ", " + location.getBlockZ() + ")";
    }

    private static String colorize(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}
