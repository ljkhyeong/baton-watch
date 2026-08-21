package com.personal.baton.watch.adapter.out.external.http;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.function.LongConsumer;
import org.apache.hc.core5.http.ContentTooLongException;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.util.Args;

/**
 * 제한된 바이트만 소비하고 응답 본문 내용을 절대 보관하지 않는다. 실패한 스트림은 열린 상태로
 * 두어 소유 응답이 본문을 끝까지 소비하는 대신 중단할 수 있게 한다.
 */
public final class ResponseBodyDiscarder {

    private static final int BUFFER_SIZE = 8 * 1024;

    public long discard(HttpEntity entity, long limit, LongConsumer progress) throws IOException {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(progress, "progress");
        Args.notNegative(limit, "response byte limit");
        long declaredLength = entity.getContentLength();
        if (declaredLength > limit) {
            throw tooLarge();
        }
        if (limit == 0) {
            if (declaredLength == 0) {
                return closeCompleted(entity.getContent(), 0);
            }
            throw tooLarge();
        }

        long consumed = 0;
        byte[] buffer = new byte[(int) Math.min(BUFFER_SIZE, limit)];
        InputStream input = entity.getContent();
        while (true) {
            int requested = (int) Math.min(buffer.length, limit - consumed);
            if (requested == 0) {
                if (declaredLength == limit) {
                    return closeCompleted(input, consumed);
                }
                // 길이를 알 수 없는 스트림은 상한을 초과해 소비하지 않고는 탐색할 수 없으므로,
                // 상한에 도달하면 보수적으로 거부한다.
                throw tooLarge();
            }
            int read = input.read(buffer, 0, requested);
            if (read == -1) {
                return closeCompleted(input, consumed);
            }
            consumed += read;
            progress.accept(consumed);
        }
    }

    private static long closeCompleted(InputStream input, long consumed) throws IOException {
        input.close();
        return consumed;
    }

    private static ContentTooLongException tooLarge() {
        return new ContentTooLongException("response exceeded byte limit");
    }
}
