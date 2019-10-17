package pool;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.StampedLock;


public class PoolV2<R> implements Pool<R> {

    private static final Object TERMINATED = new Object();

    /**
     * Entries state.
     */
    private final ConcurrentHashMap<R, PooledEntry> refs = new ConcurrentHashMap<>();

    /**
     * acquire -> release handoff
     */
    private final TransferQueue<Object> handOff = new LinkedTransferQueue<>();

    /**
     * open-close state.
     */
    private final StampedLock openCloseLock;


    private final AtomicInteger acquireCount = new AtomicInteger(0);


    private final AtomicBoolean terminated = new AtomicBoolean(false);

    @SuppressWarnings("WeakerAccess")
    public PoolV2() {
        openCloseLock = new StampedLock();
    }

    enum State {
        IN_USE,
        NOT_IN_USE,
        TOMBSTONE
    }

    @SuppressWarnings("WeakerAccess")
    static class PooledEntry {
        private final AtomicReference<State> state = new AtomicReference<>(State.NOT_IN_USE);
        private final CountDownLatch latch = new CountDownLatch(1);
    }


    @Override
    public R acquire() throws InterruptedException {
        return doTake(0, TimeUnit.MILLISECONDS);
    }

    @Override
    public R acquire(long timeout, TimeUnit timeUnit) throws InterruptedException {
        return doTake(timeout, timeUnit);
    }

    private R doTake(long timeout, TimeUnit timeUnit) throws InterruptedException {

        // expect that #acquire operation will be visible during #close operation,
        //  as it increment acquireCount first and check termination flag after.
        // Vica-versa #close operation CAS  termination first and that "count-down" acquire consumers
        acquireCount.incrementAndGet();

        try {

            if (isTerminated()) {
                return null;
            }

            return doAcquire(timeout, timeUnit);
        } finally {
            acquireCount.decrementAndGet();
        }
    }

    private R doAcquire(long timeout, TimeUnit timeUnit) throws InterruptedException {
        long startTime = System.nanoTime();
        boolean doPollForever = timeout <= 0;
        timeout = timeUnit.toNanos(timeout);

        do {
            final Object elem = doPollForever
                    ? handOff.take()
                    : handOff.poll(timeout, TimeUnit.NANOSECONDS);

            if (elem == null || elem == TERMINATED) {
                return null;
            }

            @SuppressWarnings("unchecked")
            R item = (R) elem;

            PooledEntry mappedEntry = refs.compute(item, (k, entry) -> {
                // This lambda is a critical section for every entry.
                // So decision about offer to acquire the resource or not (because of pool closed state)
                // have to be taken into account here.
                //
                // expects that event if pool was closed (by terminated flag) some resource cane  still be
                // acquired
                if (entry == null || isTerminated()) {
                    return null; // mapping have been just removed, try again
                }

                entry.state.set(State.IN_USE);
                return entry;
            });

            if (mappedEntry != null) {
                return item;
            }

            if (!doPollForever) {
                timeout = timeout - elapsedNanos(startTime);
            }

        } while (!isTerminated() && (doPollForever || timeout > 0));

        return null;
    }



    private long elapsedNanos(long startTime) {
        return System.nanoTime() - startTime;
    }


    @Override
    public void release(R resource) {
        final PooledEntry updated = refs.compute(resource, (k, entry) -> {
            if (entry == null) {
                return null;
            }

            if (entry.state.get() == State.TOMBSTONE) {
                entry.latch.countDown();
                return null;
            }

            entry.state.set(State.NOT_IN_USE);
            return entry;
        });

        if (updated != null) {
            handOff.offer(resource);
        }
    }

    @Override
    public boolean remove(R resource) throws InterruptedException {
        return doRemove(resource, true);
    }


    @Override
    public boolean removeNow(R resource) throws InterruptedException {
        return doRemove(resource, false);
    }


    private boolean doRemove(R resource, boolean await) throws InterruptedException {
        final PooledEntry mapping = refs.compute(resource, (k, entry) -> {
            if (entry == null) {
                return null;
            }

            if (entry.state.get() == State.IN_USE) {
                entry.state.set(State.TOMBSTONE);
                return entry;
            }
            // remove mapping
            return null;
        });

        //
        //
        if (await && mapping != null && !isTerminated()) {
            // await for the latch count down
            // mapping will be removed by release method
            mapping.latch.await();
            return true;
        }

        //TODO how to track updated status?
        return false;
    }


    @Override
    public boolean add(R resource) {
        final PooledEntry oldMapping = refs.putIfAbsent(resource, new PooledEntry());
        if (oldMapping == null) {
            handOff.add(resource);
            return true;
        } else {
            return false;
        }
    }

    @Override
    public void closeNow() {

    }

    @Override
    public void open() {

    }

    @Override
    public boolean isOpen() {
        return false;
    }

    @Override
    public void close() throws InterruptedException {

        if (!terminated.get() && terminated.compareAndSet(false, true)) {

            while (acquireCount.get() > 0) {
                handOff.tryTransfer(TERMINATED);
            }



        }

    }

    private boolean isTerminated() {
        return terminated.get();
    }


}

