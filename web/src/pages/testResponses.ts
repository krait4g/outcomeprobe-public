/** page 단위 테스트가 실제 fetch body stream 계약을 사용하게 하는 JSON 응답 fixture다. */
export function jsonResponse(
  body: unknown,
  status = 200,
  contentType = 'application/json',
): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': contentType },
  });
}

/** raw Artifact 조회용 UTF-8 text 응답 fixture다. */
export function textResponse(body: string, status = 200): Response {
  return new Response(body, {
    status,
    headers: { 'content-type': 'text/yaml; charset=utf-8' },
  });
}
