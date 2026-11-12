package me.cyantangerine.slimefunaddon.model;

import com.tcoded.folialib.wrapper.task.WrappedTask;

import java.util.function.Consumer;

@FunctionalInterface
public interface FoliaTask extends Consumer<WrappedTask> {
}
