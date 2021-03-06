/*
 *  This file is part of Cubic Chunks Mod, licensed under the MIT License (MIT).
 *
 *  Copyright (c) 2015 contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 */
package cubicchunks.server;

import com.google.common.collect.Maps;
import cubicchunks.CubicChunks;
import cubicchunks.server.chunkio.CubeIO;
import cubicchunks.util.AddressTools;
import cubicchunks.util.Coords;
import cubicchunks.util.CubeCoords;
import cubicchunks.world.ICubeCache;
import cubicchunks.world.ICubicWorldServer;
import cubicchunks.world.column.Column;
import cubicchunks.world.cube.Cube;
import cubicchunks.world.dependency.DependencyManager;
import cubicchunks.worldgen.GeneratorStage;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderOverworld;
import net.minecraft.world.gen.ChunkProviderServer;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import static cubicchunks.server.ServerCubeCache.LoadType.FORCE_LOAD;
import static cubicchunks.server.ServerCubeCache.LoadType.LOAD_ONLY;
import static cubicchunks.server.ServerCubeCache.LoadType.LOAD_OR_GENERATE;

/**
 * This is CubicChunks equivalent of ChunkProviderServer, it loads and unloads Cubes and Columns.
 * <p>
 * There are a few necessary changes to the way vanilla methods work:
 * * Because loading a Chunk (Column) doesn't make much sense with CubicChunks,
 * all methods that load Chunks, actually load  an empry column with no blocks in it
 * (there may be some entities that are not in any Cube yet).
 * * dropChunk method is not supported. Columns are unloaded automatically when the last cube is unloaded
 */
public class ServerCubeCache extends ChunkProviderServer implements ICubeCache {

	private static final Logger log = CubicChunks.LOGGER;

	public static final int SPAWN_LOAD_RADIUS = 12; // highest render distance is 32

	private ICubicWorldServer worldServer;
	private CubeIO cubeIO;
	private HashMap<Long, Column> loadedColumns;
	private Queue<CubeCoords> cubesToUnload;
	private DependencyManager dependencyManager;

	/**
	 * Cube generator can add cubes into world that are "linked" with other cube -
	 * Usually when generating one cube requires generating more than just neighbors.
	 * <p>
	 * This is a mapping of which cubes are linked with which other cubes,
	 * allows to automatically unload these additional cubes.
	 */
	private Map<Cube, Set<Cube>> forceAdded;
	private Map<Cube, Set<Cube>> forceAddedReverse;

	public ServerCubeCache(ICubicWorldServer worldServer) {
		//TODO: Replace add ChunkGenerator argument and use chunk generator object for generating terrain?
		//ChunkGenerator has to exist for mob spawning to work
		super((WorldServer) worldServer, worldServer.getSaveHandler().getChunkLoader(worldServer.getProvider()),
				new ChunkProviderOverworld((World) worldServer, worldServer.getSeed(), false, null));

		this.worldServer = worldServer;
		this.cubeIO = new CubeIO(worldServer);
		this.loadedColumns = Maps.newHashMap();
		this.cubesToUnload = new ArrayDeque<>();
		this.forceAdded = new HashMap<>();
		this.forceAddedReverse = new HashMap<>();
		this.dependencyManager = new DependencyManager(this);
	}

	public DependencyManager getDependencyManager() {
		return this.dependencyManager;
	}

	@Override
	public Collection<Chunk> getLoadedChunks() {
		return (Collection<Chunk>) (Object) this.loadedColumns.values();
	}

	@Override
	public void unload(Chunk chunk) {
		//ignore, ChunkGc unloads cubes
	}

	@Override
	public void unloadAllChunks() {
		// unload all the cubes in the columns
		for (Column column : this.loadedColumns.values()) {
			for (Cube cube : column.getAllCubes()) {
				this.cubesToUnload.add(cube.getCoords());
			}
		}
	}

	/**
	 * Vanilla method, returns a Chunk (Column) only of it's already loaded.
	 * Same as getColumn(cubeX, cubeZ)
	 */
	@Override
	@Nullable
	public Chunk getLoadedChunk(int cubeX, int cubeZ) {
		return this.getColumn(cubeX, cubeZ);
	}

	/**
	 * Loads Chunk (Column) if it can be loaded from disk, or returns already loaded one.
	 * Doesn't generate new Columns.
	 */
	@Override
	@Nullable
	public Column loadChunk(int cubeX, int cubeZ) {
		return this.loadColumn(cubeX, cubeZ, LOAD_ONLY);
	}

	/**
	 * Load chunk asynchronously. Currently CubicChunks only loads synchronously.
	 */
	@Override
	@Nullable
	public Column loadChunk(int cubeX, int cubeZ, Runnable runnable) {
		Column column = this.loadColumn(cubeX, cubeZ, LOAD_OR_GENERATE);
		if (runnable == null) {
			return column;
		}
		runnable.run();
		return null;
	}

	/**
	 * If this Column is already loaded - returns it.
	 * Loads from disk if possible, otherwise generated new Column.
	 */
	@Override
	public Column provideChunk(int cubeX, int cubeZ) {
		return loadChunk(cubeX, cubeZ, null);
	}

	@Override
	public boolean saveChunks(boolean alwaysTrue) {

		for (Column column : this.loadedColumns.values()) {
			// save the column
			if (column.needsSaving(alwaysTrue)) {
				this.cubeIO.saveColumn(column);
			}

			// save the cubes
			for (Cube cube : column.getAllCubes()) {
				if (cube.needsSaving()) {
					this.cubeIO.saveCube(cube);
				}
			}
		}

		return true;
	}

	@Override
	public boolean unloadQueuedChunks() {
		// NOTE: the return value is completely ignored

		if (this.worldServer.getDisableLevelSaving()) {
			return false;
		}

		final int maxUnload = 400;

		Iterator<CubeCoords> iter = this.cubesToUnload.iterator();
		int processed = 0;

		while (iter.hasNext() && processed < maxUnload) {
			CubeCoords coords = iter.next();
			iter.remove();
			++processed;

			if (this.dependencyManager.isRequired(coords)) {
				continue;
			}

			long columnAddress = AddressTools.getAddress(coords.getCubeX(), coords.getCubeZ());

			Column column = this.loadedColumns.get(columnAddress);
			if (column == null) {
				continue;
			}
			Cube cube = column.removeCube(coords.getCubeY());
			if (cube != null) {
				cube.onUnload();
				this.worldServer.getCubeGenerator().getDependentCubeManager().unregister(cube);
				this.cubeIO.saveCube(cube);
			}
			if (!column.hasCubes()) {
				column.onChunkUnload();
				this.loadedColumns.remove(columnAddress);
				this.cubeIO.saveColumn(column);
			}
		}

		return false;
	}

	@Override
	public String makeString() {
		return "ServerCubeCache: " + this.loadedColumns.size() + " columns, Unload: " + this.cubesToUnload.size() +
				" cubes";
	}

	@Override
	public List<Biome.SpawnListEntry> getPossibleCreatures(@Nonnull final EnumCreatureType type, @Nonnull final BlockPos pos) {
		return super.getPossibleCreatures(type, pos);
	}

	@Nullable
	public BlockPos getStrongholdGen(@Nonnull World worldIn, @Nonnull String structureName, @Nonnull BlockPos position) {
		return null;
	}

	@Override
	public int getLoadedChunkCount() {
		return this.loadedColumns.size();
	}

	@Override
	public boolean chunkExists(int cubeX, int cubeZ) {
		return this.loadedColumns.containsKey(AddressTools.getAddress(cubeX, cubeZ));
	}

	//==============================
	//=====CubicChunks methods======
	//==============================

	@Override
	public boolean cubeExists(int cubeX, int cubeY, int cubeZ) {
		long columnAddress = AddressTools.getAddress(cubeX, cubeZ);
		Column column = this.loadedColumns.get(columnAddress);
		if (column == null) {
			return false;
		}
		return column.getCube(cubeY) != null;
	}

	public boolean cubeExists(CubeCoords coords) {
		return this.cubeExists(coords.getCubeX(), coords.getCubeY(), coords.getCubeZ());
	}

	@Override
	public Column getColumn(int columnX, int columnZ) {
		return this.loadedColumns.get(AddressTools.getAddress(columnX, columnZ));
	}

	@Override
	public Cube getCube(int cubeX, int cubeY, int cubeZ) {
		long columnAddress = AddressTools.getAddress(cubeX, cubeZ);
		Column column = this.loadedColumns.get(columnAddress);
		if (column == null) {
			return null;
		}
		return column.getCube(cubeY);
	}

	public Cube getCube(CubeCoords coords) {
		return this.getCube(coords.getCubeX(), coords.getCubeY(), coords.getCubeZ());
	}

	public void loadCube(int cubeX, int cubeY, int cubeZ, LoadType loadType, GeneratorStage targetStage) {

		if (loadType == FORCE_LOAD) {
			throw new UnsupportedOperationException("Cannot force load a cube");
		}

		// Get the column
		long columnAddress = AddressTools.getAddress(cubeX, cubeZ);

		// Is it loaded?
		Column column = this.loadedColumns.get(columnAddress);

		// Try loading the column.
		if (column == null) {
			column = this.loadColumn(cubeX, cubeZ, loadType);
		}

		// If we couldn't load or generate the column - give up.
		if (column == null) {
			if (loadType == LOAD_OR_GENERATE) {
				CubicChunks.LOGGER.error(
						"Loading cube at " + cubeX + ", " + cubeY + ", " + cubeZ + " failed, couldn't load column");
			}
			return;
		}

		// Get the cube.
		long cubeAddress = AddressTools.getAddress(cubeX, cubeY, cubeZ);

		// Is the cube loaded?
		Cube cube = column.getCube(cubeY);
		if (cube != null) {

			// Resume/continue generation if necessary.
			if (cube.getCurrentStage().precedes(targetStage)) {
				this.worldServer.getCubeGenerator().generateCube(cube, targetStage);
			}

			return;
		}

		// Try loading the cube.
		try {
			cube = this.cubeIO.loadCubeAndAddToColumn(column, cubeAddress);
		} catch (IOException ex) {
			log.error("Unable to load cube ({},{},{})", cubeX, cubeY, cubeZ, ex);
			return;
		}

		// If loading it didn't work...
		if (cube == null) {
			// ... and generating was requested, generate it.
			if (loadType == LoadType.LOAD_OR_GENERATE) {
				// Have the column generate a new cube object and configure it for generation.
				cube = this.worldServer.getCubeGenerator().generateCube(new CubeCoords(cubeX, cubeY, cubeZ), targetStage);
			}
			// ... or quit.
			else {
				return;
			}
		}

		// If the cube has yet to reach the target stage, resume generation.
		else if (cube.isBeforeStage(targetStage)) {
			this.worldServer.getCubeGenerator().generateCube(cube, targetStage);
		}

		// Init the column.
		if (!column.isLoaded()) {
			column.onChunkLoad();
		}
		column.setTerrainPopulated(true);

		// Init the cube.
		cube.onLoad();
		this.dependencyManager.onLoad(cube);
	}

	public void loadCube(int cubeX, int cubeY, int cubeZ, LoadType loadType) {
		this.loadCube(cubeX, cubeY, cubeZ, loadType, GeneratorStage.LIVE);
	}

	public void loadCube(CubeCoords coords, LoadType loadType, GeneratorStage targetStage) {
		this.loadCube(coords.getCubeX(), coords.getCubeY(), coords.getCubeZ(), loadType, targetStage);
	}

	public Column loadColumn(int cubeX, int cubeZ, LoadType loadType) {
		Column column = null;
		//if we are not forced to load from disk - try to get it first
		if (loadType != FORCE_LOAD) {
			column = getColumn(cubeX, cubeZ);
		}
		if (column != null) {
			return column;
		}
		try {
			column = this.cubeIO.loadColumn(cubeX, cubeZ);
		} catch (IOException ex) {
			log.error("Unable to load column ({},{})", cubeX, cubeZ, ex);
			return null;
		}

		if (column == null) {
			// there wasn't a column, generate a new one (if allowed to generate)
			if (loadType == LOAD_OR_GENERATE) {
				column = this.worldServer.getCubeGenerator().generateColumn(cubeX, cubeZ);
			}
		} else {
			// the column was loaded
			column.setLastSaveTime(this.worldServer.getTotalWorldTime());
		}
		if (column == null) {
			return null;
		}
		this.loadedColumns.put(AddressTools.getAddress(cubeX, cubeZ), column);
		column.onChunkLoad();
		return column;
	}

	public Cube forceLoadCube(Cube forcedBy, int cubeX, int cubeY, int cubeZ) {

		this.loadCube(cubeX, cubeY, cubeZ, LOAD_ONLY);
		Cube cube = getCube(cubeX, cubeY, cubeZ);
		if (cube != null) {
			addForcedByMapping(forcedBy, cube);
			return cube;
		}
		Column column = this.loadColumn(cubeX, cubeZ, LOAD_OR_GENERATE);
		cube = this.worldServer.getCubeGenerator().generateCube(new CubeCoords(cubeX, cubeY, cubeZ));
		addForcedByMapping(forcedBy, cube);

		return cube;
	}

	private void addForcedByMapping(Cube forcedBy, Cube cube) {
		Set<Cube> forcedCubes = this.forceAdded.get(forcedBy);

		if (forcedCubes == null) {
			forcedCubes = new HashSet<Cube>();
			this.forceAdded.put(forcedBy, forcedCubes);
		}
		Set<Cube> forcedReverse = this.forceAddedReverse.get(cube);
		if (forcedReverse == null) {
			forcedReverse = new HashSet<>();
			this.forceAddedReverse.put(cube, forcedReverse);
		}

		forcedCubes.add(cube);
		forcedReverse.add(forcedBy);
	}

	private boolean cubeIsNearSpawn(Cube cube) {

		if (!this.worldServer.getProvider().canRespawnHere()) {
			// no spawn points
			return false;
		}

		BlockPos spawnPoint = this.worldServer.getSpawnPoint();
		int spawnCubeX = Coords.blockToCube(spawnPoint.getX());
		int spawnCubeY = Coords.blockToCube(spawnPoint.getY());
		int spawnCubeZ = Coords.blockToCube(spawnPoint.getZ());
		int dx = Math.abs(spawnCubeX - cube.getX());
		int dy = Math.abs(spawnCubeY - cube.getY());
		int dz = Math.abs(spawnCubeZ - cube.getZ());
		return dx <= SPAWN_LOAD_RADIUS && dy <= SPAWN_LOAD_RADIUS && dz <= SPAWN_LOAD_RADIUS;
	}

	public String dumpLoadedCubes() {
		StringBuilder sb = new StringBuilder(10000).append("\n");
		for (Column column : this.loadedColumns.values()) {
			if (column == null) {
				sb.append("column = null\n");
				continue;
			}
			sb.append("Column[").append(column.getX()).append(", ").append(column.getZ()).append("] {");
			boolean isFirst = true;
			for (Cube cube : column.getAllCubes()) {
				if (!isFirst) {
					sb.append(", ");
				}
				isFirst = false;
				if (cube == null) {
					sb.append("cube = null");
					continue;
				}
				sb.append("Cube[").append(cube.getY()).append("]");
			}
			sb.append("\n");
		}
		return sb.toString();
	}

	public void flush() {
		this.cubeIO.flush();
	}

	public void unloadCube(Cube cube) {
		// don't unload cubes near the spawn
		if (cubeIsNearSpawn(cube)) {
			return;
		}

		// queue the cube for unloading
		this.cubesToUnload.add(cube.getCoords());
	}

	public void unloadColumn(Column column) {
		if(!column.hasCubes()) {
			//TODO: remove unloadColumn hack
			//the column has no cubes, to unload the column - try to unload already unloaded cube
			//unloadQueuedChunks will automatically unload column once it's empty
			//just in case additional cube gets loaded between call to this method
			//and actual unload - specify impossible Y coordinate
			this.cubesToUnload.add(new CubeCoords(column.getX(), Integer.MIN_VALUE, column.getZ()));
		}
	}

	public enum LoadType {
		LOAD_ONLY, LOAD_OR_GENERATE, FORCE_LOAD
	}
}
