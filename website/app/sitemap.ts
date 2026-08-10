import type { MetadataRoute } from "next";
import { site } from "@/lib/site";
import { allDocs } from "@/lib/docs";
import { blogPosts } from "@/content/blog";

export const dynamic = "force-static";

export default function sitemap(): MetadataRoute.Sitemap {
  const now = new Date();
  const staticRoutes = [
    "",
    "/features",
    "/models",
    "/downloads",
    "/roadmap",
    "/changelog",
    "/docs",
    "/blog",
    "/community",
    "/github",
    "/contributors",
    "/about",
    "/privacy",
    "/license",
  ];

  return [
    ...staticRoutes.map((r) => ({
      url: `${site.url}${r}`,
      lastModified: now,
      changeFrequency: "weekly" as const,
      priority: r === "" ? 1 : 0.8,
    })),
    ...allDocs.map((d) => ({
      url: `${site.url}/docs/${d.slug}`,
      lastModified: now,
      changeFrequency: "weekly" as const,
      priority: 0.7,
    })),
    ...blogPosts.map((p) => ({
      url: `${site.url}/blog/${p.slug}`,
      lastModified: now,
      changeFrequency: "monthly" as const,
      priority: 0.6,
    })),
  ];
}