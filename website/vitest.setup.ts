import { vi } from "vitest";

// Recharts measures with ResizeObserver (absent in jsdom) — fixed stub.
class StubResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}
vi.stubGlobal("ResizeObserver", StubResizeObserver);

// framer-motion's useInView needs IntersectionObserver (absent in jsdom).
class StubIntersectionObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
  takeRecords() {
    return [];
  }
}
vi.stubGlobal("IntersectionObserver", StubIntersectionObserver);

if (!window.matchMedia) {
  window.matchMedia = (() => ({
    matches: false,
    addListener() {},
    removeListener() {},
    addEventListener() {},
    removeEventListener() {},
    dispatchEvent: () => false,
  })) as unknown as typeof window.matchMedia;
}
