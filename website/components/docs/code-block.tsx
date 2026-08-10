"use client";

import { useState } from "react";
import { motion, useReducedMotion } from "framer-motion";
import { PrismLight as SyntaxHighlighter } from "react-syntax-highlighter";
import vscDarkPlus from "react-syntax-highlighter/dist/esm/styles/prism/vsc-dark-plus";
import { Check, Copy } from "lucide-react";
import { cn } from "@/lib/utils";

const supported = new Set([
  "bash",
  "shell",
  "sh",
  "kotlin",
  "java",
  "groovy",
  "json",
  "xml",
  "html",
  "css",
  "javascript",
  "typescript",
  "python",
  "sql",
  "diff",
  "markdown",
  "yaml",
  "properties",
  "gradle",
  "plaintext",
  "text",
]);

export function CodeBlock({ code, lang, className }: { code: string; lang?: string; className?: string }) {
  const [copied, setCopied] = useState(false);
  const reduce = useReducedMotion();
  const language = supported.has(lang ?? "") ? lang : undefined;

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(code);
      setCopied(true);
      setTimeout(() => setCopied(false), 1800);
    } catch {
      /* clipboard unavailable — noop */
    }
  };

  return (
    <motion.div
      initial={reduce ? { opacity: 1 } : { opacity: 0, y: 14 }}
      whileInView={reduce ? undefined : { opacity: 1, y: 0 }}
      viewport={{ once: true, margin: "-40px" }}
      transition={{ duration: 0.5, ease: [0.22, 1, 0.36, 1] }}
      className={cn("group/code relative overflow-hidden rounded-card border border-[var(--line)] bg-[var(--code-bg)] shadow-card", className)}
    >
      <div className="flex items-center justify-between border-b border-[var(--line)] bg-[var(--code-bar)] px-4 py-2">
        <span className="font-mono text-[10px] uppercase tracking-widest text-[var(--faint)]">
          {lang ? `${lang}` : "code"}
        </span>
        <motion.button
          type="button"
          onClick={copy}
          whileTap={reduce ? undefined : { scale: 0.94 }}
          className="inline-flex items-center gap-1.5 rounded-pill border border-[var(--line)] bg-[var(--surface)] px-2.5 py-1 font-mono text-[10px] font-semibold text-[var(--muted)] transition-colors hover:border-[var(--accent)] hover:text-[var(--accent-deep)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)] dark:hover:text-[var(--accent-soft)]"
          aria-label="Copy code block"
        >
          {copied ? <motion.span initial={reduce ? undefined : { rotate: -90 }} animate={{ rotate: 0 }}><Check className="size-3 text-[var(--ok)]" /></motion.span> : <Copy className="size-3" />}
          {copied ? "Copied" : "Copy"}
        </motion.button>
      </div>
      <SyntaxHighlighter
        language={language}
        style={vscDarkPlus}
        customStyle={{
          margin: 0,
          background: "transparent",
          padding: "1rem 1.25rem",
          fontSize: "0.8125rem",
          lineHeight: 1.7,
        }}
        codeTagProps={{ style: { fontFamily: "var(--font-mono)" } }}
        wrapLongLines
      >
        {code}
      </SyntaxHighlighter>
      <div className="pointer-events-none absolute inset-x-0 top-0 h-px bg-gradient-to-r from-transparent via-[var(--line)] to-transparent opacity-70" aria-hidden />
    </motion.div>
  );
}