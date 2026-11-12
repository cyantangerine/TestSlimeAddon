package me.cyantangerine.slimefunaddon.item;

import com.tcoded.folialib.wrapper.task.WrappedTask;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.player.PlayerBackpack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.implementation.items.food.Juice;
import io.github.thebusybiscuit.slimefun4.utils.LoreBuilder;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.ParametersAreNonnullByDefault;

import io.github.thebusybiscuit.slimefun4.utils.SlimefunUtils;
import me.cyantangerine.slimefunaddon.utils.Utils;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExhaustionEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;

/**
 * 一个能"看见容量"的小冰柜。
 *
 * <p>Slimefun 的背包内容不在物品里，而在 {@link PlayerBackpack} 的 {@link Inventory} 中
 * （RC-32 存玩家 yml，dev 分支存数据库）。所以容量只能在背包真正打开/改动时统计，
 * 再写回物品让客户端渲染。任何"猜测式"的加减都会和真实存档不一致。</p>
 *
 * <p>本类不注册 Slimefun 自带的 {@code CoolerListener}：它在 Folia 上把
 * {@code addPotionEffect} 丢到全局区域线程，会抛线程检查异常导致果汁没被喝掉，
 * 而 {@code CoolerFeedPlayerEvent} 却在异常之前就发出去了。这里改成自己的
 * Folia 安全实现：只要玩家饱和度低于 {@link #SATURATION_THRESHOLD} 就喝一瓶，
 * 且扫描整份玩家背包（不限快捷栏或手持）。</p>
 *
 * <p>手持右键容器（箱子/桶/潜影盒/漏斗/发射器/投掷器）会把容器里的果汁批量装载进冰柜，
 * 并回报装载了多少瓶、分别是什么果汁；潜行时则按原版行为正常开箱。</p>
 *
 * <p>首次使用要输入背包名称：右键时会先关掉客户端残留的原版物品栏/收纳袋浮窗。
 * 只有在主手/副手时才放行 Slimefun 的聊天输入命名；从物品栏里点开的会关掉浮窗
 * 并提示玩家先放到手上，绝不触发 Slimefun 的「请输入名称」。
 * 未绑定背包的冰柜用满权重的冰占位，盖掉客户端 tooltip 里的原版收纳袋显示；
 * Slimefun 异步完成命名绑定后，再换回正常的容量显示。</p>
 *
 * <p>创造模式的点击是 {@link InventoryCreativeEvent}（ClickType.CREATIVE，左右键被压成同一个类型），
 * 且一次点击会连发两个 SetCreativeModeSlot 包（槽位包 + 光标包）。这里按客户端上报的
 * cursor 反推意图：左键只是搬运整只冰柜，完全放行；右键改了 bundle 内容则取消事件。
 * 创造模式的服务端不跟踪光标物品，所以额外维护一个短时"正在拿着冰柜"状态，
 * 用来拦掉「拿起冰柜后在别的物品上右键」这种原版收纳袋行为。</p>
 *
 * <p>无论从哪条路径打开小冰柜，都会先关掉当前界面（把光标上的冰柜放回背包），
 * 再打开新的 GUI；已打开的另一个冰柜也能直接切换过去。</p>
 *
 * <p><b>幽灵占位物</b>：Folia 26.3 的 {@code handleSetCreativeModeSlot} 在 DENY 且
 * {@code slotNum < 0}（OUTSIDE 光标包）时是静默 return，一个同步包都不发，
 * 客户端会一直"拿着"它本地预测倒出来的玻璃瓶。所以占位物都带 PDC 标记，
 * 右键时显式广播服务端真实光标，打开/关闭冰柜 GUI 时再强制全量同步并回收泄漏物。</p>
 *
 * <p>三层显示，按能力自动降级：{@link BundleMeta} 图标条 / {@link Damageable} 耐久条 / Lore 文本条。</p>
 */
public class SmartCooler extends SmartContainer implements Listener {
    public static final ItemStack HEAD_ICON = SlimefunUtils.getCustomHead("eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZjM4MTRkYjdjMjRmNDg2ODhhNjA0ZThkZmU4NGYwOTdiYjExYzc2YjQ4YTRjMDMxOWQyMTBmZDQ5NzBjMjA5MSJ9fX0=");
    /**
     * 容量：GUI 格数、Lore 进度条分母、占位玻璃瓶的最大堆叠数，三者统一用这个值。
     * 1.21 的收纳袋权重 = 数量 / 最大堆叠数，于是 used 个瓶子正好画出 used / CAPACITY。
     */
    public static final int CAPACITY = 36;
    /**
     * 新建背包时传给 Slimefun 的格数，与容量一致
     */
    public static final int DEFAULT_SIZE = CAPACITY;

    /**
     * 饱和度低于这个值就自动喝果汁；不依赖饱食度下降事件
     */
    private static final float SATURATION_THRESHOLD = 2.0F;
    /**
     * 喝一瓶果汁后恢复到的饱和度，与 Slimefun 官方 Cooler 保持一致
     */
    private static final float SATURATION_RESTORE = 6.0F;
    /**
     * 两次自动饮用尝试的最小间隔，避免没果汁时反复查库
     */
    private static final long FEED_COOLDOWN_MILLIS = 2000L;
    /**
     * 饱和度轮询周期（tick）。EntityExhaustionEvent 负责即时触发，定时器负责兜底
     */
    private static final long CHECK_INTERVAL_TICKS = 10L;
    /**
     * 当被消耗后，小冰柜内的数量小于指定值时，都发消息，负数表示关闭
     **/
    private static final short WARNING_IF_ITEMS_COUNT_BELOW = 3;

    private static final String NOT_ENOUGH_WARN_EMPTY = ChatColor.YELLOW + "[ " + ChatColor.WHITE + " %s " + ChatColor.YELLOW + " ] 里已经没有果汁。";
    private static final String NOT_ENOUGH_WARN = ChatColor.YELLOW + "[ " + ChatColor.WHITE + " %s " + ChatColor.YELLOW + " ] 里只剩下 " + ChatColor.RED + "%d" + ChatColor.YELLOW + " 瓶果汁。";

    private static final String JUICE_ONLY = ChatColor.RED + "小冰柜只能存放果汁！";
    private static final String NO_JUICE_IN_CONTAINER = ChatColor.GRAY + "容器里没有果汁，无法装载。";

    @Override
    protected String getLoadOnlyMsg() {
        return JUICE_ONLY;
    }

    @Override
    protected String getLoadErrorMsg() {
        return NO_JUICE_IN_CONTAINER;
    }

    /**
     * 每个玩家自己的饱和度轮询任务（Folia 上必须挂在实体所属区域线程）
     */
    private final ConcurrentMap<UUID, WrappedTask> saturationChecks = new ConcurrentHashMap<>();
    /**
     * 玩家 UUID -> 下一次允许自动饮用的时间戳
     */
    private final ConcurrentMap<UUID, Long> feedCooldown = new ConcurrentHashMap<>();

    private static final Material defaultPlaceholder = Material.ICE;
    private static final Material placeholder = Material.GLASS_BOTTLE;


    @ParametersAreNonnullByDefault
    public SmartCooler(int size, ItemGroup itemGroup, SlimefunItemStack item, RecipeType recipeType, ItemStack[] recipe) {
        super(size, itemGroup, item, recipeType, recipe);
    }

    @Override
    public void preRegister() {
        super.preRegister();
        Bukkit.getPluginManager().registerEvents(this, getPlugin());
        // 注意：这里绝不能 new CoolerListener(Slimefun.instance(), this)，理由见类注释。
        // 插件重载时可能已有在线玩家，补挂轮询任务
        for (Player player : Bukkit.getOnlinePlayers()) {
            registerSaturationCheck(player);
        }
    }

    @Override
    public Material getDefaultPlaceholder() {
        return defaultPlaceholder;
    }

    @Override
    public Material getPlaceholder() {
        return placeholder;
    }

    @Override
    protected int getCapacity() {
        return CAPACITY;
    }

    public static SlimefunItemStack createItemStack() {
        return SmartContainer.createItemStack(
                "SMART_COOLER",
                "&b智能小冰柜",
                () -> new ItemStack(defaultPlaceholder, SmartContainer.LEGACY_BUNDLE_MAX_WEIGHT),
                "可装载果汁，会在饱和度低时自动食用",
                "&7无需快捷栏，在物品栏右键即可打开",
                "&7类似收纳袋拾起后左键可装入果汁",
                "&7手持时右键容器可自动装入果汁",
                "&7容量可视化，低余量自动告警",
                "&7容量: &e" + CAPACITY,
                LoreBuilder.RIGHT_CLICK_TO_OPEN
        );
    }


    // ------------------------------------------------------------------
    // 自动喝果汁：饱和度低于 2 即触发，扫描整份玩家背包（不只是快捷栏）
    // ------------------------------------------------------------------

    /**
     * 每个玩家一个挂在实体区域线程上的轮询任务，保证读饱和度/改实体都是线程安全的。
     */
    private void registerSaturationCheck(Player player) {
        WrappedTask task = getResourceLib().getFoliaLib().getScheduler().runAtEntityTimer(
                player,
                () -> {
                    if (player.isOnline()) maybeFeed(player);
                },
                CHECK_INTERVAL_TICKS, CHECK_INTERVAL_TICKS
        );

        if (task == null) {
            return;
        }
        WrappedTask previous = saturationChecks.put(player.getUniqueId(), task);
        if (previous != null) {
            previous.cancel();
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        registerSaturationCheck(event.getPlayer());
    }

    @EventHandler
    @Override
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        super.onQuit(event);
        feedCooldown.remove(uuid);
        WrappedTask task = saturationChecks.remove(uuid);
        if (task != null) {
            task.cancel();
        }
    }

    /**
     * exhaustion 事件即时触发；定时器兜底捕捉所有其它饱和度下降途径。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExhaustion(EntityExhaustionEvent event) {
        if (event.getEntity() instanceof Player player) {
            maybeFeed(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStarvation(EntityDamageEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.STARVATION
                && event.getEntity() instanceof Player player) {
            maybeFeed(player);
        }
    }

    private void maybeFeed(Player player) {
        if (!player.isOnline() || player.getSaturation() >= SATURATION_THRESHOLD) {
            return;
        }
        // GUI 开着时 Inventory 由 Slimefun 的 BackpackListener 负责，异步改另一份实例会互相覆盖
        if (super.hasOpenView(player.getUniqueId())) return;


        long now = System.currentTimeMillis();
        Long until = feedCooldown.get(player.getUniqueId());
        if (until != null && now < until) {
            return;
        }
        feedCooldown.put(player.getUniqueId(), now + FEED_COOLDOWN_MILLIS);

        feed(player, player.getFoodLevel() <= 0);
    }

    private void feed(Player player, boolean restoreFood) {
        if (super.hasOpenView(player.getUniqueId())) return;

        // 背包里可能有多个小冰柜：第一个空的不能就此放弃，要按格子顺序逐个找下去。
        List<ItemStack> coolers = findCoolers(player);
        if (coolers.isEmpty()) return;

        UUID uuid = player.getUniqueId();
        Object token = busyLock(uuid);
        if (token == null) return;
        scheduleUnlock(uuid, token);
        tryFeedNext(player, coolers, 0, restoreFood, token);
    }

    /**
     * 逐个读取冰柜的背包，直到真的喝到一瓶（或全部都空）。
     * 整条链共用同一把 busy 锁，避免在等待异步读库时被定时器重复触发；
     * 每一步的实体改动都通过 runAtEntity 回到玩家所属的区域线程。
     * 本方法本身可以在任意线程调用，只做异步读和任务调度。
     */
    private void tryFeedNext(Player player, List<ItemStack> coolers, int index,
                             boolean restoreFood, Object token) {
        UUID uuid = player.getUniqueId();
        if (!player.isOnline() || index >= coolers.size()) {
            busyUnlock(uuid, token);
            return;
        }

        ItemStack item = coolers.get(index);
        readBackpack(item, backpack -> getResourceLib().runAtEntity(player, () -> {
            boolean drank = false;
            try {
                if (player.isOnline() && player.getSaturation() < SATURATION_THRESHOLD) {
                    drank = consume(player, item, backpack, restoreFood);
                }
            } catch (RuntimeException | LinkageError ex) {
                getResourceLib().warn("自动饮用失败", ex);
            } finally {
                if (drank || !player.isOnline() || player.getSaturation() >= SATURATION_THRESHOLD) {
                    busyUnlock(uuid, token);
                } else {
                    // 这个冰柜没果汁（或还没初始化），继续看下一个
                    tryFeedNext(player, coolers, index + 1, restoreFood, token);
                }
            }
        }), () -> tryFeedNext(player, coolers, index + 1, restoreFood, token));
    }

    /**
     * 一定运行在玩家所属的区域线程上，所以 addPotionEffect / setFoodLevel 都是安全的。
     * 返回是否真的喝掉了一瓶，供 {@link #tryFeedNext} 决定要不要继续找下一个冰柜。
     */
    private boolean consume(Player player, ItemStack coolerItem, PlayerBackpack backpack, boolean restoreFood) {
        if (!player.isOnline() || !isOurContainer(coolerItem)) {
            return false;
        }
        // 多个触发源（exhaustion / 定时器 / 饥饿伤害）可能排队，喝之前再确认一次
        if (player.getSaturation() >= SATURATION_THRESHOLD) {
            return false;
        }

        Inventory inv = backpack.getInventory();

        int slot = -1;
        int count = 0, max = Math.min(CAPACITY, WARNING_IF_ITEMS_COUNT_BELOW);
        boolean warn = false;
        for (int i = 0; i < inv.getSize(); i++) {
            if (isJuice(inv.getItem(i))) {
                if (slot == -1) slot = i;
                if (max < 0) {
                    break;
                } else {
                    count++;
                    if (count > max) break;
                }
            }
        }

        if (slot < 0) {
            return false;
        }
        if (count <= max) {
            if (coolerItem.getItemMeta() == null) {
                warn("冰柜 " + coolerItem.getType() + " 没有元数据:" + coolerItem);
            } else {
                warn = true;
            }
        }

        ItemStack juice = inv.getItem(slot);
        if (juice == null) return false;

        // 不发event了
        //        CoolerFeedPlayerEvent event = new CoolerFeedPlayerEvent(player, this, coolerItem, juice);
        //        Bukkit.getPluginManager().callEvent(event);
        //        if (event.isCancelled() || !isJuice(inv.getItem(slot))) {
        //            return false;
        //        }

        ItemStack consumed = juice; // event.getConsumedItem();
        if (consumed.getItemMeta() instanceof PotionMeta potionMeta) {
            for (PotionEffect effect : potionMeta.getCustomEffects()) {
                player.addPotionEffect(effect);
            }
        }

        player.setSaturation(SATURATION_RESTORE);
        if (restoreFood) {
            player.setFoodLevel(Math.min(20, player.getFoodLevel() + 1));
        }
        player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_DRINK, 1F, 1F);

        inv.setItem(slot, null);

        if (warn) {
            if (count <= 1) {
                player.sendMessage(String.format(NOT_ENOUGH_WARN_EMPTY, coolerItem.getItemMeta().getDisplayName()));
            } else {
                player.sendMessage(String.format(NOT_ENOUGH_WARN, coolerItem.getItemMeta().getDisplayName(), count - 1));
            }
        }

        saveBackpack(backpack, coolerItem);
        return true;
    }

    @Override

    public boolean isItemAllowed(ItemStack item, SlimefunItem itemAsSlimefunItem) {
        return itemAsSlimefunItem instanceof Juice;
    }


    @Override
    protected boolean isOurContainer(ItemStack item) {
        return Utils.isOurDevice(item, this);
    }
}
