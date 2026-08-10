import type { MetadataRoute } from "next";
import { site } from "@/lib/site";

export const dynamic = "force-static";

export default function manifest(): MetadataRoute.Manifest {
  return {
    name: site.name,
    short_name: site.shortName,
    description: site.description,
    start_url: "/",
    display: "standalone",
    background_color: "#1a1712",
    theme_color: "#1a1712",
    icons: [
      {
        src: "/images/app-icon.png",
        sizes: "192x192",
        type: "image/png",
      },
    ],
  };
}