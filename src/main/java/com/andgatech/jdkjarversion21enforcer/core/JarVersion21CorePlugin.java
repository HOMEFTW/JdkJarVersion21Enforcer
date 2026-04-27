package com.andgatech.jdkjarversion21enforcer.core;

import java.util.Map;

import com.andgatech.jdkjarversion21enforcer.JarVersionPropertyEnforcer;

import cpw.mods.fml.relauncher.IFMLLoadingPlugin;

@IFMLLoadingPlugin.MCVersion("1.7.10")
@IFMLLoadingPlugin.SortingIndex(Integer.MIN_VALUE)
@IFMLLoadingPlugin.TransformerExclusions({ "com.andgatech.jdkjarversion21enforcer." })
public class JarVersion21CorePlugin implements IFMLLoadingPlugin {

    static {
        JarVersionPropertyEnforcer.enforce();
    }

    public JarVersion21CorePlugin() {
        JarVersionPropertyEnforcer.enforce();
    }

    @Override
    public String[] getASMTransformerClass() {
        return new String[0];
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {}

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
