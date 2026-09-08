"use client";

import { useEffect, useRef } from "react";
import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";

gsap.registerPlugin(ScrollTrigger);

export function SmoothScroll({ children }: { children: React.ReactNode }) {
  const wrapperRef = useRef<HTMLDivElement | null>(null);
  const contentRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    const coarse = window.matchMedia("(pointer: coarse)").matches;
    // Mobile and reduced-motion stay native — fixed wrapper breaks sticky, text selection, and causes input jank
    // Apply smooth only on fine-pointer desktop
    const shouldSmooth = !reduce && !coarse;
    if (!shouldSmooth) {
      document.documentElement.classList.add("native-scroll");
      document.documentElement.classList.remove("is-smooth");
      return;
    }

    const content = contentRef.current;
    const wrapper = wrapperRef.current;
    if (!content || !wrapper) return;

    document.documentElement.classList.add("is-smooth");
    document.documentElement.classList.remove("native-scroll");

    let target = window.scrollY;
    let current = target;
    let raf = 0;
    let ticking = false;
    const lerp = 0.082;
    const clampTarget = () => {
      const max = Math.max(0, content.getBoundingClientRect().height - window.innerHeight);
      if (target < 0) target = 0;
      if (target > max) target = max;
    };

    const setBodyHeight = () => {
      const h = content.getBoundingClientRect().height;
      document.body.style.height = `${Math.ceil(h)}px`;
      // wrapper holds the visual content fixed and translated — keep it full-width
      wrapper.style.position = "fixed";
      wrapper.style.top = "0";
      wrapper.style.left = "0";
      wrapper.style.width = "100%";
      wrapper.style.willChange = "transform";
      ScrollTrigger.refresh();
    };

    // initial
    setBodyHeight();

    const onScroll = () => {
      target = window.scrollY;
      clampTarget();
      if (!ticking) {
        ticking = true;
        raf = requestAnimationFrame(tick);
      }
    };

    const onResize = () => {
      setBodyHeight();
      target = window.scrollY;
      current = target;
      content.style.transform = `translate3d(0, ${-current}px, 0)`;
      ScrollTrigger.update();
    };

    const onAnchorClick = (e: Event) => {
      const a = e.target as HTMLElement;
      const link = a.closest('a[href^="#"]') as HTMLAnchorElement | null;
      if (!link) return;
      const id = link.getAttribute("href");
      if (!id || id === "#") return;
      const el = document.querySelector(id);
      if (!el) return;
      e.preventDefault();
      const rect = el.getBoundingClientRect();
      // target is window scroll position — lerp will glide there
      const dest = rect.top + current;
      window.scrollTo({ top: dest, behavior: "auto" });
      target = dest;
      clampTarget();
    };

    const tick = () => {
      ticking = false;
      const diff = target - current;
      if (Math.abs(diff) < 0.15) {
        if (current !== target) {
          current = target;
          content.style.transform = `translate3d(0, ${-current}px, 0)`;
          // dispatch a custom event so WebGL can use the lerped value
          window.dispatchEvent(new CustomEvent("smooth-scroll:lerped", { detail: current }));
          ScrollTrigger.update();
        }
        return;
      }
      current += diff * lerp;
      content.style.transform = `translate3d(0, ${-current}px, 0)`;
      window.dispatchEvent(new CustomEvent("smooth-scroll:lerped", { detail: current }));
      ScrollTrigger.update();
      raf = requestAnimationFrame(tick);
      ticking = true;
    };

    // Sync target on scroll
    window.addEventListener("scroll", onScroll, { passive: true });
    window.addEventListener("resize", onResize);
    document.addEventListener("click", onAnchorClick);
    ScrollTrigger.addEventListener("refresh", setBodyHeight);

    // Use GSAP ticker for buttery sync with ScrollTrigger
    const gsapTick = () => {
      // keep body height correct after images load
      // only run tick if there's distance
      if (Math.abs(target - current) >= 0.15) {
        if (!ticking) {
          ticking = true;
          raf = requestAnimationFrame(tick);
        }
      }
    };
    gsap.ticker.add(gsapTick);

    // Observe content height changes (images, async)
    const ro = new ResizeObserver(() => setBodyHeight());
    ro.observe(content);
    // Re-measure when child images/fonts settle
    const onLoad = () => setBodyHeight();
    window.addEventListener("load", onLoad);

    // handle back/forward restoring scroll
    const onPageShow = () => {
      target = window.scrollY;
      current = target;
      content.style.transform = `translate3d(0, ${-current}px, 0)`;
    };
    window.addEventListener("pageshow", onPageShow);

    return () => {
      cancelAnimationFrame(raf);
      gsap.ticker.remove(gsapTick);
      window.removeEventListener("scroll", onScroll);
      window.removeEventListener("resize", onResize);
      window.removeEventListener("load", onLoad);
      window.removeEventListener("pageshow", onPageShow);
      document.removeEventListener("click", onAnchorClick);
      ScrollTrigger.removeEventListener("refresh", setBodyHeight);
      ro.disconnect();
      document.documentElement.classList.remove("is-smooth");
      document.body.style.height = "";
      if (wrapper) {
        wrapper.style.position = "";
        wrapper.style.top = "";
        wrapper.style.left = "";
        wrapper.style.width = "";
        wrapper.style.willChange = "";
      }
      if (content) content.style.transform = "";
      window.dispatchEvent(new CustomEvent("smooth-scroll:destroyed"));
    };
  }, []);

  return (
    <div ref={wrapperRef} data-smooth-wrapper>
      <div ref={contentRef} data-smooth-content>
        {children}
      </div>
    </div>
  );
}

// Tiny hook for other components to read lerped scroll without window.scrollY jitter
export function useLerpedScroll(cb: (y: number) => void) {
  const cbRef = useRef(cb);
  cbRef.current = cb;
  useEffect(() => {
    const h = (e: Event) => cbRef.current((e as CustomEvent).detail);
    window.addEventListener("smooth-scroll:lerped", h as EventListener);
    return () => window.removeEventListener("smooth-scroll:lerped", h as EventListener);
  }, []);
}
