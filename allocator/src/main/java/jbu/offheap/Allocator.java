package jbu.offheap;

import jbu.exception.InvalidParameterException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.*;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Frontend of off-heap store
 */
public class Allocator implements AllocatorMBean {


    private static final Logger LOGGER = LoggerFactory.getLogger(Allocator.class);

    private static final Boolean LOGGER_IS_DEBUG_ENABLED = LOGGER.isDebugEnabled();

    private static final int DEFAULT_MIN_CHUNK_SIZE = 256;
    private static final double MIN_FILL_FACTOR = 0.75d;

    //Thread safe until cannot be modified at runtime
    private final Map<Integer, Bins> binsByAddr = new HashMap<>();
    //Thread safe until cannot be modified at runtime
    private final NavigableMap<Integer, Bins> binsBySize = new TreeMap<>();

    private final AtomicInteger allocatedMemory = new AtomicInteger(0);
    private final AtomicInteger usedMemory = new AtomicInteger(0);
    private final AtomicLong nbAllocation = new AtomicLong(0);
    private final AtomicLong nbFree = new AtomicLong(0);

    public Allocator(long maxMemory) {
        this(maxMemory, DEFAULT_MIN_CHUNK_SIZE);
    }

    /**
     * Allocate {@code maxMemory} with the smaller chunk size {@code firstChunkSize}
     * {@code firstChunkSize} must a power of two and at least 8
     *
     * @param maxMemory
     * @param firstChunkSize
     */
    public Allocator(long maxMemory, int firstChunkSize) {
        if (!checkFirstChunkSizeValid(firstChunkSize)) {
            throw new InvalidParameterException("FirstChunkSize must be a power of two and at least 8");
        }
        constructWithLinearScale(maxMemory, 1, firstChunkSize);
    }

    private void constructWithLinearScale(long initialMemory, int maxBins, int firstChunkSize) {
        LOGGER.info("allocate_native_memory, total_user_size:{} Mb, {} buffers, initial_chunk_size: {}", initialMemory / 1024 / 1024, maxBins, firstChunkSize);

        // Construct scale
        long binsSize = initialMemory / maxBins;
        int currentChunkSize = firstChunkSize;

        for (int i = 0; i < maxBins; i++) {
            //int numberOfChunk = (int) Math.ceil((double) binsSize / (double) currentChunkSize);
            int noc = (int) (binsSize / currentChunkSize);
            if (binsSize % currentChunkSize > 0) {
                noc++;
            }
            Bins bbb = new UnsafeBins(noc, currentChunkSize, i);
            binsBySize.put(currentChunkSize, bbb);
            binsByAddr.put(i, bbb);
            currentChunkSize = currentChunkSize * 2;
        }
    }


    /**
     * Alloc with the littest chunk size
     * @return
     */
    public long alloc() {
        return alloc(binsBySize.firstKey());
    }

    // Allocate with less waste but much more chunks
    public long alloc(int memorySize) {
        int usedMemoryByAllocate = 0;
        int memoryToAllocate = memorySize;
        long previousChunkAddr = -1;
        long firstChunk = -1;
        int nbAllocateChunk = 0;
        // Search for the bin with just size lesser
        while (memoryToAllocate > 0) {
            Map.Entry<Integer, Bins> usedBin;
            // Search upper and lesser
            Map.Entry<Integer, Bins> lesserBin = binsBySize.floorEntry(memoryToAllocate);
            Map.Entry<Integer, Bins> upperBin = binsBySize.ceilingEntry(memoryToAllocate);
            // Fill factor determine when stop cutting in two memory to allocate
            if (lesserBin == null || (((double) memorySize / (double) lesserBin.getKey().intValue()) > MIN_FILL_FACTOR && upperBin != null)) {
                // take upper
                usedBin = upperBin;
            } else {
                usedBin = lesserBin;
            }
            // Allocate one chunk
            long chunkAddr = usedBin.getValue().allocateOneChunk();

            // If no chunk available
            // Try to take a inferior chunk if not available superior chunk and two inf chunk two sup chunk etc...
            if (chunkAddr < 0) {
                Map.Entry<Integer, Bins> currentBin = usedBin;
                // take inf
                currentBin = binsBySize.lowerEntry(currentBin.getKey());
                if (currentBin != null) {
                    chunkAddr = currentBin.getValue().allocateOneChunk();
                }
            }

            // Here try superior chunk
            if (chunkAddr < 0) {
                Map.Entry<Integer, Bins> currentBin = usedBin;
                // take inf
                currentBin = binsBySize.higherEntry(currentBin.getKey());
                if (currentBin != null) {
                    chunkAddr = currentBin.getValue().allocateOneChunk();
                }
            }


            // update next chunk of previous element
            if (previousChunkAddr != -1) {
                setNextChunk(previousChunkAddr, chunkAddr);
            } else {
                // First chunk
                firstChunk = chunkAddr;
            }
            previousChunkAddr = chunkAddr;
            // update memory to allocate
            memoryToAllocate -= usedBin.getValue().userDataChunkSize;
            // update used memory
            usedMemoryByAllocate += usedBin.getValue().realChunkSize;

            nbAllocateChunk++;
        }
        // Set no next chunk to last chunk
        setNextChunk(previousChunkAddr, -1);
        this.usedMemory.getAndAdd(usedMemoryByAllocate);
        this.nbAllocation.getAndAdd(nbAllocateChunk);
        if (LOGGER_IS_DEBUG_ENABLED) {
            LOGGER.debug("take_memory, memory_size: {} Bytes, first_chunk_id: {}, used_memory: {}, nb_chunk_allocated {}",
                    memorySize, firstChunk, this.usedMemory.get(), this.nbAllocation.get());
        }
        return firstChunk;
    }

    public void free(long firstChunkAdr) {
        // get all chunk ref
        // FIXME find bin
        // Get next adr
        long currentAdr = firstChunkAdr;
        long nextAdr;
        do {
            Bins bin = getBinFromAddr(currentAdr);
            nextAdr = bin.getNextChunkId(AddrAlign.getChunkId(currentAdr));
            // and free current
            bin.freeChunk(currentAdr);
            currentAdr = nextAdr;
            // update counter
            usedMemory.getAndAdd(-bin.realChunkSize);
            nbFree.incrementAndGet();
        } while (nextAdr != -1);
    }

    /**
     * @param currentBaseAdr
     * @return
     */
    public boolean extend(long currentBaseAdr) {
        if (LOGGER_IS_DEBUG_ENABLED) {
            LOGGER.debug("extends, currentChunk: {}, with {} byte", currentBaseAdr);
        }
        long chunkToAdd = alloc(getBinFromAddr(currentBaseAdr).realChunkSize);
        if (chunkToAdd < 0) {
            return false;
        } else {
            setNextChunk(currentBaseAdr, chunkToAdd);
            return true;
        }
    }

    public StoreContext getStoreContext(long firstChunkAdr) {
        return new StoreContext(this, firstChunkAdr);
    }

    public LoadContext getLoadContext(long firstChunkAdr) {
        return new LoadContext(this, firstChunkAdr);
    }

    private void setNextChunk(long currentChunkAdr, long nextChunkAddr) {
        // FIXME find bin
        getBinFromAddr(currentChunkAdr).setNextChunkId(AddrAlign.getChunkId(currentChunkAdr), nextChunkAddr);
    }


    Bins getBinFromAddr(long chunkAddr) {
        return binsByAddr.get(AddrAlign.getBinId(chunkAddr));
    }

    // JMX

    public void registerInMBeanServer(MBeanServer mbs) {
        try {
            mbs.registerMBean(this, new ObjectName("Allocator:name=allocator"));
            for (Bins bbb : binsBySize.values()) {
                mbs.registerMBean(bbb, new ObjectName("Allocator.UnsafeBins:maxChunk=" + bbb.userDataChunkSize));
            }
        } catch (InstanceAlreadyExistsException | MBeanRegistrationException | NotCompliantMBeanException | MalformedObjectNameException e) {
            LOGGER.warn("Cannot register JMX Beans", e);
        }

    }

    public void unRegisterInMBeanServer(MBeanServer mbs) {
        try {
            mbs.unregisterMBean(new ObjectName("Allocator:name=allocator"));
        } catch (InstanceNotFoundException | MBeanRegistrationException | MalformedObjectNameException e) {
            LOGGER.warn("Cannot unregister JMX Beans", e);
        }
    }

    @Override
    public int getAllocatedMemory() {
        return allocatedMemory.intValue();
    }

    @Override
    public int getUsedMemory() {
        return usedMemory.intValue();
    }

    @Override
    public long getNbAllocation() {
        return nbAllocation.longValue();
    }

    @Override
    public long getNbFree() {
        return nbFree.longValue();
    }

    private boolean checkFirstChunkSizeValid(int firstChunkSize) {
        return (firstChunkSize > 8 && Integer.bitCount(firstChunkSize) == 1);
    }

}
