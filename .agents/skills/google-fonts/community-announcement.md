# Google Fonts Skill for Claude Code

I built a Claude Code skill that handles typography system generation using Google Fonts.

**What it does:**
- Searches 1,923 Google Fonts enriched with personality tags, contrast levels, and body-suitability ratings
- Suggests single fonts or proven pairings (73 curated pairs with contrast type classification)
- Generates complete CSS custom properties, Tailwind config, and Google Fonts embed links
- 8 modular type scales from minor-second (dense UI) to golden-ratio (hero sections)

**Install:**
```bash
claude plugin marketplace add sliday/google-fonts-skill
claude plugin install google-fonts
```

**Live gallery:** [100 pre-made typography systems](https://sliday.github.io/google-fonts-skill/) you can browse and pick from

**Repo:** https://github.com/sliday/google-fonts-skill

The skill activates automatically when you mention fonts, typography, or type scales. Say "choose a font for my SaaS dashboard" and it searches, recommends, and generates the full system.
