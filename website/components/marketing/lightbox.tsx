"use client";

import { useState } from "react";
import Image from "next/image";
import { Button } from "@/components/ui/button";
import { X, ZoomIn } from "lucide-react";
import { Dialog, DialogContent, DialogTitle } from "@/components/ui/dialog";

export function Lightbox({ src, alt, open, onClose }: { src: string; alt: string; open: boolean; onClose: () => void }) {
  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-4xl border-none bg-transparent p-0 shadow-none">
        <DialogTitle className="sr-only">{alt}</DialogTitle>
        <figure>
          <div className="overflow-hidden rounded-card border border-[var(--line)] bg-[var(--surface)] shadow-2xl">
            <Image
              src={src}
              alt={alt}
              width={1600}
              height={1000}
              className="h-auto w-full object-contain"
              sizes="100vw"
            />
          </div>
          <figcaption className="mt-3 text-center text-sm text-[var(--muted)]">{alt}</figcaption>
        </figure>
        <div className="flex justify-center">
          <Button variant="secondary" size="sm" onClick={onClose}>
            <X className="size-4" />
            Close
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

export function LightboxButton({
  src,
  alt,
  label,
  className,
}: {
  src: string;
  alt: string;
  label?: string;
  className?: string;
}) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button
        type="button"
        onClick={() => setOpen(true)}
        aria-label={`View ${alt}`}
        className={className}
      >
        {label ?? <ZoomIn className="size-4" />}
      </button>
      <Lightbox src={src} alt={alt} open={open} onClose={() => setOpen(false)} />
    </>
  );
}