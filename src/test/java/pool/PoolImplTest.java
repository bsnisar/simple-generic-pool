package pool;

import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.*;

public class PoolImplTest {


    private static final int TIMEOUT = 10_000;

    private PoolImpl<String> create() {
        final PoolImpl<String> pool = new PoolImpl<>();
        pool.open();
        return pool;
    }


    @Test(timeout = TIMEOUT)
    public void acquireWithTO() throws InterruptedException {
        final PoolImpl<String> pool = create();
        String s = pool.acquire(100, TimeUnit.MILLISECONDS);
        Assert.assertNull(s);
    }



    @Test(expected = TimeoutException.class)
    public void acquireForever() throws InterruptedException, TimeoutException, ExecutionException {
        final PoolImpl<String> pool = create();
        ExecutorService e = Executors.newFixedThreadPool(1);
        final Future<String> future = e.submit((Callable<String>) pool::acquire);

        future.get(2, TimeUnit.SECONDS);

        e.shutdown();
        e.awaitTermination(1, TimeUnit.MINUTES);
    }


    @Test(timeout = TIMEOUT)
    public void addAndAcquireWithoutTO() throws InterruptedException, ExecutionException {
        final PoolImpl<String> pool = create();

        ExecutorService e = Executors.newFixedThreadPool(1);

        final Future<String> futureAdd = e.submit(() -> {
            pool.add("1");
            return null;
        });

        futureAdd.get();

        final Future<String> futureGet = e.submit((Callable<String>) pool::acquire);
        Assert.assertEquals("1", futureGet.get());

        e.shutdown();
        e.awaitTermination(1, TimeUnit.MINUTES);
    }


    @Test(timeout = TIMEOUT)
    public void addAndAcquireWittTO() throws InterruptedException, ExecutionException {
        final PoolImpl<String> pool = create();

        ExecutorService e = Executors.newFixedThreadPool(1);

        final Future<String> futureAdd = e.submit(() -> {
            pool.add("1");
            return null;
        });

        futureAdd.get();

        final Future<String> futureGet = e.submit(
                (Callable<String>) () -> pool.acquire(300, TimeUnit.MILLISECONDS));

        Assert.assertEquals("1", futureGet.get());

        e.shutdown();
        e.awaitTermination(1, TimeUnit.MINUTES);
    }

    @Test(timeout = TIMEOUT)
    public void addAndAcquireWithTO() throws InterruptedException {
        final PoolImpl<String> pool = create();
        pool.add("OK");
        String s = pool.acquire(100, TimeUnit.MILLISECONDS);
        Assert.assertNotNull(s);
    }

    @Test(timeout = TIMEOUT)
    public void addAndRemoveAndAcquire() throws InterruptedException {
        final PoolImpl<String> pool = create();
        pool.add("OK");
        pool.remove("OK");
        String s = pool.acquire(100, TimeUnit.MILLISECONDS);
        Assert.assertNull(s);
    }


    @Test(timeout = TIMEOUT)
    public void removeNotExists() throws InterruptedException {
        final PoolImpl<String> pool = create();
        pool.add("OK");
        Assert.assertFalse(pool.remove("NOK"));
    }

    @SuppressWarnings({"Convert2MethodRef"})
    @Test(timeout = TIMEOUT)
    public void waitForRelease() throws InterruptedException, ExecutionException {
        final PoolImpl<String> pool = create();
        ExecutorService e = Executors.newFixedThreadPool(1);

        pool.add("OK");

        final Future<String> futureGet1 = e.submit(() -> pool.acquire());
        Assert.assertEquals("OK", futureGet1.get());

        final Future<String> futureGet2 = e.submit(() -> pool.acquire());
        pool.release("OK");
        Assert.assertEquals("OK", futureGet2.get());

        e.shutdown();
        e.awaitTermination(1, TimeUnit.MINUTES);
    }



    @Test(timeout = TIMEOUT, expected = IllegalStateException.class)
    public void addToClosedFailed() {
        final PoolImpl<String> pool = new PoolImpl<>();
        pool.add("FOO");
    }

    @Test(timeout = TIMEOUT)
    public void openAndIsOpened() {
        final PoolImpl<String> pool = new PoolImpl<>();
        Assert.assertFalse(pool.isOpen());

        pool.open();
        Assert.assertTrue(pool.isOpen());
    }

    @Test(timeout = TIMEOUT)
    public void addReleaseAndClose() throws InterruptedException {
        final PoolImpl<String> pool = create();
        final String item = "FOO";

        pool.add(item);
        Assert.assertEquals(item, pool.acquire());
        pool.release(item);

        pool.close();
    }


    @Test(timeout = TIMEOUT)
    public void closeNowWithRemove() throws InterruptedException, ExecutionException {
        final PoolImpl<String> pool = create();
        final String item = "FOO";

        pool.add(item);
        Assert.assertEquals(item, pool.acquire());

        ExecutorService e = Executors.newFixedThreadPool(2);

        final Future<Object> f1 = e.submit(() -> {
            pool.close();
            return null;
        });

        final Future<Object> f2 = e.submit(() -> {
            pool.remove(item);
            return null;
        });

        pool.release(item);

        f1.get();
        f2.get();

        Assert.assertFalse(pool.isOpen());

        e.shutdown();
        e.awaitTermination(1, TimeUnit.MINUTES);
    }
}
