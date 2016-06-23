/*
 *     Copyright © 2016 cpw
 *     This file is part of Simpleretrogen.
 *
 *     Simpleretrogen is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Simpleretrogen is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with Simpleretrogen.  If not, see <http://www.gnu.org/licenses/>.
 */

package cpw.mods.retro;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.MapMaker;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkGenerator;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;
import net.minecraftforge.event.world.ChunkDataEvent;
import net.minecraftforge.fml.common.FMLLog;
import net.minecraftforge.fml.common.IWorldGenerator;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerAboutToStartEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.registry.GameRegistry;
import org.apache.logging.log4j.Level;

import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;

@Mod(modid="simpleretrogen", name="Simple Retrogen", acceptableRemoteVersions="*", acceptedMinecraftVersions = "[1.9,1.11)")
@ParametersAreNonnullByDefault
public class WorldRetrogen {
    private List<Marker> markers = Lists.newArrayList();
    private Map<String,TargetWorldWrapper> delegates;

    private Map<World,ListMultimap<ChunkPos,String>> pendingWork;
    private Map<World,ListMultimap<ChunkPos,String>> completedWork;

    private ConcurrentMap<World,Semaphore> completedWorkLocks;

    private int maxPerTick;
    private Map<String,String> retros = Maps.newHashMap();

    private static class Marker {
        private final String marker;
        private final Set<String> classes;

        Marker(String marker, Set<String> classes) {
            this.marker = marker;
            this.classes = classes;
        }
    }
    @EventHandler
    public void preInit(FMLPreInitializationEvent evt)
    {
        Configuration cfg = new Configuration(evt.getSuggestedConfigurationFile(), null, true);
        cfg.load();

        Property property = cfg.get(Configuration.CATEGORY_GENERAL, "maxPerTick", 100);
        property.setComment("Maximum number of retrogens to run in a single tick");
        this.maxPerTick = property.getInt(100);
        Property amProperty = cfg.get(Configuration.CATEGORY_GENERAL, "markerList", new String[0]);
        amProperty.setComment("Active markers");
        final List<String> activeMarkerList = Lists.newArrayList(amProperty.getStringList());
        Set<String> categories = cfg.getCategoryNames();

        if (categories.size() == 1) // only the general category - version 1 of config file
        {
            property = cfg.get(Configuration.CATEGORY_GENERAL, "worldGens", new String[0]);
            String[] retros = property.getStringList();
            property = cfg.get(Configuration.CATEGORY_GENERAL, "marker", "CPWRGMARK");
            Marker m = new Marker(property.getString(), Sets.newHashSet(retros));
            this.markers.add(m);
        }
        else
        {
            for (String marker : activeMarkerList) {
                if (categories.contains(marker)) {
                    final Property property1 = cfg.get(marker, "worldGens", new String[0]);
                    property1.setComment("World Generator classes for marker");
                    this.markers.add(new Marker(marker, Sets.newHashSet(property1.getStringList())));
                    cfg.getCategory(marker).setComment("Marker definition\nYou can create as many of these as you wish\nActivate by adding to active list");
                } else {
                    evt.getModLog().log(Level.INFO, "Ignoring missing marker definition for active marker %s", marker);
                }
            }
        }

        // clean up leftovers
        cfg.getCategory(Configuration.CATEGORY_GENERAL).remove("worldGens");
        cfg.getCategory(Configuration.CATEGORY_GENERAL).remove("marker");

        for (Marker m : markers) {
            for (String clz : m.classes) {
                if (retros.put(clz, m.marker) != null) {
                    evt.getModLog().log(Level.ERROR, "Configuration error, duplicate class for multiple markers found : %s", clz);
                }
            }
            if (!categories.contains(m.marker)) {
                Property p = cfg.get(m.marker, "worldGens",new String[0]);
                p.setComment("World Generator classes for marker");
                p.set(m.classes.toArray(new String[0]));
                cfg.getCategory(m.marker).setComment("Marker definition\nYou can create as many of these as you wish\nActivate by adding to active list");
                if (!activeMarkerList.contains(m.marker)) {
                    activeMarkerList.add(m.marker);
                    amProperty.set(activeMarkerList.toArray(new String[0]));
                }
            }
        }

        if (cfg.hasChanged())
        {
            cfg.save();
        }

        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(new LastTick());
        this.delegates = Maps.newHashMap();
    }

    @EventHandler
    public void serverStarting(FMLServerStartingEvent evt) {
        evt.registerServerCommand(new CommandBase()
        {
            @Override
            @Nonnull
            public String getCommandName()
            {
                return "listretrogenclasstargets";
            }

            @Override
            @Nonnull
            public String getCommandUsage(ICommandSender sender)
            {
                return "List retrogens";
            }

            @Override
            public int getRequiredPermissionLevel()
            {
                return 0;
            }

            @Override
            public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException
            {
                Set<IWorldGenerator> worldGens = ObfuscationReflectionHelper.getPrivateValue(GameRegistry.class, null, "worldGenerators");
                List<String> targets = Lists.newArrayList();
                for (IWorldGenerator worldGen : worldGens) {
                    if (!(worldGen instanceof TargetWorldWrapper))
                    {
                        targets.add(worldGen.getClass().getName());
                    }
                }
                if (targets.isEmpty()) {
                    sender.addChatMessage(new TextComponentString("There are no retrogen target classes"));
                } else {
                    sender.addChatMessage(new TextComponentString(CommandBase.joinNiceStringFromCollection(targets)));
                }
            }
        });
    }
    @EventHandler
    public void serverAboutToStart(FMLServerAboutToStartEvent evt)
    {
        this.pendingWork = new MapMaker().weakKeys().makeMap();
        this.completedWork = new MapMaker().weakKeys().makeMap();
        this.completedWorkLocks = new MapMaker().weakKeys().makeMap();

        Set<IWorldGenerator> worldGens = ObfuscationReflectionHelper.getPrivateValue(GameRegistry.class, null, "worldGenerators");
        Map<IWorldGenerator,Integer> worldGenIdx = ObfuscationReflectionHelper.getPrivateValue(GameRegistry.class, null, "worldGeneratorIndex");

        for (String retro : ImmutableSet.copyOf(retros.keySet()))
        {
            if (!delegates.containsKey(retro))
            {
                FMLLog.info("Substituting worldgenerator %s with delegate", retro);
                for (Iterator<IWorldGenerator> iterator = worldGens.iterator(); iterator.hasNext();)
                {
                    IWorldGenerator wg = iterator.next();
                    if (wg.getClass().getName().equals(retro))
                    {
                        iterator.remove();
                        TargetWorldWrapper tww = new TargetWorldWrapper();
                        tww.delegate = wg;
                        tww.tag = retro;
                        worldGens.add(tww);
                        Integer idx = worldGenIdx.remove(wg);
                        worldGenIdx.put(tww, idx);
                        FMLLog.info("Successfully substituted %s with delegate", retro);
                        delegates.put(retro, tww);
                        break;
                    }
                }

                if (!delegates.containsKey(retro))
                {
                    FMLLog.warning("WorldRetrogen was not able to locate world generator class %s, it will be skipped, found %s", retro, worldGens);
                    retros.remove(retro);
                }
            }
        }
    }

    @EventHandler
    public void serverStopped(FMLServerStoppedEvent evt)
    {
        Set<IWorldGenerator> worldGens = ObfuscationReflectionHelper.getPrivateValue(GameRegistry.class, null, "worldGenerators");
        Map<IWorldGenerator,Integer> worldGenIdx = ObfuscationReflectionHelper.getPrivateValue(GameRegistry.class, null, "worldGeneratorIndex");

        for (TargetWorldWrapper tww : delegates.values())
        {
            worldGens.remove(tww);
            Integer idx = worldGenIdx.remove(tww);
            worldGens.add(tww.delegate);
            worldGenIdx.put(tww.delegate,idx);
        }

        delegates.clear();
    }

    private Semaphore getSemaphoreFor(World w)
    {
        completedWorkLocks.putIfAbsent(w, new Semaphore(1));
        return completedWorkLocks.get(w);
    }

    private class LastTick {
        private int counter = 0;
        @SubscribeEvent
        public void tickStart(TickEvent.WorldTickEvent tick)
        {
            World w = tick.world;
            if (!(w instanceof WorldServer))
            {
                return;
            }
            if (tick.phase == TickEvent.Phase.START)
            {
                counter = 0;
                getSemaphoreFor(w);
            }
            else
            {
                ListMultimap<ChunkPos, String> pending = pendingWork.get(w);
                if (pending == null)
                {
                    return;
                }
                ImmutableList<Entry<ChunkPos, String>> forProcessing = ImmutableList.copyOf(Iterables.limit(pending.entries(), maxPerTick + 1));
                for (Entry<ChunkPos, String> entry : forProcessing)
                {
                    if (counter++ > maxPerTick)
                    {
                        FMLLog.fine("Completed %d retrogens this tick. There are %d left for world %s", counter, pending.size(), w.getWorldInfo().getWorldName());
                        return;
                    }
                    runRetrogen((WorldServer)w, entry.getKey(), entry.getValue());
                }
            }
        }
    }

    private class TargetWorldWrapper implements IWorldGenerator {
        private IWorldGenerator delegate;
        private String tag;

        @Override
        public void generate(Random random, int chunkX, int chunkZ, World world, IChunkGenerator chunkGenerator, IChunkProvider chunkProvider)
        {
            FMLLog.fine("Passing generation for %s through to underlying generator", tag);
            delegate.generate(random, chunkX, chunkZ, world, chunkGenerator, chunkProvider);
            ChunkPos chunkCoordIntPair = new ChunkPos(chunkX, chunkZ);
            completeRetrogen(chunkCoordIntPair, world, tag);
        }
    }

    @SubscribeEvent
    public void onChunkLoad(ChunkDataEvent.Load chunkevt)
    {
        World w = chunkevt.getWorld();
        if (!(w instanceof WorldServer))
        {
            return;
        }
        getSemaphoreFor(w);

        Chunk chk = chunkevt.getChunk();
        Set<String> existingGens = Sets.newHashSet();
        NBTTagCompound data = chunkevt.getData();
        for (Marker m : markers)
        {
            NBTTagCompound marker = data.getCompoundTag(m.marker);
            NBTTagList tagList = marker.getTagList("list", 8);
            for (int i = 0; i < tagList.tagCount(); i++)
            {
                existingGens.add(tagList.getStringTagAt(i));
            }

            SetView<String> difference = Sets.difference(m.classes, existingGens);
            for (String retro : difference)
            {
                if (retros.containsKey(retro))
                {
                    queueRetrogen(retro, w, chk.getChunkCoordIntPair());
                }
            }
        }

        for (String retro : existingGens)
        {
            completeRetrogen(chk.getChunkCoordIntPair(), w, retro);
        }
    }

    @SubscribeEvent
    public void onChunkSave(ChunkDataEvent.Save chunkevt)
    {
        World w = chunkevt.getWorld();
        if (!(w instanceof WorldServer))
        {
            return;
        }
        getSemaphoreFor(w).acquireUninterruptibly();
        try
        {
            if (completedWork.containsKey(w))
            {
                ListMultimap<ChunkPos, String> doneChunks = completedWork.get(w);
                List<String> retroClassList = doneChunks.get(chunkevt.getChunk().getChunkCoordIntPair());
                if (retroClassList.isEmpty())
                    return;
                NBTTagCompound data = chunkevt.getData();
                for (String retroClass : retroClassList)
                {
                    String marker = retros.get(retroClass);
                    if (marker == null)
                    {
                        FMLLog.log(Level.DEBUG, "Encountered retrogen class %s with no existing marker, removing from chunk. You probably removed it from the active configuration", retroClass);
                        continue;
                    }
                    NBTTagList lst;
                    if (data.hasKey(marker)) {
                        lst = data.getCompoundTag(marker).getTagList("list", 8);
                    } else {
                        NBTTagCompound retro = new NBTTagCompound();
                        lst = new NBTTagList();
                        retro.setTag("list", lst);
                        data.setTag(marker, retro);
                    }
                    lst.appendTag(new NBTTagString(retroClass));
                }
            }
        }
        finally
        {
            getSemaphoreFor(w).release();
        }
    }

    private void queueRetrogen(String retro, World world, ChunkPos chunkCoords)
    {
        if (world instanceof WorldServer)
        {
            ListMultimap<ChunkPos, String> currentWork = pendingWork.get(world);
            if (currentWork == null)
            {
                currentWork = ArrayListMultimap.create();
                pendingWork.put(world, currentWork);
            }

            currentWork.put(chunkCoords, retro);
        }
    }
    private void completeRetrogen(ChunkPos chunkCoords, World world, String retroClass)
    {
        ListMultimap<ChunkPos, String> pendingMap = pendingWork.get(world);
        if (pendingMap != null && pendingMap.containsKey(chunkCoords))
        {
            pendingMap.remove(chunkCoords, retroClass);
        }

        getSemaphoreFor(world).acquireUninterruptibly();
        try
        {
            ListMultimap<ChunkPos, String> completedMap = completedWork.get(world);
            if (completedMap == null)
            {
                completedMap = ArrayListMultimap.create();
                completedWork.put(world, completedMap);
            }

            completedMap.put(chunkCoords, retroClass);
        }
        finally
        {
            getSemaphoreFor(world).release();
        }
    }

    private void runRetrogen(WorldServer world, ChunkPos chunkCoords, String retroClass)
    {
        long worldSeed = world.getSeed();
        Random fmlRandom = new Random(worldSeed);
        long xSeed = fmlRandom.nextLong() >> 2 + 1L;
        long zSeed = fmlRandom.nextLong() >> 2 + 1L;
        long chunkSeed = (xSeed * chunkCoords.chunkXPos + zSeed * chunkCoords.chunkZPos) ^ worldSeed;

        fmlRandom.setSeed(chunkSeed);
        ChunkProviderServer providerServer = world.getChunkProvider();
        IChunkGenerator generator = ObfuscationReflectionHelper.getPrivateValue(ChunkProviderServer.class, providerServer, "field_186029_c", "chunkGenerator");
        delegates.get(retroClass).delegate.generate(fmlRandom, chunkCoords.chunkXPos, chunkCoords.chunkZPos, world, generator, providerServer);
        FMLLog.fine("Retrogenerated chunk for %s", retroClass);
        completeRetrogen(chunkCoords, world, retroClass);
    }
}
