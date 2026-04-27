package com.andgatech.jdkjarversion21enforcer;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLLoadCompleteEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        if (!JarVersionPropertyEnforcer.shouldActivate()) {
            return;
        }
        JarVersionPropertyEnforcer.enforce();
        JdkJarVersion21Enforcer.LOG
            .info(JdkJarVersion21Enforcer.MOD_NAME + " at version " + JdkJarVersion21Enforcer.VERSION);
    }

    public void init(FMLInitializationEvent event) {
        // No regular Forge registrations are needed.
    }

    public void postInit(FMLPostInitializationEvent event) {
        // Register networking, cross-mod integration, etc.
    }

    public void complete(FMLLoadCompleteEvent event) {
        // No recipes are provided by this mod.
    }

    public void serverStarting(FMLServerStartingEvent event) {
        if (!JarVersionPropertyEnforcer.shouldActivate()) {
            return;
        }
        JdkJarVersion21Enforcer.LOG.info(JdkJarVersion21Enforcer.MOD_NAME + " loaded.");
    }

    public void serverStarted(FMLServerStartedEvent event) {
        // Server-side initialization
    }

}
