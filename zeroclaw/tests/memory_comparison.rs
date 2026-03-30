//! Memory backend comparison tests (SQLite-only after backend simplification)
//!
//! Run with: cargo test --test memory_comparison -- --nocapture

use std::time::Instant;
use tempfile::TempDir;

use zeroclaw::memory::{sqlite::SqliteMemory, Memory, MemoryCategory};

// -- Helpers --

fn sqlite_backend(dir: &std::path::Path) -> SqliteMemory {
    SqliteMemory::new(dir).expect("SQLite init failed")
}

// -- Test 1: Store performance --

#[tokio::test]
async fn sqlite_store_speed() {
    let tmp = TempDir::new().unwrap();
    let sq = sqlite_backend(tmp.path());

    let n = 100;

    let start = Instant::now();
    for i in 0..n {
        sq.store(
            &format!("key_{i}"),
            &format!("Memory entry number {i} about Rust programming"),
            MemoryCategory::Core,
            None,
        )
        .await
        .unwrap();
    }
    let sq_dur = start.elapsed();

    println!("\n============================================================");
    println!("STORE {n} entries:");
    println!("  SQLite: {:?}", sq_dur);

    assert_eq!(sq.count().await.unwrap(), n);
}

// -- Test 2: Recall / search quality --

#[tokio::test]
async fn sqlite_recall_quality() {
    let tmp = TempDir::new().unwrap();
    let sq = sqlite_backend(tmp.path());

    let entries = vec![
        (
            "lang_pref",
            "User prefers Rust over Python",
            MemoryCategory::Core,
        ),
        (
            "editor",
            "Uses VS Code with rust-analyzer",
            MemoryCategory::Core,
        ),
        ("tz", "Timezone is EST, works 9-5", MemoryCategory::Core),
        (
            "proj1",
            "Working on ZeroClaw AI assistant",
            MemoryCategory::Daily,
        ),
        (
            "proj2",
            "Previous project was a web scraper in Python",
            MemoryCategory::Daily,
        ),
        (
            "deploy",
            "Deploys to Hetzner VPS via Docker",
            MemoryCategory::Core,
        ),
        (
            "model",
            "Prefers Claude Sonnet for coding tasks",
            MemoryCategory::Core,
        ),
        (
            "style",
            "Likes concise responses, no fluff",
            MemoryCategory::Core,
        ),
        (
            "rust_note",
            "Rust's ownership model prevents memory bugs",
            MemoryCategory::Daily,
        ),
        (
            "perf",
            "Cares about binary size and startup time",
            MemoryCategory::Core,
        ),
    ];

    for (key, content, cat) in &entries {
        sq.store(key, content, cat.clone(), None).await.unwrap();
    }

    let queries = vec![
        ("Rust", "Should find Rust-related entries"),
        ("Python", "Should find Python references"),
        ("deploy Docker", "Multi-keyword search"),
        ("Claude", "Specific tool reference"),
        ("javascript", "No matches expected"),
        ("binary size startup", "Multi-keyword partial match"),
    ];

    println!("\n============================================================");
    println!("RECALL QUALITY (10 entries seeded):\n");

    for (query, desc) in &queries {
        let sq_results = sq.recall(query, 10, None).await.unwrap();

        println!("  Query: \"{query}\" -- {desc}");
        println!("    SQLite: {} results", sq_results.len());
        for r in &sq_results {
            println!(
                "      [{:.2}] {}: {}",
                r.score.unwrap_or(0.0),
                r.key,
                &r.content[..r.content.len().min(50)]
            );
        }
        println!();
    }
}

// -- Test 3: Recall speed at scale --

#[tokio::test]
async fn sqlite_recall_speed() {
    let tmp = TempDir::new().unwrap();
    let sq = sqlite_backend(tmp.path());

    let n = 200;
    for i in 0..n {
        let content = if i % 3 == 0 {
            format!("Rust is great for systems programming, entry {i}")
        } else if i % 3 == 1 {
            format!("Python is popular for data science, entry {i}")
        } else {
            format!("TypeScript powers modern web apps, entry {i}")
        };
        sq.store(&format!("e{i}"), &content, MemoryCategory::Core, None)
            .await
            .unwrap();
    }

    let start = Instant::now();
    let sq_results = sq.recall("Rust systems", 10, None).await.unwrap();
    let sq_dur = start.elapsed();

    println!("\n============================================================");
    println!("RECALL from {n} entries (query: \"Rust systems\", limit 10):");
    println!("  SQLite: {:?} -> {} results", sq_dur, sq_results.len());

    assert!(!sq_results.is_empty());
}

// -- Test 4: Persistence --

#[tokio::test]
async fn sqlite_persistence() {
    let tmp = TempDir::new().unwrap();

    {
        let sq = sqlite_backend(tmp.path());
        sq.store(
            "persist_test",
            "I should survive",
            MemoryCategory::Core,
            None,
        )
        .await
        .unwrap();
    }

    let sq2 = sqlite_backend(tmp.path());
    let sq_entry = sq2.get("persist_test").await.unwrap();

    println!("\n============================================================");
    println!("PERSISTENCE (store -> drop -> re-open -> get):");
    println!(
        "  SQLite: {}",
        if sq_entry.is_some() {
            "Survived"
        } else {
            "Lost"
        }
    );

    assert!(sq_entry.is_some());
    assert_eq!(sq_entry.unwrap().content, "I should survive");
}

// -- Test 5: Upsert / update behavior --

#[tokio::test]
async fn sqlite_upsert() {
    let tmp = TempDir::new().unwrap();
    let sq = sqlite_backend(tmp.path());

    sq.store("pref", "likes Rust", MemoryCategory::Core, None)
        .await
        .unwrap();
    sq.store("pref", "loves Rust", MemoryCategory::Core, None)
        .await
        .unwrap();

    let sq_count = sq.count().await.unwrap();
    let sq_entry = sq.get("pref").await.unwrap();

    println!("\n============================================================");
    println!("UPSERT (store same key twice):");
    println!(
        "  SQLite: count={sq_count}, latest=\"{}\"",
        sq_entry.as_ref().map_or("none", |e| &e.content)
    );

    assert_eq!(sq_count, 1);
    assert_eq!(sq_entry.unwrap().content, "loves Rust");
}

// -- Test 6: Forget / delete capability --

#[tokio::test]
async fn sqlite_forget() {
    let tmp = TempDir::new().unwrap();
    let sq = sqlite_backend(tmp.path());

    sq.store("secret", "API key: sk-1234", MemoryCategory::Core, None)
        .await
        .unwrap();

    let sq_forgot = sq.forget("secret").await.unwrap();

    println!("\n============================================================");
    println!("FORGET (delete sensitive data):");
    println!(
        "  SQLite: {} (count={})",
        if sq_forgot { "Deleted" } else { "Kept" },
        sq.count().await.unwrap()
    );

    assert!(sq_forgot);
    assert_eq!(sq.count().await.unwrap(), 0);
}

// -- Test 7: Category filtering --

#[tokio::test]
async fn sqlite_category_filter() {
    let tmp = TempDir::new().unwrap();
    let sq = sqlite_backend(tmp.path());

    sq.store("a", "core fact 1", MemoryCategory::Core, None)
        .await
        .unwrap();
    sq.store("b", "core fact 2", MemoryCategory::Core, None)
        .await
        .unwrap();
    sq.store("c", "daily note", MemoryCategory::Daily, None)
        .await
        .unwrap();
    sq.store("d", "convo msg", MemoryCategory::Conversation, None)
        .await
        .unwrap();

    let sq_core = sq.list(Some(&MemoryCategory::Core), None).await.unwrap();
    let sq_daily = sq.list(Some(&MemoryCategory::Daily), None).await.unwrap();
    let sq_conv = sq
        .list(Some(&MemoryCategory::Conversation), None)
        .await
        .unwrap();
    let sq_all = sq.list(None, None).await.unwrap();

    println!("\n============================================================");
    println!("CATEGORY FILTERING:");
    println!(
        "  SQLite: core={}, daily={}, conv={}, all={}",
        sq_core.len(),
        sq_daily.len(),
        sq_conv.len(),
        sq_all.len()
    );

    assert_eq!(sq_core.len(), 2);
    assert_eq!(sq_daily.len(), 1);
    assert_eq!(sq_conv.len(), 1);
    assert_eq!(sq_all.len(), 4);
}
