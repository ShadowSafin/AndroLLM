import type { ReactNode } from "react";
import Link from "next/link";
import type { MDXComponents } from "mdx/types";
import { CodeBlock } from "@/components/docs/code-block";
import { Callout } from "@/components/docs/callout";

function HeadingLink({ level, children }: { level: 1 | 2 | 3 | 4; children: ReactNode }) {
  const text = typeof children === "string" ? children : "";
  const id = text
    .toLowerCase()
    .replace(/[^\w\s-]/g, "")
    .trim()
    .replace(/\s+/g, "-");
  const Tag = `h${level}` as "h2" | "h3" | "h4";
  const size =
    level === 2
      ? "text-2xl font-serif font-semibold mt-12 mb-4"
      : level === 3
        ? "text-xl font-serif font-semibold mt-8 mb-3"
        : "text-base font-semibold mt-6 mb-2";
  return (
    <Tag id={id} className={`group flex items-center gap-2 scroll-mt-28 text-[var(--ink)] ${size}`}>
      <span className="no-anchor">{children}</span>
      <a
        href={`#${id}`}
        aria-label={`Link to section: ${text}`}
        className="opacity-0 transition-opacity group-hover:opacity-100 text-[var(--accent-deep)] dark:text-[var(--accent-soft)]"
      >
        #
      </a>
    </Tag>
  );
}

interface PreChild {
  props?: { className?: string; children?: ReactNode };
}

function Pre({ children }: { children?: ReactNode }) {
  const child = Array.isArray(children) ? children[0] : children;
  if (!child || typeof child !== "object" || !("props" in (child as object))) {
    return <pre className="text-[var(--ink-dim)]">{children}</pre>;
  }
  const props = (child as PreChild).props;
  const className = props?.className ?? "";
  const language = className.replace("language-", "").split(" ")[0] || "text";
  const code = typeof props?.children === "string" ? props.children : "";
  return <CodeBlock code={code} lang={language} />;
}

export const mdxComponents: MDXComponents = {
  h1: ({ children }: { children?: ReactNode }) => (
    <h1 id="top" className="ledger mt-0 text-[var(--ink)]">
      {children}
    </h1>
  ),
  h2: ({ children }: { children?: ReactNode }) => <HeadingLink level={2}>{children}</HeadingLink>,
  h3: ({ children }: { children?: ReactNode }) => <HeadingLink level={3}>{children}</HeadingLink>,
  h4: ({ children }: { children?: ReactNode }) => <HeadingLink level={4}>{children}</HeadingLink>,
  p: ({ children }: { children?: ReactNode }) => (
    <p className="my-4 leading-relaxed text-[var(--ink-dim)]">{children}</p>
  ),
  a: ({ href, children }: { href?: string; children?: ReactNode }) => {
    if (href?.startsWith("http")) {
      return (
        <a href={href} target="_blank" rel="noreferrer" className="font-medium text-[var(--accent-deep)] underline decoration-[color-mix(in_srgb,var(--accent)_40%,transparent)] underline-offset-4 hover:text-[var(--ink)] dark:text-[var(--accent-soft)]">
          {children}
        </a>
      );
    }
    if (href?.startsWith("/")) {
      return (
        <Link href={href} className="font-medium text-[var(--accent-deep)] underline decoration-[color-mix(in_srgb,var(--accent)_40%,transparent)] underline-offset-4 hover:text-[var(--ink)] dark:text-[var(--accent-soft)]">
          {children}
        </Link>
      );
    }
    return <span className="font-medium text-[var(--accent-deep)] dark:text-[var(--accent-soft)]">{children}</span>;
  },
  ul: ({ children }: { children?: ReactNode }) => (
    <ul className="my-4 space-y-2 pl-5 text-[var(--ink-dim)] marker:text-[var(--accent)] [&>li]:pl-1 [&>li]:leading-relaxed list-disc">{children}</ul>
  ),
  ol: ({ children }: { children?: ReactNode }) => (
    <ol className="my-4 space-y-2 pl-5 text-[var(--ink-dim)] marker:font-semibold marker:text-[var(--accent)] [&>li]:pl-1 [&>li]:leading-relaxed list-decimal">{children}</ol>
  ),
  li: ({ children }: { children?: ReactNode }) => <li className="my-1">{children}</li>,
  strong: ({ children }: { children?: ReactNode }) => <strong className="font-semibold text-[var(--ink)]">{children}</strong>,
  em: ({ children }: { children?: ReactNode }) => <em className="italic text-[var(--ink)]">{children}</em>,
  hr: () => <hr className="my-8 border-[var(--line)]" />,
  blockquote: ({ children }: { children?: ReactNode }) => {
    let text = "";
    const collect = (n: ReactNode): string => {
      if (typeof n === "string") return n;
      if (Array.isArray(n)) return n.map(collect).join("");
      if (n && typeof n === "object" && "props" in (n as object)) {
        return collect((n as { props: { children?: ReactNode } }).props?.children);
      }
      return "";
    };
    text = collect(children);
    const lower = text.toLowerCase();
    const variant = lower.includes("warning") || lower.includes("caution") ? "warning" : lower.includes("error") || lower.includes("danger") ? "danger" : lower.includes("tip") || lower.includes("note:") ? "note" : "note";
    return <Callout message={text} variant={variant as "note" | "warning" | "danger" | "tip"} />;
  },
  table: ({ children }: { children?: ReactNode }) => (
    <div className="my-6 overflow-x-auto rounded-card border border-[var(--line)] bg-[var(--mutedsurface)]">
      <table className="w-full border-collapse text-sm">{children}</table>
    </div>
  ),
  thead: ({ children }: { children?: ReactNode }) => <thead className="border-b border-[var(--line)]">{children}</thead>,
  tbody: ({ children }: { children?: ReactNode }) => <tbody>{children}</tbody>,
  tr: ({ children }: { children?: ReactNode }) => <tr className="border-b border-[var(--line-soft)] last:border-0">{children}</tr>,
  th: ({ children }: { children?: ReactNode }) => (
    <th scope="col" className="px-4 py-3 text-left text-xs font-bold uppercase tracking-wider text-[var(--faint)]">{children}</th>
  ),
  td: ({ children }: { children?: ReactNode }) => (
    <td className="px-4 py-3 align-top text-[13px] leading-relaxed text-[var(--ink-dim)]">{children}</td>
  ),
  pre: ({ children }: { children?: ReactNode }) => <Pre>{children}</Pre>,
  code: ({ className, children }: { className?: string; children?: ReactNode }) => {
    const inline = !className;
    if (inline) {
      return (
        <code className="rounded bg-[color-mix(in_srgb,var(--accent)_12%,var(--mutedsurface))] px-1.5 py-0.5 font-mono text-[0.85em] text-[var(--accent-deep)] dark:text-[var(--accent-soft)]">
          {children}
        </code>
      );
    }
    return <>{children}</>;
  },
  img: ({ src, alt }: { src?: string; alt?: string }) => (
    // eslint-disable-next-line @next/next/no-img-element
    <img src={src ?? ""} alt={alt ?? ""} className="my-6 max-w-full rounded-card border border-[var(--line)] shadow-card" loading="lazy" />
  ),
};