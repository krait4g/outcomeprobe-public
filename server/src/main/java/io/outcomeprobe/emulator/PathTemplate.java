package io.outcomeprobe.emulator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Contract path template을 실제 요청 경로와 매칭하는 순수 도메인 도구다.
 *
 * <p>책임: {@code /v1/payments/{paymentId}/confirm} 같은 template에서 변수를
 * 추출하고 들어온 요청 경로와의 일치 여부를 판정한다.</p>
 * <p>핵심 불변식: 정규식 문자는 전부 이스케이프되므로 template이 사용자 입력이어도
 * regex injection이 불가능하다. 변수 값은 path segment 하나에만 매칭된다.</p>
 *
 * @param template 원본 template 문자열
 * @param pattern 매칭용 정규식
 * @param variableNames template에 등장하는 변수 이름 순서 목록
 */
public record PathTemplate(String template, Pattern pattern, List<String> variableNames) {

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{([^/{}]+)}");

    /**
     * template을 컴파일해 매칭 준비된 인스턴스를 만든다.
     *
     * @param template 경로 template
     * @return 컴파일된 PathTemplate
     */
    public static PathTemplate compile(String template) {
        List<String> names = new ArrayList<>();
        Matcher variableMatcher = VARIABLE_PATTERN.matcher(template);
        StringBuilder regex = new StringBuilder("^");
        int last = 0;
        while (variableMatcher.find()) {
            regex.append(Pattern.quote(template.substring(last, variableMatcher.start())));
            regex.append("([^/]+)");
            names.add(variableMatcher.group(1));
            last = variableMatcher.end();
        }
        regex.append(Pattern.quote(template.substring(last)));
        regex.append("$");
        return new PathTemplate(template, Pattern.compile(regex.toString()), List.copyOf(names));
    }

    /**
     * 요청 경로를 매칭하고 추출된 변수를 반환한다.
     *
     * @param path 요청 경로
     * @return 매칭 성공 시 변수 맵, 실패 시 empty
     */
    public Optional<Map<String, String>> match(String path) {
        Matcher matcher = pattern.matcher(path);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        Map<String, String> variables = new LinkedHashMap<>();
        for (int i = 0; i < variableNames.size(); i++) {
            variables.put(variableNames.get(i), matcher.group(i + 1));
        }
        return Optional.of(variables);
    }
}
