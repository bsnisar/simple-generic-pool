package pool;

import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.LL_Result;
import org.openjdk.jcstress.infra.results.ZZL_Result;
import org.openjdk.jcstress.infra.results.ZZ_Result;

import java.util.concurrent.TimeUnit;

import static java.util.Objects.requireNonNull;

public class PoolV2_Entry_StressTest {

    @JCStressTest()
    @Outcome(id = "true, true, IDLE", expect = Expect.ACCEPTABLE, desc = "acq, release")
    @Outcome(id = "true, true, IN_USE", expect = Expect.ACCEPTABLE, desc = "release, acq")
    @State
    public static class AllocateAndRelease {

        final PoolV2.PooledEntry<String> e =  new PoolV2.DefaultPooledEntry<>("");


        @Actor
        public void actor1(ZZL_Result r) {
            r.r1 = e.allocate();
            r.r3 = e.state();
        }


        @Actor
        public void actor2(ZZL_Result r) {
            r.r2 = e.release();
            r.r3 = e.state();
        }
    }


    @JCStressTest()
    @Outcome(id = "true, false, TOMBSTONE", expect = Expect.ACCEPTABLE, desc = "release, mark")
    @Outcome(id = "false, true, TOMBSTONE", expect = Expect.ACCEPTABLE, desc = "mark, release")
    @State
    public static class ReleaseMark {

        final PoolV2.PooledEntry<String> e;

        public ReleaseMark() {
            e = new PoolV2.DefaultPooledEntry<>("");
            e.allocate();
        }

        @Actor
        public void actor2(ZZL_Result r) {
            r.r1 = e.release();
            r.r3 = e.state();
        }

        @Actor
        public void actor3(ZZL_Result r) {
            r.r2 = e.markTombstone();
            r.r3 = e.state();
        }
    }

}
