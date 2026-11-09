package me.cyantangerine.slimefunaddon;

import com.tcoded.folialib.FoliaLib;
import io.github.thebusybiscuit.slimefun4.core.attributes.Radioactivity;
import io.github.thebusybiscuit.slimefun4.implementation.SlimefunItems;
import io.github.thebusybiscuit.slimefun4.utils.LoreBuilder;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.api.SlimefunAddon;
import io.github.thebusybiscuit.slimefun4.libraries.dough.config.Config;
import io.github.thebusybiscuit.slimefun4.libraries.dough.items.CustomItemStack;

import java.util.logging.Level;

public class CyanTestAddon extends JavaPlugin implements SlimefunAddon {

    private FoliaLib foliaLib;

    public FoliaLib getFoliaLib() {
        return foliaLib;
    }

    @Override
    public void onEnable() {
        foliaLib = new FoliaLib(this);


        // 从 config.yml 中读取插件配置
        Config cfg = new Config(this);

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

        // 可视小冰柜：物品材质为 BUNDLE，容量会像收纳袋一样画在图标上
        ItemStack[] coolerRecipe = {
                new ItemStack(Material.ICE), new ItemStack(Material.ICE), new ItemStack(Material.ICE),
                new ItemStack(Material.ICE), SlimefunItems.COOLER, new ItemStack(Material.ICE),
                new ItemStack(Material.ICE), new ItemStack(Material.ICE), new ItemStack(Material.ICE)
        };
        VisableCooler cooler = new VisableCooler(
                VisableCooler.DEFAULT_SIZE, itemGroup, VisableCooler.createItemStack(),
                RecipeType.ENHANCED_CRAFTING_TABLE, coolerRecipe);
        cooler.register(this);

        getLogger().log(Level.WARNING, "cyan 插件已启用！");
    }

    @Override
    public void onDisable() {
        foliaLib.getScheduler().cancelAllTasks();
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
