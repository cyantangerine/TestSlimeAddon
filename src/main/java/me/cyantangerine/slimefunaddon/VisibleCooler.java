package me.cyantangerine.slimefunaddon;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import javax.annotation.ParametersAreNonnullByDefault;
import org.bukkit.inventory.ItemStack;

/**
 * 拼写正确的别名，实现全部在 {@link VisableCooler} 里。
 * 保留这个类是为了不破坏已经引用 VisibleCooler 的代码。
 */
public class VisibleCooler extends VisableCooler {

    @ParametersAreNonnullByDefault
    public VisibleCooler(int size, ItemGroup itemGroup, SlimefunItemStack item, RecipeType recipeType, ItemStack[] recipe) {
        super(size, itemGroup, item, recipeType, recipe);
    }
}
