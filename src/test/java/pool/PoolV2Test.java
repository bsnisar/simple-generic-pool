package pool;

import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.*;

// test simple logic, not concurrency
public class PoolV2Test {


    private static final int TIMEOUT = 10_000;

    private PoolImpl<String> create() {
        final PoolImpl<String> pool = new PoolImpl<>();
        pool.open();
        return pool;
    }


    @Test(timeout = TIMEOUT)
    public void acquireByTimeout() throws InterruptedException {
        final Pool<String> pool = create();
        String s = pool.acquire(100, TimeUnit.MILLISECONDS);
        Assert.assertNull(s);
    }


    @Test(timeout = TIMEOUT)
    public void addAndAcquireByTimeout() throws InterruptedException {
        final Pool<String> pool = create();
        pool.add("A");
        String s = pool.acquire(100, TimeUnit.MILLISECONDS);
        Assert.assertEquals("A", s);
    }

    @Test(timeout = TIMEOUT)
    public void add() throws InterruptedException {
        final Pool<String> pool = create();
        Assert.assertTrue(pool.add("A"));
        Assert.assertFalse(pool.add("A"));
        Assert.assertTrue(pool.add("B"));
    }


    @SuppressWarnings("Convert2MethodRef")
    @Test(timeout = TIMEOUT)
    public void closeDuringAcq() throws InterruptedException, ExecutionException {
        final PoolImpl<String> pool = create();

        ExecutorService es = Executors.newFixedThreadPool(1);

        Future<String> f = es.submit(() -> {
            return pool.acquire();
        });

        //noinspection StatementWithEmptyBody
        while (!pool.idleQueue.hasWaitingConsumer()) {}

        pool.closeNow();

        Assert.assertNull(f.get());

        es.shutdown();
        es.awaitTermination(10, TimeUnit.SECONDS);
    }

}
