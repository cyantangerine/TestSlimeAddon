package me.cyantangerine.slimefunaddon;

import io.github.thebusybiscuit.slimefun4.core.attributes.Radioactivity;
import io.github.thebusybiscuit.slimefun4.implementation.SlimefunItems;
import io.github.thebusybiscuit.slimefun4.utils.LoreBuilder;
import me.cyantangerine.slimefunaddon.item.FireCake;
import me.cyantangerine.slimefunaddon.item.SmartCooler;
import me.cyantangerine.slimefunaddon.model.ResourceLib;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.api.SlimefunAddon;
import io.github.thebusybiscuit.slimefun4.libraries.dough.config.Config;
import io.github.thebusybiscuit.slimefun4.libraries.dough.items.CustomItemStack;

import java.util.logging.Level;

public class CyanTestAddon extends JavaPlugin implements SlimefunAddon {
    private ResourceLib resourceLib;
    public ResourceLib getResourceLib() {
        return resourceLib;
    }

    @Override
    public void onEnable() {
        resourceLib = new ResourceLib(this);
        // 从 config.yml 中读取插件配置
        Config cfg = resourceLib.getConfig();
        if (cfg.getBoolean("options.auto-update")) {
            // 你可以在这里添加自动更新功能
        }

        NamespacedKey categoryId = new NamespacedKey(this, "cool_category");
        CustomItemStack categoryItem = new CustomItemStack(Material.DIAMOND, "&4非常炫酷的分类");
// 创建物品组 ItemGroup
        ItemGroup itemGroup = new ItemGroup(categoryId, categoryItem);
// 粘液物品 SlimefunItem 的物品 SlimefunItemStack
        SlimefunItemStack itemStack = new SlimefunItemStack("FIRE_CAKE", Material.CAKE, "&4火焰蛋糕", "", LoreBuilder.radioactive(Radioactivity.HIGH), LoreBuilder.HAZMAT_SUIT_REQUIRED);
// 3x3 的有序合成配方
        ItemStack[] recipe = {
                new ItemStack(Material.DIAMOND),    null,                               new ItemStack(Material.DIAMOND),
                null,                               SlimefunItems.CARBONADO,            null,
                new ItemStack(Material.DIAMOND),    null,                               new ItemStack(Material.DIAMOND)
        };
// 我们现在正在使用自己的类
        FireCake cake = new FireCake(itemGroup, itemStack, RecipeType.ENHANCED_CRAFTING_TABLE, recipe);
        cake.register(this);


        NamespacedKey category2Id = new NamespacedKey(this, "smart_devices");
        CustomItemStack category2Item = new CustomItemStack(SmartCooler.HEAD_ICON, "&4智能设备");
// 创建物品组 ItemGroup
        ItemGroup item2Group = new ItemGroup(category2Id, category2Item);

        // 可视小冰柜：物品材质为 BUNDLE，容量会像收纳袋一样画在图标上。
        // 中心用官方 COOLER 升级，产物会继承旧背包（Slimefun 的 upgradeBackpack 语义）。
        // 这条路径在 Folia 上曾因回调落在全局线程而报 "Cannot read world asynchronously"，
        // 已在本地 Slimefun fork 的 AbstractCraftingTable 中修复，请勿为了绕开它而改配方。
        ItemStack[] coolerRecipe = {
                SlimefunItems.CLOTH, SlimefunItems.SYNTHETIC_SAPPHIRE, SlimefunItems.CLOTH,
                SlimefunItems.SOLDER_INGOT, SlimefunItems.COOLER, SlimefunItems.SOLDER_INGOT,
                SlimefunItems.GOLD_16K, SlimefunItems.ADVANCED_CIRCUIT_BOARD, SlimefunItems.GOLD_16K
        };
        SmartCooler cooler = new SmartCooler(
                SmartCooler.DEFAULT_SIZE, item2Group, SmartCooler.createItemStack(),
                RecipeType.ENHANCED_CRAFTING_TABLE, coolerRecipe
        );
        cooler.register(this);

        getLogger().log(Level.WARNING, "cyan 插件已启用！");
    }

    @Override
    public void onDisable() {
        resourceLib.getFoliaLib().getScheduler().cancelAllTasks();
        // 禁用插件的逻辑...
        getLogger().log(Level.WARNING, "cyan 插件已禁用！");
    }

    @Override
    public String getBugTrackerURL() {
        // 你可以在这里返回你的问题追踪器的网址，而不是 null
        return null;
    }

    @Override
    public JavaPlugin getJavaPlugin() {
        /*
         * 你需要返回对你插件的引用。
         * 如果这是你插件的主类，只需要返回 "this" 即可。
         */
        return this;
    }

}
