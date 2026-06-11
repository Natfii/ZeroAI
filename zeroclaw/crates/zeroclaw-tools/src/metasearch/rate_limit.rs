// Copyright (c) 2026 @Natfii. All rights reserved.

//! Sliding-window rate limiter for meta searches, backing the user-facing
//! requests-per-minute setting.

use std::collections::VecDeque;
use std::time::{Duration, Instant};

const WINDOW: Duration = Duration::from_secs(60);

/// Counts whole meta searches (not individual engine requests) in a sliding
/// 60-second window.
pub struct SearchRateLimiter {
    max_per_minute: u32,
    window: parking_lot::Mutex<VecDeque<Instant>>,
}

impl SearchRateLimiter {
    /// Creates a limiter allowing `max_per_minute` searches; `0` disables
    /// limiting entirely.
    pub fn new(max_per_minute: u32) -> Self {
        Self {
            max_per_minute,
            window: parking_lot::Mutex::new(VecDeque::new()),
        }
    }

    /// Records one search if the window has room, or returns the number of
    /// seconds until the oldest in-window search expires.
    pub fn try_acquire(&self) -> Result<(), u64> {
        self.try_acquire_at(Instant::now())
    }

    fn try_acquire_at(&self, now: Instant) -> Result<(), u64> {
        if self.max_per_minute == 0 {
            return Ok(());
        }
        let mut window = self.window.lock();
        while let Some(oldest) = window.front() {
            if now.duration_since(*oldest) >= WINDOW {
                window.pop_front();
            } else {
                break;
            }
        }
        if window.len() < self.max_per_minute as usize {
            window.push_back(now);
            return Ok(());
        }
        let oldest = window.front().copied().unwrap_or(now);
        let elapsed = now.duration_since(oldest);
        let retry_secs = WINDOW.saturating_sub(elapsed).as_secs().max(1);
        Err(retry_secs)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn zero_means_unlimited() {
        let limiter = SearchRateLimiter::new(0);
        let now = Instant::now();
        for _ in 0..100 {
            assert!(limiter.try_acquire_at(now).is_ok());
        }
    }

    #[test]
    fn denies_after_limit_with_sane_retry() {
        let limiter = SearchRateLimiter::new(2);
        let now = Instant::now();
        assert!(limiter.try_acquire_at(now).is_ok());
        assert!(limiter.try_acquire_at(now).is_ok());
        let retry = limiter.try_acquire_at(now).unwrap_err();
        assert!((1..=60).contains(&retry), "retry was {retry}");
    }

    #[test]
    fn window_expiry_frees_capacity() {
        let limiter = SearchRateLimiter::new(1);
        let start = Instant::now();
        assert!(limiter.try_acquire_at(start).is_ok());
        assert!(limiter.try_acquire_at(start).is_err());
        let later = start + WINDOW + Duration::from_secs(1);
        assert!(limiter.try_acquire_at(later).is_ok());
    }
}
