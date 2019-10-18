package pool;

import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.*;

import java.util.concurrent.TimeUnit;

import static java.util.Objects.requireNonNull;

public class PoolV2_StressTest {

    private static final String ITEM_1 = "A1";

    @JCStressTest(Mode.Termination)
    @Outcome.Outcomes({
            @Outcome(id = "TERMINATED", expect = Expect.ACCEPTABLE, desc = "gracefully finished"),
            @Outcome(id = "STALE", expect = Expect.FORBIDDEN, desc = "test hung up.")
    })
    @State
    public static class Acquire_WaitForAdd {

        private final PoolV2<String> pool;

        public Acquire_WaitForAdd() {
            pool = new PoolV2<>();
            pool.open();
        }

        @Actor
        public void actor1()  {
            try {
                requireNonNull(pool.acquire());
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        @Signal
        public void sig() {
            pool.add(ITEM_1);
        }
    }


    @JCStressTest(Mode.Termination)
    @Outcome.Outcomes({
            @Outcome(id = "TERMINATED", expect = Expect.ACCEPTABLE, desc = "finished"),
            @Outcome(id = "STALE", expect = Expect.FORBIDDEN, desc = "test hung up.")
    })
    @State
    public static class Remove_WaitForRelease {

        private final PoolV2<String> pool;

        public Remove_WaitForRelease() {
            pool = new PoolV2<>();
            pool.open();
            pool.add(ITEM_1);

            try {
                pool.acquire();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        @Actor
        public void actor1()  {
            try {
                pool.remove(ITEM_1);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        @Signal
        public void sig() {
            pool.release(ITEM_1);
        }
    }



    @JCStressTest
    @Outcome(id = "null, A1", expect = Expect.ACCEPTABLE, desc = "T1 acquired X, and T2 not")
    @Outcome(id = "A1, null", expect = Expect.ACCEPTABLE, desc = "T2 acquired X, and T1 not")
    @State
    public static class Aq_OnlyOne {
        private final PoolV2<String> pool;

        public Aq_OnlyOne() {
            pool = new PoolV2<>();
            pool.open();
            pool.add(ITEM_1);
        }

        @Actor
        public void actor1(LL_Result r)  {
            try {
                r.r1 = pool.acquire(10, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        @Actor
        public void actor2(LL_Result r) {
            try {
                r.r2 = pool.acquire(10, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

    }

}
