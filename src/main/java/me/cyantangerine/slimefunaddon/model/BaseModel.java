package me.cyantangerine.slimefunaddon.model;

public abstract class BaseModel {
    private ResourceLib resourceLib;
    public void init(ResourceLib resourceLib) {
        this.resourceLib = resourceLib;
    }
    public ResourceLib getResourceLib() {
        if(resourceLib == null) throw new NullPointerException("ResourceLib还没有被初始化！");
        return resourceLib;
    }
}
