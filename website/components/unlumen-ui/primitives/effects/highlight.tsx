"use client";

import * as React from "react";
import { motion } from "framer-motion";
import { cn } from "@/lib/utils";

type HighlightContextValue = {
  containerRef: React.RefObject<HTMLDivElement | null>;
  setActive: (rect: DOMRect | null) => void;
};

const HighlightContext = React.createContext<HighlightContextValue | null>(null);

function useHighlight() {
  return React.useContext(HighlightContext);
}

type HighlightProps = React.HTMLAttributes<HTMLDivElement> & {
  containerClassName?: string;
  mode?: string;
  controlledItems?: boolean;
  hover?: boolean;
};

export function Highlight({
  className,
  style,
  containerClassName,
  children,
  mode,
  controlledItems,
  hover,
  ...props
}: HighlightProps) {
  const containerRef = React.useRef<HTMLDivElement>(null);
  const [activeRect, setActiveRect] = React.useState<DOMRect | null>(null);
  const [isHovered, setIsHovered] = React.useState(false);

  const setActive = React.useCallback((rect: DOMRect | null) => {
    setActiveRect(rect);
  }, []);

  const ctx = React.useMemo(
    () => ({ containerRef, setActive }),
    [containerRef, setActive]
  );

  return (
    <HighlightContext.Provider value={ctx}>
      <div
        ref={containerRef}
        className={cn("relative", containerClassName)}
        onMouseLeave={() => {
          setIsHovered(false);
          setActiveRect(null);
        }}
        onMouseEnter={() => setIsHovered(true)}
        {...props}
      >
        {/* Highlight backdrop — monochrome white/10, matches navbar black */}
        {activeRect && (
          <motion.div
            layout
            className={cn("absolute pointer-events-none", className)}
            style={{
              left: activeRect.left,
              top: activeRect.top,
              width: activeRect.width,
              height: activeRect.height,
              ...style,
            }}
            initial={false}
            animate={{
              opacity: isHovered ? 1 : 0,
            }}
            transition={{ type: "spring", bounce: 0, stiffness: 350, damping: 32 }}
          />
        )}
        <div className="relative z-10">{children}</div>
      </div>
    </HighlightContext.Provider>
  );
}

type HighlightItemProps = React.HTMLAttributes<HTMLElement> & {
  asChild?: boolean;
};

export function HighlightItem({ asChild, children, className, ...props }: HighlightItemProps) {
  const ctx = useHighlight();
  const itemRef = React.useRef<HTMLElement>(null);

  // Merge refs if asChild
  const setRefs = React.useCallback(
    (node: HTMLElement | null) => {
      (itemRef as React.MutableRefObject<HTMLElement | null>).current = node;
      // If asChild and child has ref, we can't easily merge without cloning, but we handle via callback
    },
    []
  );

  const updateRect = React.useCallback(() => {
    if (!ctx?.containerRef.current || !itemRef.current) return;
    const containerRect = ctx.containerRef.current.getBoundingClientRect();
    const rect = itemRef.current.getBoundingClientRect();
    ctx.setActive(
      new DOMRect(rect.left - containerRect.left, rect.top - containerRect.top, rect.width, rect.height)
    );
  }, [ctx]);

  const handleEnter = React.useCallback(() => {
    updateRect();
  }, [updateRect]);

  const handleLeave = React.useCallback(() => {
    // Keep highlight until next item or container leave handles clear
  }, []);

  if (asChild && React.isValidElement(children)) {
    const child = children as React.ReactElement<any>;
    return React.cloneElement(child, {
      ref: (node: HTMLElement) => {
        setRefs(node);
        const childRef = (child as any).ref;
        if (typeof childRef === "function") childRef(node);
        else if (childRef) childRef.current = node;
      },
      onMouseEnter: (e: React.MouseEvent) => {
        handleEnter();
        (child.props as any).onMouseEnter?.(e);
      },
      onFocus: (e: React.FocusEvent) => {
        handleEnter();
        (child.props as any).onFocus?.(e);
      },
      onMouseLeave: (e: React.MouseEvent) => {
        handleLeave();
        (child.props as any).onMouseLeave?.(e);
      },
    } as any);
  }

  return (
    <div
      ref={itemRef as any}
      className={className}
      onMouseEnter={handleEnter}
      onFocus={handleEnter as any}
      {...props}
    >
      {children}
    </div>
  );
}

export default Highlight;
