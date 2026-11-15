package io.todu.goweb;

import java.util.concurrent.*;

/** Bounded concurrency; idle threads release their stacks after 30 seconds. */
final class BackgroundWorkers {
    static ExecutorService create(int count) {
        ThreadPoolExecutor pool=new ThreadPoolExecutor(count,count,30,TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), task -> new Thread(() -> {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                task.run();
            }, "GoWeb-files"));
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }
    private BackgroundWorkers() {}
}
