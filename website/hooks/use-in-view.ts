"use client";

import { useEffect, useRef, useState, type RefObject } from "react";

type Options = {
  threshold?: number | number[];
  rootMargin?: string;
  once?: boolean;
  amount?: number;
};

export function useInView<T extends Element = Element>(options: Options = {}): {
  ref: RefObject<T | null>;
  inView: boolean;
} {
  const { threshold = 0.18, rootMargin = "-40px 0px", once = false, amount } = options;
  const ref = useRef<T | null>(null);
  const [inView, setInView] = useState(false);

  useEffect(() => {
    const node = ref.current;
    if (!node) return;

    if (typeof IntersectionObserver === "undefined") {
      setInView(true);
      return;
    }

    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) {
            setInView(true);
            if (once) observer.disconnect();
          } else if (!once) {
            setInView(false);
          }
        }
      },
      {
        threshold: amount ?? threshold,
        rootMargin,
      }
    );

    observer.observe(node);
    return () => observer.disconnect();
  }, [threshold, rootMargin, once, amount]);

  return { ref, inView };
}