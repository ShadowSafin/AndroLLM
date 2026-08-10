"use client";

import { usePathname } from "next/navigation";
import { useRef } from "react";
import { gsap } from "gsap";
import { useGSAP } from "@gsap/react";

gsap.registerPlugin(useGSAP);

const TEXT_SELECTOR =
  "h1, h2, h3, h4, h5, p, li, dt, dd, blockquote, figcaption, th, td, .ledger, label";

function isHeading(el: Element): boolean {
  return /^H[1-5]$/.test(el.tagName);
}

function collectVisible(root: HTMLElement): Element[] {
  const seen = new Set<Element>();
  const collect = (node: Element): void => {
    const children = Array.from(node.children);
    for (const child of children) {
      if (!child.matches(TEXT_SELECTOR)) {
        if (!isHeading(child)) collect(child);
        continue;
      }
      const rect = child.getBoundingClientRect();
      const style = getComputedStyle(child);
      if (
        rect.width > 0 &&
        rect.height > 0 &&
        style.display !== "none" &&
        style.visibility !== "hidden" &&
        !child.closest("[data-text-cascade-exclude]")
      ) {
        seen.add(child);
      }
    }
  };
  collect(root);
  return Array.from(seen);
}

export function TextCascade({ children }: { children: React.ReactNode }) {
  const mainRef = useRef<HTMLElement>(null);
  const pathname = usePathname();

  useGSAP(
    () => {
      const root = mainRef.current;
      if (!root) return;

      const targets = collectVisible(root);
      if (targets.length === 0) return;

      const headings = targets.filter(isHeading);
      const body = targets.filter((el) => !isHeading(el));

      const mm = gsap.matchMedia();

      mm.add("(prefers-reduced-motion: reduce)", () => {
        gsap.set(targets, { clearProps: "all" });
      });

      mm.add("(prefers-reduced-motion: no-preference)", () => {
        gsap.set(headings, { opacity: 0, y: 12, filter: "blur(3px)" });
        gsap.set(body, { opacity: 0, y: 10, willChange: "transform, opacity" });

        const tl = gsap.timeline({ defaults: { ease: "power3.out" } });

        headings.forEach((el, i) => {
          tl.to(
            el,
            { opacity: 1, y: 0, filter: "blur(0px)", duration: 0.4 },
            0.03 + i * 0.06
          );
        });

        body.forEach((el, i) => {
          tl.to(
            el,
            { opacity: 1, y: 0, duration: 0.32, clearProps: "willChange" },
            0.06 + Math.min(headings.length * 0.06, 0.5) + i * 0.012
          );
        });
      });

      return () => {
        mm.revert();
      };
    },
    { scope: mainRef, dependencies: [pathname], revertOnUpdate: true }
  );

  return (
    <main id="main" ref={mainRef} data-text-cascade>
      {children}
    </main>
  );
}