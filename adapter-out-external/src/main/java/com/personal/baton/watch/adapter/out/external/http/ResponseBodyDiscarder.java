package com.personal.baton.watch.adapter.out.external.http;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import org.apache.hc.core5.http.ContentTooLongException;
import org.apache.hc.core5.http.HttpEntity;

/**
 * 상한까지만 읽고 버린다. 읽은 바이트는 메서드 안에서 잠시 보유할 뿐 보관하거나 기록하지 않는다.
 * 실패한 스트림은 열린 상태로 두어 소유 응답이 본문을 끝까지 소비하는 대신 중단할 수 있게 한다.
 */
public final class ResponseBodyDiscarder {

    public void discard(HttpEntity entity, long limit) throws IOException {
        Objects.requireNonNull(entity, "entity");
        long declaredLength = entity.getContentLength();
        if (declaredLength > limit) {
            throw tooLarge();
        }
        InputStream input = entity.getContent();
        // readNBytes는 상한을 넘는 바이트를 요청하지 않는다. 상한 도달 뒤의 0바이트 읽기는
        // Apache 본문 스트림이 소켓을 읽지 않고 바로 반환한다.
        int read = input.readNBytes(Math.toIntExact(limit)).length;
        if (read == limit && declaredLength != limit) {
            // 상한을 넘겨 읽지 않고는 끝을 확인할 수 없으므로 보수적으로 거부한다.
            throw tooLarge();
        }
        input.close();
    }

    private static ContentTooLongException tooLarge() {
        return new ContentTooLongException("response exceeded byte limit");
    }
}
