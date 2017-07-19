import net.minecraft.world.World;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.gen.IChunkGenerator;
import net.minecraftforge.fml.common.IWorldGenerator;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.registry.GameRegistry;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;

import java.util.Random;

/**
 * Created by cpw on 23/06/16.
 */
@Mod(modid="RG-testgen", version="1.0")
public class TestGenerator
{
    @Mod.EventHandler
    public void preinit(FMLPreInitializationEvent init)
    {
        final Logger modLog = init.getModLog();
        IWorldGenerator gen = new IWorldGenerator() {
            @Override
            public void generate(Random random, int chunkX, int chunkZ, World world, IChunkGenerator chunkGenerator, IChunkProvider chunkProvider)
            {
                modLog.log(Level.INFO, "Calling!");
            }
        };
        GameRegistry.registerWorldGenerator(gen, 10);
    }
}
