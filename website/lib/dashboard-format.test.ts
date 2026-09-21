import { describe, expect, it } from "vitest";
import {
  currentStreak,
  dayLabel,
  dowName,
  engineSplit,
  failureRate,
  formatCompact,
  formatDuration,
  formatGrowth,
  formatMs,
  formatPercent,
  groupByWeek,
  growthRate,
  hourLabel,
  isEmptyTotals,
  orderBuckets,
  peakSplit,
  relativeTime,
  sliceRange,
  sumDays,
  timeLabel,
  weekdaySplit,
} from "./dashboard-format";

describe("formatCompact", () => {
  it("passes small numbers through", () => {
    expect(formatCompact(0)).toBe("0");
    expect(formatCompact(950)).toBe("950");
  });
  it("compacts thousands and millions", () => {
    expect(formatCompact(1500)).toBe("1.5K");
    expect(formatCompact(2000)).toBe("2K");
    expect(formatCompact(2_500_000)).toBe("2.5M");
  });
  it("handles non-finite input", () => {
    expect(formatCompact(NaN)).toBe("—");
  });
});

describe("formatMs", () => {
  it("formats ms, seconds, minutes", () => {
    expect(formatMs(800)).toBe("800 ms");
    expect(formatMs(2500)).toBe("2.5 s");
    expect(formatMs(90_000)).toBe("1.5 min");
  });
  it("is null-safe", () => {
    expect(formatMs(null)).toBe("—");
    expect(formatMs(undefined)).toBe("—");
  });
});

describe("labels", () => {
  it("formats day labels", () => {
    expect(dayLabel("2026-09-21")).toBe("Sep 21");
    expect(dayLabel("junk")).toBe("junk");
  });
  it("formats timestamps with date and time", () => {
    expect(timeLabel("2026-09-21T13:25:00.000Z")).toMatch(/Sep 21/);
    expect(timeLabel("junk")).toBe("junk");
  });
});

describe("failureRate", () => {
  it("computes the share and guards empty totals", () => {
    expect(failureRate(1, 4)).toBeCloseTo(0.25);
    expect(failureRate(0, 0)).toBeNull();
    expect(formatPercent(failureRate(1, 4))).toBe("25%");
    expect(formatPercent(null)).toBe("—");
  });
});

describe("isEmptyTotals", () => {
  it("detects connected-but-unused accounts", () => {
    expect(isEmptyTotals({ chats: 0, events: 0, total_tokens: 0, sessions: 0 })).toBe(true);
    expect(isEmptyTotals({ chats: 1, events: 1, total_tokens: 10, sessions: 0 })).toBe(false);
  });
});

describe("engineSplit", () => {
  it("omits empty slices so the donut never renders zeros", () => {
    expect(engineSplit(0, 0)).toEqual([]);
    expect(engineSplit(600, 0)).toEqual([{ name: "Local", value: 600 }]);
    expect(engineSplit(600, 200)).toHaveLength(2);
  });
});

describe("ranges and growth", () => {
  const rows = [
    { day: "2026-09-18", chats: 1, total_tokens: 100, sessions: 1, errors: 0 },
    { day: "2026-09-19", chats: 2, total_tokens: 200, sessions: 1, errors: 0 },
    { day: "2026-09-20", chats: 3, total_tokens: 300, sessions: 2, errors: 1 },
    { day: "2026-09-21", chats: 4, total_tokens: 400, sessions: 2, errors: 0 },
  ];
  it("slices ranges and sums", () => {
    expect(sliceRange(rows, 7)).toHaveLength(4);
    expect(sumDays(rows, (r) => r.chats)).toBe(10);
  });
  it("computes growth safely", () => {
    expect(growthRate(10, 5)).toBeCloseTo(1);
    expect(growthRate(5, 0)).toBeNull();
    expect(formatGrowth(0.25)).toEqual({ text: "+25.0%", up: true });
    expect(formatGrowth(null)).toEqual({ text: "—", up: null });
  });
  it("groups weeks and streaks", () => {
    expect(groupByWeek(rows).length).toBeGreaterThan(0);
    expect(currentStreak(rows, "2026-09-21")).toBe(4);
    expect(currentStreak([{ day: "2026-09-19", chats: 1 }], "2026-09-21")).toBe(0);
  });
});

describe("heatmap helpers", () => {
  it("names days and hours", () => {
    expect(dowName(0)).toBe("Sun");
    expect(dowName(1)).toBe("Mon");
    expect(hourLabel(0)).toBe("12a");
    expect(hourLabel(13)).toBe("1p");
  });
  it("splits weekday/weekend and peak/off-hours", () => {
    const cells = [
      { dow: 1, hour: 9, chats: 10, tokens: 100 },
      { dow: 6, hour: 10, chats: 4, tokens: 40 },
    ];
    const { weekday, weekend } = weekdaySplit(cells);
    expect(weekday.chats).toBe(10);
    expect(weekend.chats).toBe(4);
    const { peak, off } = peakSplit(cells);
    expect(peak.chats + off.chats).toBe(14);
  });
  it("orders buckets canonically", () => {
    expect(orderBuckets([{ bucket: "1k-2k", count: 2 }], ["0-100", "1k-2k"]).map((b) => b.count)).toEqual([0, 2]);
  });
});

describe("freshness helpers", () => {
  it("formats durations and relative times", () => {
    expect(formatDuration(45)).toBe("45s");
    expect(formatDuration(150)).toBe("2.5m");
    expect(formatDuration(null)).toBe("—");
    expect(relativeTime(new Date(Date.now() - 5 * 60_000).toISOString())).toBe("5m ago");
    expect(relativeTime(null)).toBe("—");
  });
});
