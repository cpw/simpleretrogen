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
import com.google.common.collect.MapMaker;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
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
import net.minecraftforge.fml.common.event.FMLServerStoppedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.registry.GameRegistry;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;

@Mod(modid="simpleretrogen", name="Simple Retrogen")
public class WorldRetrogen {
    private Set<String> retros;

    private String marker;

    private Map<String,TargetWorldWrapper> delegates;

    private Map<World,ListMultimap<ChunkCoordIntPair,String>> pendingWork;
    private Map<World,ListMultimap<ChunkCoordIntPair,String>> completedWork;

    private ConcurrentMap<World,Semaphore> completedWorkLocks;

    private int maxPerTick;
    @EventHandler
    public void preInit(FMLPreInitializationEvent evt)
    {
        Configuration cfg = new Configuration(evt.getSuggestedConfigurationFile());
        cfg.load();
        Property property = cfg.get(Configuration.CATEGORY_GENERAL, "worldGens", new String[0]);
        property.comment = "List of IWorldGenerator instances to fire retrogen for";
        String[] retros = property.getStringList();
        this.retros = Sets.newHashSet(retros);

        property = cfg.get(Configuration.CATEGORY_GENERAL, "marker", "CPWRGMARK");
        property.comment = "Marker to apply to chunks to indicate retrogen occurred";
        this.marker = property.getString();

        property = cfg.get(Configuration.CATEGORY_GENERAL, "maxPerTick", 100);
        property.comment = "Maximum number of retrogens to run in a single tick";
        this.maxPerTick = property.getInt(100);

        if (cfg.hasChanged())
        {
            cfg.save();
        }

        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(new LastTick());
        this.delegates = Maps.newHashMap();
    }

    @EventHandler
    public void serverAboutToStart(FMLServerAboutToStartEvent evt)
    {
        this.pendingWork = new MapMaker().weakKeys().makeMap();
        this.completedWork = new MapMaker().weakKeys().makeMap();
        this.completedWorkLocks = new MapMaker().weakKeys().makeMap();

        Set<IWorldGenerator> worldGens = ObfuscationReflectionHelper.getPrivateValue(GameRegistry.class, null, "worldGenerators");
        Map<IWorldGenerator,Integer> worldGenIdx = ObfuscationReflectionHelper.getPrivateValue(GameRegistry.class, null, "worldGeneratorIndex");

        for (String retro : ImmutableSet.copyOf(retros))
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
                ListMultimap<ChunkCoordIntPair, String> pending = pendingWork.get(w);
                if (pending == null)
                {
                    return;
                }
                ImmutableList<Entry<ChunkCoordIntPair, String>> forProcessing = ImmutableList.copyOf(Iterables.limit(pending.entries(), maxPerTick + 1));
                for (Entry<ChunkCoordIntPair, String> entry : forProcessing)
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
        public void generate(Random random, int chunkX, int chunkZ, World world, IChunkProvider chunkGenerator, IChunkProvider chunkProvider)
        {
            FMLLog.fine("Passing generation for %s through to underlying generator", tag);
            delegate.generate(random, chunkX, chunkZ, world, chunkGenerator, chunkProvider);
            ChunkCoordIntPair chunkCoordIntPair = new ChunkCoordIntPair(chunkX, chunkZ);
            completeRetrogen(chunkCoordIntPair, world, tag);
        }
    }

    @SubscribeEvent
    public void onChunkLoad(ChunkDataEvent.Load chunkevt)
    {
        World w = chunkevt.world;
        if (!(w instanceof WorldServer))
        {
            return;
        }
        getSemaphoreFor(w);

        Chunk chk = chunkevt.getChunk();
        Set<String> existingGens = Sets.newHashSet();
        NBTTagCompound data = chunkevt.getData();
        NBTTagCompound marker = data.getCompoundTag(this.marker);
        NBTTagList tagList = marker.getTagList("list",8);
        for (int i = 0; i < tagList.tagCount(); i++)
        {
            existingGens.add(tagList.getStringTagAt(i));
        }

        SetView<String> difference = Sets.difference(retros, existingGens);
        for (String retro : difference)
        {
            queueRetrogen(retro, w, chk.getChunkCoordIntPair());
        }

        for (String retro : existingGens)
        {
            completeRetrogen(chk.getChunkCoordIntPair(), w, retro);
        }
    }

    @SubscribeEvent
    public void onChunkSave(ChunkDataEvent.Save chunkevt)
    {
        World w = chunkevt.world;
        if (!(w instanceof WorldServer))
        {
            return;
        }
        getSemaphoreFor(w).acquireUninterruptibly();
        try
        {
            if (completedWork.containsKey(w))
            {
                ListMultimap<ChunkCoordIntPair, String> doneChunks = completedWork.get(w);
                List<String> list = doneChunks.get(chunkevt.getChunk().getChunkCoordIntPair());
                if (list.isEmpty())
                    return;
                NBTTagCompound data = chunkevt.getData();
                NBTTagCompound retro = new NBTTagCompound();
                NBTTagList lst = new NBTTagList();
                retro.setTag("list", lst);
                data.setTag(this.marker, retro);
                for (String retrogen : list)
                {
                    lst.appendTag(new NBTTagString(retrogen));
                }
            }
        }
        finally
        {
            getSemaphoreFor(w).release();
        }
    }

    private void queueRetrogen(String retro, World world, ChunkCoordIntPair chunkCoords)
    {
        if (world instanceof WorldServer)
        {
            ListMultimap<ChunkCoordIntPair, String> currentWork = pendingWork.get(world);
            if (currentWork == null)
            {
                currentWork = ArrayListMultimap.create();
                pendingWork.put(world, currentWork);
            }

            currentWork.put(chunkCoords, retro);
        }
    }
    private void completeRetrogen(ChunkCoordIntPair chunkCoords, World world, String marker)
    {
        ListMultimap<ChunkCoordIntPair, String> pendingMap = pendingWork.get(world);
        if (pendingMap != null && pendingMap.containsKey(chunkCoords))
        {
            pendingMap.remove(chunkCoords, marker);
        }

        getSemaphoreFor(world).acquireUninterruptibly();
        try
        {
            ListMultimap<ChunkCoordIntPair, String> completedMap = completedWork.get(world);
            if (completedMap == null)
            {
                completedMap = ArrayListMultimap.create();
                completedWork.put(world, completedMap);
            }

            completedMap.put(chunkCoords, marker);
        }
        finally
        {
            getSemaphoreFor(world).release();
        }
    }

    private void runRetrogen(WorldServer world, ChunkCoordIntPair chunkCoords, String retro)
    {
        long worldSeed = world.getSeed();
        Random fmlRandom = new Random(worldSeed);
        long xSeed = fmlRandom.nextLong() >> 2 + 1L;
        long zSeed = fmlRandom.nextLong() >> 2 + 1L;
        long chunkSeed = (xSeed * chunkCoords.chunkXPos + zSeed * chunkCoords.chunkZPos) ^ worldSeed;

        fmlRandom.setSeed(chunkSeed);
        ChunkProviderServer providerServer = world.theChunkProviderServer;
        IChunkProvider generator = ObfuscationReflectionHelper.getPrivateValue(ChunkProviderServer.class, providerServer, "field_73246_d", "currentChunkProvider");
        delegates.get(retro).delegate.generate(fmlRandom, chunkCoords.chunkXPos, chunkCoords.chunkZPos, world, generator, providerServer);
        FMLLog.fine("Retrogenerated chunk for %s", retro);
        completeRetrogen(chunkCoords, world, retro);
    }
}
