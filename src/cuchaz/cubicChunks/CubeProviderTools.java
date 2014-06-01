/*******************************************************************************
 * Copyright (c) 2014 Jeff Martin.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 * 
 * Contributors:
 *     Jeff Martin - initial API and implementation
 ******************************************************************************/
package cuchaz.cubicChunks;

import cuchaz.cubicChunks.generator.GeneratorStage;
import static cuchaz.cubicChunks.generator.GeneratorStage.Population;
import cuchaz.cubicChunks.util.Coords;

public class CubeProviderTools
{
	public static boolean blocksExist( CubeProvider provider, int minBlockX, int minBlockY, int minBlockZ, int maxBlockX, int maxBlockY, int maxBlockZ )
	{
		// convert block bounds to chunk bounds
		int minCubeX = Coords.blockToCube( minBlockX );
		int minCubeY = Coords.blockToCube( minBlockY );
		int minCubeZ = Coords.blockToCube( minBlockZ );
		int maxCubeX = Coords.blockToCube( maxBlockX );
		int maxCubeY = Coords.blockToCube( maxBlockY );
		int maxCubeZ = Coords.blockToCube( maxBlockZ );
		
		return cubesExist( provider, minCubeX, minCubeY, minCubeZ, maxCubeX, maxCubeY, maxCubeZ );
	}
	
	public static boolean cubeAndNeighborsExist( CubeProvider provider, int cubeX, int cubeY, int cubeZ )
	{
		return cubesExist( provider,
			cubeX - 1, cubeY - 1, cubeZ - 1,
			cubeX + 1, cubeY + 1, cubeZ + 1
		);
	}

	public static boolean cubesForPopulationExistAndCheckStage( CubeProvider provider, int cubeX, int cubeY, int cubeZ )
	{
		boolean r = cubesExist( provider,
			cubeX, cubeY, cubeZ,
			cubeX + 1, cubeY + 1, cubeZ + 1 );
		if(!r){
			return false;
		}
		r &= checkCubeGeneratorStage(provider, Population, 
			cubeX, cubeY, cubeZ,
			cubeX + 1, cubeY + 1, cubeZ + 1);
		return r;
	}
	
	public static boolean cubeAboveExistsAndStageEqualOrHigher( CubeProvider provider, GeneratorStage stage, int cubeX, int cubeY, int cubeZ ){
		boolean r = cubesExist( provider,
			cubeX, cubeY, cubeZ,
			cubeX , cubeY + 1, cubeZ );
		if(!r){
			return false;
		}
		r &= checkCubeGeneratorStage(provider, stage, 
			cubeX, cubeY, cubeZ,
			cubeX, cubeY + 1, cubeZ);
		return r;
	}

	public static boolean cubesExist( CubeProvider provider, int minCubeX, int minCubeY, int minCubeZ, int maxCubeX, int maxCubeY, int maxCubeZ )
	{
		for( int cubeX=minCubeX; cubeX<=maxCubeX; cubeX++ )
		{
			for( int cubeY=minCubeY; cubeY<=maxCubeY; cubeY++ )
			{
				for( int cubeZ=minCubeZ; cubeZ<=maxCubeZ; cubeZ++ )
				{
					if( !provider.cubeExists( cubeX, cubeY, cubeZ ) )
					{
						return false;
					}
				}
			}
		}
		return true;
	}
	
	public static boolean checkCubeGeneratorStage(CubeProvider provider, GeneratorStage stage, int minCubeX, int minCubeY, int minCubeZ, int maxCubeX, int maxCubeY, int maxCubeZ ){
		for( int cubeX=minCubeX; cubeX<=maxCubeX; cubeX++ )
		{
			for( int cubeY=minCubeY; cubeY<=maxCubeY; cubeY++ )
			{
				for( int cubeZ=minCubeZ; cubeZ<=maxCubeZ; cubeZ++ )
				{
					if( provider.provideCube(cubeX, cubeY, cubeZ ).getGeneratorStage().ordinal() < stage.ordinal() )
					{
						return false;
					}
				}
			}
		}
		return true;
	}
}
