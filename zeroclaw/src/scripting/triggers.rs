// Copyright (c) 2026 @Natfii. All rights reserved.

//! Trigger matching and registration for packaged scripts.

use crate::scripting::{ScriptError, ScriptManifest, ScriptTrigger};

/// A resolved trigger ready for registration with the scheduler or event bus.
#[derive(Debug, Clone)]
pub struct ResolvedTrigger {
    /// Script manifest this trigger belongs to.
    pub manifest: ScriptManifest,
    /// The trigger definition.
    pub trigger: ScriptTrigger,
}

/// Extract all cron triggers from workspace scripts.
pub fn collect_cron_triggers(scripts: &[ScriptManifest]) -> Vec<ResolvedTrigger> {
    scripts
        .iter()
        .flat_map(|manifest| {
            manifest.triggers.iter().filter_map(move |trigger| {
                if trigger.kind == "cron" && trigger.schedule.is_some() {
                    Some(ResolvedTrigger {
                        manifest: manifest.clone(),
                        trigger: trigger.clone(),
                    })
                } else {
                    None
                }
            })
        })
        .collect()
}

/// Extract event-driven triggers (channel_event, provider_event).
pub fn collect_event_triggers(scripts: &[ScriptManifest]) -> Vec<ResolvedTrigger> {
    scripts
        .iter()
        .flat_map(|manifest| {
            manifest.triggers.iter().filter_map(move |trigger| {
                if trigger.kind == "channel_event" || trigger.kind == "provider_event" {
                    Some(ResolvedTrigger {
                        manifest: manifest.clone(),
                        trigger: trigger.clone(),
                    })
                } else {
                    None
                }
            })
        })
        .collect()
}

/// Validate a trigger definition.
///
/// For `channel_event` and `provider_event` triggers, the `event` field is required.
/// Wildcard triggers (no `event` field) are not allowed for event-driven triggers.
pub fn validate_trigger(trigger: &ScriptTrigger) -> Result<(), ScriptError> {
    match trigger.kind.as_str() {
        "channel_event" | "provider_event" => {
            if trigger.event.is_none() {
                return Err(ScriptError::ValidationError {
                    detail: format!(
                        "{} trigger requires an 'event' field; wildcard triggers are not allowed",
                        trigger.kind
                    ),
                });
            }
            Ok(())
        }
        _ => Ok(()),
    }
}

/// Check whether a trigger matches a given event kind and channel.
pub fn trigger_matches_event(
    trigger: &ScriptTrigger,
    event_kind: &str,
    event_channel: Option<&str>,
) -> bool {
    let kind_matches = match trigger.event.as_deref() {
        Some("message") => event_kind == "channel_message",
        Some("tool_call") => event_kind == "tool_call",
        Some("error") => event_kind == "error",
        Some("agent_start") => event_kind == "agent_start",
        Some("agent_end") => event_kind == "agent_end",
        Some(other) => event_kind == other,
        None => true,
    };

    if !kind_matches {
        return false;
    }

    match trigger.channel.as_deref() {
        Some(required) => event_channel.map_or(false, |c| c.eq_ignore_ascii_case(required)),
        None => true,
    }
}

/// Return names of scripts matching an event.
pub fn matching_script_names(
    triggers: &[ResolvedTrigger],
    event_kind: &str,
    event_channel: Option<&str>,
) -> Vec<String> {
    triggers
        .iter()
        .filter(|rt| trigger_matches_event(&rt.trigger, event_kind, event_channel))
        .map(|rt| rt.manifest.name.clone())
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn manifest_with_triggers(name: &str, triggers: Vec<ScriptTrigger>) -> ScriptManifest {
        ScriptManifest {
            name: name.to_string(),
            triggers,
            ..Default::default()
        }
    }

    #[test]
    fn collects_cron_triggers_only() {
        let scripts = vec![manifest_with_triggers(
            "test",
            vec![
                ScriptTrigger {
                    kind: "cron".into(),
                    schedule: Some("0 9 * * *".into()),
                    ..Default::default()
                },
                ScriptTrigger {
                    kind: "manual".into(),
                    ..Default::default()
                },
                ScriptTrigger {
                    kind: "channel_event".into(),
                    event: Some("message".into()),
                    ..Default::default()
                },
            ],
        )];
        let crons = collect_cron_triggers(&scripts);
        assert_eq!(crons.len(), 1);
        assert_eq!(crons[0].trigger.schedule.as_deref(), Some("0 9 * * *"));
    }

    #[test]
    fn cron_without_schedule_is_skipped() {
        let scripts = vec![manifest_with_triggers(
            "test",
            vec![ScriptTrigger {
                kind: "cron".into(),
                schedule: None,
                ..Default::default()
            }],
        )];
        assert!(collect_cron_triggers(&scripts).is_empty());
    }

    #[test]
    fn event_trigger_matches_channel() {
        let trigger = ScriptTrigger {
            kind: "channel_event".into(),
            event: Some("message".into()),
            channel: Some("telegram".into()),
            ..Default::default()
        };
        assert!(trigger_matches_event(
            &trigger,
            "channel_message",
            Some("telegram")
        ));
        assert!(!trigger_matches_event(
            &trigger,
            "channel_message",
            Some("discord")
        ));
        assert!(!trigger_matches_event(&trigger, "tool_call", None));
    }

    #[test]
    fn event_trigger_without_channel_matches_any() {
        let trigger = ScriptTrigger {
            kind: "channel_event".into(),
            event: Some("message".into()),
            ..Default::default()
        };
        assert!(trigger_matches_event(
            &trigger,
            "channel_message",
            Some("telegram")
        ));
        assert!(trigger_matches_event(
            &trigger,
            "channel_message",
            Some("discord")
        ));
    }

    #[test]
    fn collects_event_triggers_only() {
        let scripts = vec![manifest_with_triggers(
            "test",
            vec![
                ScriptTrigger {
                    kind: "cron".into(),
                    schedule: Some("0 9 * * *".into()),
                    ..Default::default()
                },
                ScriptTrigger {
                    kind: "channel_event".into(),
                    event: Some("message".into()),
                    ..Default::default()
                },
                ScriptTrigger {
                    kind: "provider_event".into(),
                    event: Some("error".into()),
                    ..Default::default()
                },
            ],
        )];
        let events = collect_event_triggers(&scripts);
        assert_eq!(events.len(), 2);
    }

    #[test]
    fn matching_script_names_filters_correctly() {
        let triggers = vec![
            ResolvedTrigger {
                manifest: ScriptManifest {
                    name: "greeter".into(),
                    ..Default::default()
                },
                trigger: ScriptTrigger {
                    kind: "channel_event".into(),
                    event: Some("message".into()),
                    channel: Some("telegram".into()),
                    ..Default::default()
                },
            },
            ResolvedTrigger {
                manifest: ScriptManifest {
                    name: "logger".into(),
                    ..Default::default()
                },
                trigger: ScriptTrigger {
                    kind: "channel_event".into(),
                    event: Some("message".into()),
                    ..Default::default()
                },
            },
        ];
        let names = matching_script_names(&triggers, "channel_message", Some("telegram"));
        assert_eq!(names, vec!["greeter", "logger"]);

        let names = matching_script_names(&triggers, "channel_message", Some("discord"));
        assert_eq!(names, vec!["logger"]);
    }

    #[test]
    fn channel_event_without_event_field_is_invalid() {
        let trigger = ScriptTrigger {
            kind: "channel_event".into(),
            event: None,
            ..Default::default()
        };
        let result = validate_trigger(&trigger);
        assert!(result.is_err());
        let err = result.unwrap_err();
        assert!(err.to_string().contains("channel_event trigger requires an 'event' field"));
    }

    #[test]
    fn provider_event_without_event_field_is_invalid() {
        let trigger = ScriptTrigger {
            kind: "provider_event".into(),
            event: None,
            ..Default::default()
        };
        let result = validate_trigger(&trigger);
        assert!(result.is_err());
    }

    #[test]
    fn channel_event_with_event_field_is_valid() {
        let trigger = ScriptTrigger {
            kind: "channel_event".into(),
            event: Some("message".into()),
            ..Default::default()
        };
        assert!(validate_trigger(&trigger).is_ok());
    }

    #[test]
    fn cron_trigger_without_event_field_is_valid() {
        let trigger = ScriptTrigger {
            kind: "cron".into(),
            schedule: Some("0 9 * * *".into()),
            event: None,
            ..Default::default()
        };
        assert!(validate_trigger(&trigger).is_ok());
    }
}
