package pool;

import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.*;

// test simple logic, not concurrency
public class PoolV2Test {


    private static final int TIMEOUT = 10_000;

    private Pool<String> create() {
        final PoolV2<String> pool = new PoolV2<>();
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


    @Test(timeout = TIMEOUT)
    public void release() throws InterruptedException {
        final Pool<String> pool = create();

        pool.add("A");

        String acquireStr = pool.acquire();




    }

}
