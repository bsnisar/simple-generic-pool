package pool;


import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class PoolImplBenchmark {


    @State(Scope.Benchmark)
    public static class FullState {
        public final Pool<String> pool = new PoolImpl<>();

        @Setup(Level.Iteration)
        public void doSetup() {
            for (int i = 0; i < 50; i++) {
                pool.add("" + i);
            }
        }

    }

    @Benchmark
    @Threads(10)
    @Warmup(iterations = 5)
    @Measurement(iterations = 5)
    public Object acqInthreads(FullState state) throws InterruptedException {
        return state.pool.acquire();
    }


}
