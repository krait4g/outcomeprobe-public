package io.outcomeprobe.infrastructure.persistence;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.postgresql.util.PGobject;

/**
 * PostgreSQL JSONB 컬럼과 Java 값 사이의 공통 변환을 담당한다.
 *
 * <p>책임: 여러 persistence adapter가 반복하는 JSONB 파라미터·결과 변환을
 * 공유 인프라로 모아 PG 드라이버 타입 접촉면을 줄인다.</p>
 * <p>외부에서 오해하기 쉬운 한계: 이 타입은 persistence 경계 전용이다.
 * 도메인·애플리케이션 서비스는 JSONB 타입을 직접 다루지 않는다.</p>
 */
public final class JsonbSupport {

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    /**
     * 공유 mapper를 주입한다.
     *
     * @param objectMapper JSON 변환에 사용할 mapper
     */
    public JsonbSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 문자열 맵을 JSONB 파라미터로 변환한다.
     *
     * @param values 저장할 속성 맵, null이면 빈 객체
     * @return jsonb 타입 PGobject
     */
    public PGobject ofAttributes(Map<String, String> values) {
        return ofJson(write(values == null ? Map.of() : values));
    }

    /**
     * 임의 직렬화 가능 값을 JSONB 파라미터로 변환한다.
     *
     * @param value 저장할 값, null이면 빈 객체
     * @return jsonb 타입 PGobject
     */
    public PGobject ofValue(Object value) {
        return ofJson(write(value == null ? Map.of() : value));
    }

    /**
     * 이미 JSON인 문자열을 JSONB 파라미터로 감싼다.
     *
     * @param json JSON 문자열, null이면 "{}"
     * @return jsonb 타입 PGobject
     */
    public PGobject ofJson(String json) {
        try {
            PGobject object = new PGobject();
            object.setType("jsonb");
            object.setValue(json == null ? "{}" : json);
            return object;
        } catch (SQLException e) {
            throw new IllegalStateException("JSONB 파라미터 생성에 실패했습니다.", e);
        }
    }

    /**
     * JSONB 컬럼 텍스트를 문자열 맵으로 역직렬화한다.
     *
     * @param json 데이터베이스가 반환한 JSON 문자열, null 가능
     * @return 속성 맵, null이면 빈 맵
     */
    public Map<String, String> toAttributes(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, STRING_MAP);
        } catch (Exception e) {
            throw new IllegalStateException("JSONB 속성 해석에 실패했습니다.", e);
        }
    }

    /**
     * JSONB 컬럼 텍스트를 JSON 트리로 역직렬화한다.
     *
     * @param json 데이터베이스가 반환한 JSON 문자열, null 가능
     * @return JSON 트리, null이면 빈 객체 노드
     */
    public JsonNode toTree(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("JSONB 문서 해석에 실패했습니다.", e);
        }
    }

    /**
     * 값을 JSON 문자열로 직렬화한다.
     *
     * @param value 직렬화할 값
     * @return JSON 문자열
     */
    public String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 직렬화에 실패했습니다.", e);
        }
    }
}
