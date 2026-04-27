package com.andgatech.jdkjarversion21enforcer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLLoadCompleteEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

@Mod(
    modid = JdkJarVersion21Enforcer.MODID,
    version = Tags.VERSION,
    name = JdkJarVersion21Enforcer.MOD_NAME,
    acceptedMinecraftVersions = "[1.7.10]")
public class JdkJarVersion21Enforcer {

    public static final String MODID = "jdkjarversion21enforcer";
    public static final String MOD_ID = MODID;
    public static final String MOD_NAME = "JDK Jar Version 21 Enforcer";
    public static final String VERSION = Tags.VERSION;
    public static final String RESOURCE_ROOT_ID = MODID;

    public static final Logger LOG = LogManager.getLogger(MODID);

    @Mod.Instance
    public static JdkJarVersion21Enforcer instance;

    @SidedProxy(
        clientSide = "com.andgatech.jdkjarversion21enforcer.ClientProxy",
        serverSide = "com.andgatech.jdkjarversion21enforcer.CommonProxy")
    public static CommonProxy proxy;

    // region FML Events
    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit(event);
    }

    @Mod.EventHandler
    public void completeInit(FMLLoadCompleteEvent event) {
        proxy.complete(event);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        proxy.serverStarting(event);
    }

    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        proxy.serverStarted(event);
    }
    // endregion

}
