package pool;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.StampedLock;


public class PoolImpl<R> implements Pool<R> {



    private final PoolEntry<R> TERMINATION_ENTRY
            = new PoolEntry<>(null);

    /**
     * Entries state. Resources that are borrowed from the pool but never "returned" will result
     * in a memory leak as they can't be removed.
     */
    private final Map<R, PoolEntry<R>> refs = new ConcurrentHashMap<>();

    /**
     * Hand off queue in case of unsatisfied consumers.
     */
    private final SynchronousQueue<PoolEntry<R>> handOff = new SynchronousQueue<>();

    private final AtomicInteger waiters = new AtomicInteger();

    private final AtomicBoolean stopFlag;


    @SuppressWarnings("WeakerAccess")
    public PoolImpl() {
        this(true);
    }


    @SuppressWarnings("WeakerAccess")
    PoolImpl(boolean stopFlag) {
        this.stopFlag = new AtomicBoolean(stopFlag);
    }

    static class PoolEntry<T> {

        enum State {
            IN_USE,
            NOT_IN_USE,
            DROP
        }

        private final CountDownLatch removeLatch = new CountDownLatch(1);
        private final StampedLock lock = new StampedLock();
        private volatile PoolEntry.State state;

        /**
         * Value.
         */
        private final T value;

        /**
         * Ctor.
         */
        PoolEntry(T value) {
            this(value, PoolEntry.State.NOT_IN_USE);
        }

        /**
         * Ctor.
         */
        PoolEntry(T value, PoolEntry.State state) {
            this.value = value;
            this.state = state;
        }


        PoolEntry.State getState() {
            return state;
        }


        boolean take() {
            long stm = this.lock.readLock();
            try {

                while (state == PoolEntry.State.NOT_IN_USE) {
                    long ws = lock.tryConvertToWriteLock(stm);
                    if (ws != 0L) {
                        stm = ws;
                        state = PoolEntry.State.IN_USE;
                        return true;
                    } else {
                        lock.unlockRead(stm);
                        stm = lock.writeLock();
                    }
                }

                return false;
            } finally {
                lock.unlock(stm);
            }
        }

        boolean release() {
            long stm = lock.writeLock();
            try {
                switch (state) {
                    case IN_USE:
                        state = PoolEntry.State.NOT_IN_USE;
                        return true;
                    case NOT_IN_USE:
                        return true;
                    case DROP:
                        removeLatch.countDown();
                        return false;
                    default:
                        throw new IllegalStateException();
                }
            } finally {
                lock.unlock(stm);
            }
        }

        void dropAwait() throws InterruptedException {
            PoolEntry.State oldState;

            long stm = lock.writeLock();
            try {
                oldState = state;
                state = PoolEntry.State.DROP;
            } finally {
                lock.unlock(stm);
            }

            if (oldState == PoolEntry.State.IN_USE) {
                removeLatch.await();
            }
        }

        void dropNow() {
            long stm = lock.writeLock();
            try {
                state = PoolEntry.State.DROP;
            } finally {
                lock.unlock(stm);
            }

            removeLatch.countDown();
        }



        T value() {
            return value;
        }
    }

    @Override
    public void open() {
        stopFlag.set(false);
    }

    @Override
    public boolean isOpen() {
        return !stopFlag.get();
    }

    @Override
    public void close() throws InterruptedException {

        if (!stopFlag.get() && stopFlag.compareAndSet(false, true)) {

            clearHandOff();

            for (Map.Entry<R, PoolEntry<R>> entry : refs.entrySet()) {
                final PoolEntry<R> poolEntry = entry.getValue();
                poolEntry.dropAwait();
            }

            refs.clear();
        }
    }




    @Override
    public void closeNow() {
        if (!stopFlag.get() && stopFlag.compareAndSet(false, true)) {
            clearHandOff();

            final Iterator<Map.Entry<R, PoolEntry<R>>> iterator = refs.entrySet().iterator();
            while (iterator.hasNext()) {
                final Map.Entry<R, PoolEntry<R>> entry = iterator.next();
                iterator.remove();

                entry.getValue().dropNow();
            }


        }

    }

    private void clearHandOff() {
        while (waiters.get() > 0) {
            handOff.offer(TERMINATION_ENTRY);
        }
    }

    @Override
    public R acquire() throws InterruptedException {
        return doAcquire(-1, TimeUnit.NANOSECONDS);
    }

    @Override
    public R acquire(long timeout, TimeUnit timeUnit) throws InterruptedException {
        if (timeout <= 0) throw new IllegalArgumentException("timeout have to be positive, " + timeout);
        return doAcquire(timeout, timeUnit);

    }

    private R doAcquire(long timeout, TimeUnit timeUnit) throws InterruptedException {
        assertClosed();

        for (PoolEntry<R> entryState : refs.values()) {
            if (!stopFlag.get() && entryState.take()) {
                return entryState.value();
            }
        }

        waiters.incrementAndGet();
        try {
            boolean useTimeoutPoll = timeout > 0;
            timeout = timeUnit.toNanos(timeout);

            do {
                long start = System.nanoTime();

                PoolEntry<R> entry = useTimeoutPoll
                        ? handOff.poll(timeout, TimeUnit.NANOSECONDS)
                        : handOff.take();

                if (entry == null || entry == TERMINATION_ENTRY) {
                    return null;
                }

                if (!stopFlag.get() && entry.take()) {
                    return entry.value();
                }

                if (useTimeoutPoll) {
                    timeout -= elapsedNanos(start);
                }

            } while ( (timeout > 0 || !useTimeoutPoll) && !stopFlag.get() );

            return null;
        } finally {
            waiters.decrementAndGet();
        }
    }

    @Override
    public void release(R resource) {
        final PoolEntry<R> poolEntry = refs.get(resource);
        if (poolEntry == null) {
            return;
        }

        if (poolEntry.release()) {

            while (waiters.get() > 0 && !stopFlag.get()) {

                if (poolEntry.getState() != PoolEntry.State.NOT_IN_USE
                        || handOff.offer(poolEntry)) {
                    return;
                }

                Thread.yield();
            }
        }
    }

    @Override
    public boolean add(R resource) {
        assertClosed();

        final PoolEntry<R> entry = new PoolEntry<>(resource);
        final PoolEntry<R> added = refs.putIfAbsent(resource, entry);
        return added == null;
    }

    @Override
    public boolean remove(R resource) throws InterruptedException {
        final PoolEntry<R> entry = refs.get(resource);
        if (entry == null) {
            return false;
        }

        entry.dropAwait();
        return refs.remove(resource) != null;
    }

    @Override
    public boolean removeNow(R resource) {
        final PoolEntry<R> entry = refs.remove(resource);
        if (entry == null) {
            return false;
        }

        entry.dropNow();
        return refs.remove(resource) != null;
    }


    private void assertClosed() {
        if (stopFlag.get()) throw new IllegalStateException("pool closed");
    }

    private long elapsedNanos(long startTime) {
        return System.nanoTime() - startTime;
    }
}

