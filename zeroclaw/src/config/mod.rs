pub mod schema;
pub mod traits;

#[allow(unused_imports)]
pub use schema::{
    apply_runtime_proxy_to_builder, build_runtime_proxy_client,
    build_runtime_proxy_client_with_timeouts, runtime_proxy_config, set_runtime_proxy_config,
    AgentConfig, AuditConfig, AutonomyConfig, BrowserComputerUseConfig, BrowserConfig,
    BuiltinHooksConfig, ChannelsConfig, ComposioConfig, Config, CostConfig, EmailConfig,
    CronConfig, DelegateAgentConfig, DiscordConfig, DmPairingMode, DockerRuntimeConfig,
    EstopConfig, GatewayConfig, GuildOverride, HardwareConfig,
    HardwareTransport, HeartbeatConfig, HooksConfig, HttpRequestConfig, IdentityConfig,
    MemoryConfig, MultimodalConfig, ObservabilityConfig,
    PeripheralBoardConfig, PeripheralsConfig, ProxyConfig, ProxyScope, QdrantConfig,
    ReliabilityConfig, ResourceLimitsConfig, RoutingConfig, RuntimeConfig,
    SandboxBackend, SandboxConfig, SchedulerConfig, SecretsConfig, SecurityConfig, SkillsConfig,
    SkillsPromptInjectionMode, StorageConfig, StorageProviderConfig, StorageProviderSection, SystemPromptConfig,
    StreamMode, TelegramConfig, TelegramParseMode, TelegramReactionLevel, TelegramReceiveMode,
    TranscriptionConfig, TunnelConfig, TwitterBrowseConfig, WebFetchConfig, WebSearchConfig,
    WebhookConfig,
};

pub fn name_and_presence<T: traits::ChannelConfig>(channel: &Option<T>) -> (&'static str, bool) {
    (T::name(), channel.is_some())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reexported_config_default_is_constructible() {
        let config = Config::default();

        assert!(config.default_provider.is_some());
        assert!(config.default_model.is_some());
        assert!(config.default_temperature > 0.0);
    }

    #[test]
    fn reexported_channel_configs_are_constructible() {
        let telegram = TelegramConfig {
            bot_token: "token".into(),
            allowed_users: vec!["alice".into()],
            ..TelegramConfig::default()
        };

        let discord = DiscordConfig {
            bot_token: "token".into(),
            guild_id: Some("123".into()),
            ..DiscordConfig::default()
        };

        assert_eq!(telegram.allowed_users.len(), 1);
        assert_eq!(discord.guild_id.as_deref(), Some("123"));
    }
}
