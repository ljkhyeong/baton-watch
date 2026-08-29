package com.personal.baton.watch.bootstrap;

final class BestEffortMetrics {

    private BestEffortMetrics() {
    }

    static void record(Runnable recording) {
        try {
            recording.run();
        } catch (RuntimeException ignored) {
            // 텔레메트리 실패가 업무 결과나 재시도 의미를 바꾸어서는 안 된다.
        }
    }
}
