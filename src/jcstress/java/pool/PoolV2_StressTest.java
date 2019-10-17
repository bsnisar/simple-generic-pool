package pool;

import org.openjdk.jcstress.annotations.*;

import java.util.Objects;

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

        private final PoolV2<String> pool = new PoolV2<>();

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
            if (!pool.add(ITEM_1)) {
                throw new RuntimeException("add");
            }
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
}
