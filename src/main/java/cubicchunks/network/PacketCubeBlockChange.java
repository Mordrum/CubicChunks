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
package cubicchunks.network;

import cubicchunks.util.AddressTools;
import cubicchunks.world.cube.Cube;
import io.netty.buffer.ByteBuf;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

public class PacketCubeBlockChange implements IMessage {

	public int[] heightValues;
	public long cubeAddress;
	public int[] localAddresses;
	public IBlockState[] blockStates;

	public PacketCubeBlockChange() {}

	public PacketCubeBlockChange(Cube cube, Collection<Integer> localAddresses) {
		this.cubeAddress = cube.getAddress();
		this.localAddresses = new int[localAddresses.size()];
		this.blockStates = new IBlockState[localAddresses.size()];
		int i = 0;
		Set<Integer> xzAddresses = new HashSet<>();
		for (int localAddress : localAddresses) {
			this.localAddresses[i] = localAddress;
			int x = AddressTools.getLocalX(localAddress);
			int y = AddressTools.getLocalY(localAddress);
			int z = AddressTools.getLocalZ(localAddress);
			this.blockStates[i] = cube.getBlockState(x, y, z);
			xzAddresses.add(x | z << 4);
			i++;
		}
		this.heightValues = new int[xzAddresses.size()];
		i = 0;
		for (Integer v : xzAddresses) {
			Integer height = cube.getColumn().getOpacityIndex().getTopBlockY(v & 0xF, v >> 4);
			v |= (height == null ? Integer.MIN_VALUE : height) << 8;
			heightValues[i] = v;
			i++;
		}
	}

	@Override
	public void fromBytes(ByteBuf in) {
		this.cubeAddress = in.readLong();
		short numBlocks = in.readShort();
		localAddresses = new int[numBlocks];
		blockStates = new IBlockState[numBlocks];

		for (int i = 0; i < numBlocks; i++) {
			localAddresses[i] = in.readInt();
			blockStates[i] = (IBlockState) Block.BLOCK_STATE_IDS.getByValue(ByteBufUtils.readVarInt(in, 4));
		}
		int numHmapChanges = in.readUnsignedByte();
		heightValues = new int[numHmapChanges];
		for (int i = 0; i < numHmapChanges; i++) {
			heightValues[i] = in.readInt();
		}
	}

	@Override
	public void toBytes(ByteBuf out) {
		out.writeLong(cubeAddress);
		out.writeShort(localAddresses.length);
		for (int i = 0; i < localAddresses.length; i++) {
			out.writeInt(localAddresses[i]);
			ByteBufUtils.writeVarInt(out, Block.BLOCK_STATE_IDS.get(blockStates[i]), 4);
		}
		out.writeByte(heightValues.length);
		for (int v : heightValues) {
			out.writeInt(v);
		}
	}

	public static class Handler extends AbstractClientMessageHandler<PacketCubeBlockChange> {

		@Override
		public IMessage handleClientMessage(EntityPlayer player, PacketCubeBlockChange message, MessageContext ctx) {
			ClientHandler.getInstance().handle(message);
			return null;
		}
	}
}
