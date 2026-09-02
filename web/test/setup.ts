import { afterEach, beforeEach } from 'vitest';
import { cleanup } from '@testing-library/react';
import {
  clearControlApiToken,
  resetControlTokenForTest,
  setControlApiToken,
} from '../src/auth/controlToken';

/** Web 단위 테스트는 실제 제품과 같은 인증 header 경계를 기본으로 통과한다. */
beforeEach(() => {
  resetControlTokenForTest();
  window.sessionStorage.clear();
  setControlApiToken('unit-test-control-token-0123456789abcdef');
});

/** 테스트 사이에서 탭 token과 event 상태가 섞이지 않게 정리한다. */
afterEach(() => {
  cleanup();
  clearControlApiToken();
  window.sessionStorage.clear();
  resetControlTokenForTest();
});
