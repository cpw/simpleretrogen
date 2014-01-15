package cpw.mods.retro;

import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.logging.Logger;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.common.Configuration;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.Property;
import net.minecraftforge.event.ForgeSubscribe;
import net.minecraftforge.event.world.ChunkDataEvent;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.MapMaker;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;
import com.google.common.collect.UnmodifiableIterator;

import cpw.mods.fml.common.FMLLog;
import cpw.mods.fml.common.ITickHandler;
import cpw.mods.fml.common.IWorldGenerator;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.Mod.EventHandler;
import cpw.mods.fml.common.Mod.Instance;
import cpw.mods.fml.common.ObfuscationReflectionHelper;
import cpw.mods.fml.common.TickType;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerAboutToStartEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;
import cpw.mods.fml.common.network.NetworkMod;
import cpw.mods.fml.common.registry.GameRegistry;
import cpw.mods.fml.common.registry.TickRegistry;
import cpw.mods.fml.relauncher.Side;

@Mod(modid="simpleretrogen", name="Simple Retrogen", version="1.0")
public class WorldRetrogen {
    private Logger log;

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

        this.log = evt.getModLog();
        MinecraftForge.EVENT_BUS.register(this);
        TickRegistry.registerTickHandler(new LastTick(), Side.SERVER);
        this.delegates = Maps.newHashMap();
    }

    @EventHandler
    public void serverAboutToStart(FMLServerAboutToStartEvent evt)
    {
        this.pendingWork = new MapMaker().weakKeys().makeMap();
        this.completedWork = new MapMaker().weakKeys().makeMap();
        this.completedWorkLocks = new MapMaker().weakKeys().makeMap();

        Set<IWorldGenerator> worldGens = ObfuscationReflectionHelper.getPrivateValue(GameRegistry.class, null, "worldGenerators");

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

    public void serverStopped(FMLServerStoppedEvent evt)
    {
        Set<IWorldGenerator> worldGens = ObfuscationReflectionHelper.getPrivateValue(GameRegistry.class, null, "worldGenerators");

        for (TargetWorldWrapper tww : delegates.values())
        {
            worldGens.remove(tww);
            worldGens.add(tww.delegate);
        }

        delegates.clear();
    }

    private Semaphore getSemaphoreFor(World w)
    {
        completedWorkLocks.putIfAbsent(w, new Semaphore(1));
        return completedWorkLocks.get(w);
    }

    private class LastTick implements ITickHandler {
        private int counter = 0;
        @Override
        public void tickStart(EnumSet<TickType> type, Object... tickData)
        {
            counter = 0;
            World w = (World) tickData[0];
            getSemaphoreFor(w);
        }

        @Override
        public void tickEnd(EnumSet<TickType> type, Object... tickData)
        {
            World w = (World) tickData[0];
            if (!(w instanceof WorldServer))
            {
                return;
            }
            ListMultimap<ChunkCoordIntPair, String> pending = pendingWork.get(w);
            if (pending == null)
            {
                return;
            }
            ImmutableList<Entry<ChunkCoordIntPair, String>> forProcessing = ImmutableList.copyOf(Iterables.limit(pending.entries(),maxPerTick+1));
            for (Entry<ChunkCoordIntPair, String> entry : forProcessing)
            {
                if (counter ++ > maxPerTick)
                {
                    FMLLog.fine("Completed %d retrogens this tick. There are %d left for world %s", counter, pending.size(), w.getWorldInfo().getWorldName());
                    return;
                }
                runRetrogen((WorldServer) w, entry.getKey(), entry.getValue());
            }
        }

        @Override
        public EnumSet<TickType> ticks()
        {
            return EnumSet.of(TickType.WORLD);
        }

        @Override
        public String getLabel()
        {
            return "WorldRetrogen";
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

    @ForgeSubscribe
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
        NBTTagList tagList = marker.getTagList("list");
        for (int i = 0; i < tagList.tagCount(); i++)
        {
            NBTTagString tagAt = (NBTTagString) tagList.tagAt(i);
            existingGens.add(tagAt.data);
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

    @ForgeSubscribe
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
                data.setCompoundTag(this.marker, retro);
                for (String retrogen : list)
                {
                    lst.appendTag(new NBTTagString("",retrogen));
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
