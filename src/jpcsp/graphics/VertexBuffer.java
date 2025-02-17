/*
This file is part of jpcsp.

Jpcsp is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

Jpcsp is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with Jpcsp.  If not, see <http://www.gnu.org/licenses/>.
 */
package jpcsp.graphics;

import static jpcsp.Allegrex.compiler.RuntimeContext.getMemoryInt;
import static jpcsp.Allegrex.compiler.RuntimeContext.hasMemoryInt;
import static jpcsp.util.Utilities.alignDown;
import static jpcsp.util.Utilities.alignUp;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;

import org.apache.log4j.Logger;

import jpcsp.Memory;
import jpcsp.graphics.RE.IRenderingEngine;
import jpcsp.memory.IMemoryReader;
import jpcsp.memory.MemoryReader;
import jpcsp.util.Utilities;

/**
 * @author gid15
 *
 */
public class VertexBuffer {
	private static Logger log = VideoEngine.log;
	private int bufferId = -1;
	private int bufferAddress;
	private int bufferLength;
	private int stride;
	private int[] cachedMemory;
	// The data in the cachedBuffer will always be kept aligned on 32-bit boundaries
	private ByteBuffer cachedBuffer;
	private int cachedBufferOffset;
	private HashMap<Integer, Integer> addressAlreadyChecked = new HashMap<Integer, Integer>();
	private AddressRange[] dirtyRanges = new AddressRange[0];
	private int numberDirtyRanges = 0;
	private boolean reloadBufferDataPending = false;
	private static final int bufferUsage = IRenderingEngine.RE_DYNAMIC_DRAW;
	private static final int bufferTarget = IRenderingEngine.RE_ARRAY_BUFFER;
	private static final boolean workaroundBufferDataBug = true;

	private static class AddressRange {
		public int address;
		public int length;

		public AddressRange() {
		}

		public void setRange(int address, int length) {
			this.address = address;
			this.length = length;
		}

		@Override
		public String toString() {
			return String.format("AddressRange[0x%08X-0x%08X, length %d]", address, address + length, length);
		}
	}

	public VertexBuffer(int address, int stride) {
		bufferAddress = Memory.normalizeAddress(address);
		bufferLength = 0;
		this.stride = stride;
	}

	public void bind(IRenderingEngine re) {
		if (bufferId == -1) {
            bufferId = re.genBuffer();
		}
		re.bindBuffer(bufferTarget, bufferId);
	}

	/**
	 * Always keep the buffer aligned on 32-bit boundaries.
	 * 
	 * @param address
	 * @return the number of bytes to subtract from the address to be 32-bit aligned.
	 */
	private int getBufferAlignment(int address) {
		return address & 3;
	}

	private int offset4(int offset) {
		return (offset + 3) >> 2;
	}

	private int length4(int length) {
		return alignUp(length, 3);
	}

	private void loadFromMemory(int address, int length) {
		if (length > 0) {
			copyToCachedMemory(address, length);
			Buffer buffer = Memory.getInstance().getBuffer(address, length);
			position(alignDown(address, 3));
			Utilities.putBuffer(cachedBuffer, buffer, ByteOrder.LITTLE_ENDIAN, length4(length));
		}
	}

	private boolean extend(Buffer buffer, int address, int length) {
		final boolean overflowBottom = address < bufferAddress;
		final boolean overflowTop = address + length > bufferAddress + bufferLength;
		boolean extended = false;

		if (!overflowBottom && !overflowTop) {
			// Most common case: the buffer is fitting
		} else if (bufferLength == 0 || (overflowBottom && overflowTop)) {
			// Create a new buffer
			cachedBufferOffset = getBufferAlignment(address);
			cachedBuffer = ByteBuffer.allocateDirect(length4(length + cachedBufferOffset)).order(ByteOrder.LITTLE_ENDIAN);
			bufferAddress = address;
			bufferLength = length;
			cachedMemory = new int[offset4(bufferLength)];
			// The buffer has been resized: its content is lost, reload it
			reloadBufferDataPending = true;
			extended = true;
		} else if (overflowBottom) {
			// Extend the buffer to the bottom
			int newCachedBufferOffset = getBufferAlignment(address);
			int extendLength = bufferAddress - address + newCachedBufferOffset;
			ByteBuffer newBuffer = ByteBuffer.allocateDirect(length4(extendLength + cachedBuffer.capacity())).order(ByteOrder.LITTLE_ENDIAN);
			newBuffer.position(extendLength);
			cachedBuffer.clear();
			cachedBuffer.position(cachedBufferOffset);
			newBuffer.put(cachedBuffer);
			newBuffer.rewind();
			cachedBuffer = newBuffer;
			cachedBufferOffset = newCachedBufferOffset;
			bufferLength += extendLength;
			int[] newCachedMemory = new int[offset4(bufferLength)];
			System.arraycopy(cachedMemory, 0, newCachedMemory, extendLength >> 2, cachedMemory.length);
			cachedMemory = newCachedMemory;
			bufferAddress = address;
			loadFromMemory(bufferAddress + length, extendLength - length);
			// The buffer has been resized: its content is lost, reload it
			reloadBufferDataPending = true;
			extended = true;
		} else if (overflowTop) {
			// Extend the buffer to the top
			int extendLength = address + length - (bufferAddress + bufferLength);
			ByteBuffer newBuffer = ByteBuffer.allocateDirect(length4(extendLength + cachedBuffer.capacity())).order(ByteOrder.LITTLE_ENDIAN);
			cachedBuffer.clear();
			newBuffer.put(cachedBuffer);
			newBuffer.rewind();
			cachedBuffer = newBuffer;
			int oldBufferEnd = bufferAddress + bufferLength;
			bufferLength += extendLength;
			int[] newCachedMemory = new int[offset4(bufferLength)];
			System.arraycopy(cachedMemory, 0, newCachedMemory, 0, cachedMemory.length);
			cachedMemory = newCachedMemory;
			loadFromMemory(oldBufferEnd, address - oldBufferEnd);
			// The buffer has been resized: its content is lost, reload it
			reloadBufferDataPending = true;
			extended = true;
		}

		return extended;
	}

	public int getBufferOffset(int address) {
		address = Memory.normalizeAddress(address);
		return address - bufferAddress;
	}

	private int getBufferOffset4(int address) {
		return getBufferOffset(address) >> 2;
	}

	public int getNativeBufferOffset(int address) {
		return getBufferOffset(address) + cachedBufferOffset;
	}

	private void position(int address) {
		cachedBuffer.clear();
		cachedBuffer.position(getNativeBufferOffset(address));
	}

	private void copyToCachedMemory(int address, int length) {
		int offset = getBufferOffset4(address);
		int n = offset4(length);
		if (hasMemoryInt()) {
			System.arraycopy(getMemoryInt(), address >> 2, cachedMemory, offset, n);
		} else {
			IMemoryReader memoryReader = MemoryReader.getMemoryReader(address, length, 4);
			for (int i = 0; i < n; i++) {
				cachedMemory[offset + i] = memoryReader.readNext();
			}
		}
	}

	private void checkDirty(IRenderingEngine re) {
		if (reloadBufferDataPending) {
			bind(re);
			cachedBuffer.clear();
			re.setBufferData(bufferTarget, cachedBuffer.remaining(), cachedBuffer, bufferUsage);
			reloadBufferDataPending = false;
			numberDirtyRanges = 0;
		} else if (numberDirtyRanges > 0) {
			bind(re);
			for (int i = 0; i < numberDirtyRanges; i++) {
				position(dirtyRanges[i].address);
				re.setBufferSubData(bufferTarget, cachedBuffer.position(), dirtyRanges[i].length, cachedBuffer);
			}
			numberDirtyRanges = 0;
		}
	}

	private boolean cachedMemoryEquals(int address, int length) {
		IMemoryReader memoryReader = MemoryReader.getMemoryReader(address, length, 4);
		int offset = getBufferOffset4(address);
		int n = offset4(length);
		for (int i = 0; i < n; i++) {
			if (cachedMemory[offset + i] != memoryReader.readNext()) {
				if (log.isTraceEnabled()) {
					log.trace(String.format("VertexBuffer.cachedMemoryEquals(0x%08X, %d): are not equal", address, length));
				}
				return false;
			}
		}

		if (log.isTraceEnabled()) {
			log.trace(String.format("VertexBuffer.cachedMemoryEquals(0x%08X, %d): are equal", address, length));
		}
		return true;
	}

	private void addDirtyRange(int address, int length) {
		for (int i = 0; i < numberDirtyRanges; i++) {
			if (dirtyRanges[i].address == address) {
				if (length > dirtyRanges[i].length) {
					dirtyRanges[i].length = length;
				}
				return;
			}
		}

		if (numberDirtyRanges >= dirtyRanges.length) {
			// Extend dirtyRanges array
			AddressRange[] newDirtyRanges = new AddressRange[dirtyRanges.length + 10];
			System.arraycopy(dirtyRanges, 0, newDirtyRanges, 0, dirtyRanges.length);
			for (int i = dirtyRanges.length; i < newDirtyRanges.length; i++) {
				newDirtyRanges[i] = new AddressRange();
			}
			dirtyRanges = newDirtyRanges;
		}

		dirtyRanges[numberDirtyRanges].setRange(address, length);
		numberDirtyRanges++;
	}

	public synchronized void preLoad(Buffer buffer, int address, int length) {
		load(null, buffer, address, length);
	}

	public synchronized void load(IRenderingEngine re, Buffer buffer, int address, int length) {
		address = Memory.normalizeAddress(address);

		if (log.isTraceEnabled()) {
			log.trace(String.format("VertexBuffer.load address=0x%08X, length=%d in %s", address, length, toString()));
		}
		if (!addressAlreadyChecked(address, length)) {
			boolean extended = extend(buffer, address, length);
			// Check if the memory content has changed
			if (extended || !cachedMemoryEquals(address, length)) {
				position(alignDown(address, 3));
				if (log.isTraceEnabled()) {
					log.trace(String.format("copy buffer from 0x%08X(buffer offset=%d) to VertexBuffer at 0x%08X(buffer offset=%d), length=%d", alignDown(address, 3), buffer.position(), bufferAddress - cachedBufferOffset + cachedBuffer.position(), cachedBuffer.position(), length4(length)));
				}
				Utilities.putBuffer(cachedBuffer, buffer, ByteOrder.LITTLE_ENDIAN, length4(length));
				buffer.rewind();

				if (re != null) {
					if (log.isTraceEnabled()) {
						log.trace(String.format("VertexBuffer reload buffer address=0x%08X, length=%d, extended=%b", address, length, extended));
					}

					// No need to update the sub data if the complete buffer has been reloaded...
					boolean updateSubData = !reloadBufferDataPending;

					checkDirty(re);

					if (updateSubData) {
						position(address);
						bind(re);
						re.setBufferSubData(bufferTarget, cachedBuffer.position(), length, cachedBuffer);
					}
				} else {
					addDirtyRange(address, length);
				}

				copyToCachedMemory(address, length);
			} else if (re != null) {
				checkDirty(re);

				// Here the buffer data should not need to be reloaded, it is matching
				// the previous data. However, due to a driver bug (?), the buffer data
				// has sometimes been corrupted in the GPU. This problem seems to happen
				// only under some circumstances but I was not able to identify the exact
				// conditions. Just reloading a single byte of the buffer restores
				// the correct data in the buffer. This is why I assumed this is a driver bug.
				// This workaround could however completely break the performance of the vertex
				// cache!
				if (workaroundBufferDataBug) {
					bind(re);
					position(bufferAddress);
					re.setBufferSubData(bufferTarget, cachedBuffer.position(), 1, cachedBuffer);
				}
			}

			setAddressAlreadyChecked(address, length);
		} else if (re != null) {
			if (log.isTraceEnabled()) {
				log.trace(String.format("VertexBuffer address already checked address=0x%08X, length=%d", address, length));
			}
			checkDirty(re);
		}
	}

	public synchronized void delete(IRenderingEngine re) {
		if (bufferId != -1) {
			re.deleteBuffer(bufferId);
            bufferId = -1;
		}
		bufferLength = 0;
		bufferAddress = 0;
		cachedBufferOffset = 0;
		stride = 0;
		cachedBuffer = null;
    	cachedMemory = null;
    }

	public boolean isAddressInside(int address, int length, int gapSize) {
		address = Memory.normalizeAddress(address);
		int endAddress = address + length;
		int startBuffer = bufferAddress - gapSize;
		int endBuffer = bufferAddress + bufferLength + gapSize;

		// start address inside the buffer
		if (startBuffer <= address && address < endBuffer) {
			return true;
		}
		// end address inside the buffer
		if (startBuffer <= endAddress && endAddress < endBuffer) {
			return true;
		}
		// start & end address including the buffer
		if (address < startBuffer && endBuffer < endAddress) {
			return true;
		}

		return false;
	}

	public synchronized void resetAddressAlreadyChecked() {
		addressAlreadyChecked.clear();
	}

	private boolean addressAlreadyChecked(int address, int length) {
		Integer checkedLength = addressAlreadyChecked.get(address);
		if (checkedLength == null) {
			return false;
		}

		return checkedLength.intValue() >= length;
	}

	private void setAddressAlreadyChecked(int address, int length) {
		addressAlreadyChecked.put(address, length);
	}

	public int getStride() {
		return stride;
	}

	public int getLength() {
		return bufferLength;
	}

	public int getId() {
		return bufferId;
	}

	@Override
	public String toString() {
		return String.format("VertexBuffer[0x%08X-0x%08X, length %d, stride %d, id %d]", bufferAddress, bufferAddress + bufferLength, bufferLength, stride, bufferId);
	}
}
