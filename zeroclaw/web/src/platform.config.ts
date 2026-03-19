// Copyright (c) 2026 @Natfii. All rights reserved.
// Platform whitelist — Android feature filter.
// On upstream pulls: keep this file unchanged, rebuild.

export const platform = {
  id: "android",
  pages: {
    dashboard:    true,
    agent:        true,
    tools:        true,
    cron:         true,
    memory:       true,
    cost:         true,
    doctor:       true,
    config:       false,
    integrations: false,
    logs:         false,
  },
} as const;

export type PageId = keyof typeof platform.pages;
export const isPageEnabled = (page: PageId): boolean => platform.pages[page];
