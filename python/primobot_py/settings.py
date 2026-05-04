from __future__ import annotations

from dataclasses import dataclass
import os


def _env(name: str, default: str = "") -> str:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip()


def _env_bool(name: str, default: bool) -> bool:
    value = _env(name, "")
    if not value:
        return default
    return value.lower() in {"1", "true", "yes", "on"}


def _env_int(name: str, default: int) -> int:
    value = _env(name, "")
    if not value:
        return default
    try:
        return int(value)
    except ValueError:
        return default


@dataclass(frozen=True, slots=True)
class Settings:
    discord_token: str
    discord_guild_id: str
    discord_client_id: str
    discord_client_secret: str
    discord_redirect_uri: str
    dashboard_session_secret: str
    dashboard_base_url: str
    dashboard_config_path: str
    forum_auto_mention_targets: str
    order_reminder_tick_ms: int
    sales_report_tick_ms: int
    order_reminder_config_path: str
    order_reminder_default_enabled: bool
    order_reminder_default_time: str
    order_reminder_default_timezone: str
    order_reminder_default_routes: str
    sales_report_config_path: str
    sales_report_default_enabled: bool
    sales_report_default_timezone: str
    sales_report_default_times: str
    sales_report_default_target_channel_id: str
    sales_report_default_overview_time: str
    sales_report_default_overview_target_channel_id: str
    sales_report_default_tone: str
    sales_report_default_signature: str
    meta_webhook_verify_token: str
    meta_webhook_target_channel_id: str
    meta_app_secret: str
    meta_access_token: str
    meta_graph_version: str
    meta_api_base_url: str
    meta_unread_tick_ms: int
    meta_unread_config_path: str
    meta_unread_default_enabled: bool
    meta_unread_default_interval_minutes: int
    meta_unread_default_target_channel_id: str

    @classmethod
    def from_env(cls) -> Settings:
        return cls(
            discord_token=_env("DISCORD_TOKEN"),
            discord_guild_id=_env("DISCORD_GUILD_ID"),
            discord_client_id=_env("DISCORD_CLIENT_ID"),
            discord_client_secret=_env("DISCORD_CLIENT_SECRET"),
            discord_redirect_uri=_env("DISCORD_REDIRECT_URI"),
            dashboard_session_secret=_env("DASHBOARD_SESSION_SECRET"),
            dashboard_base_url=_env("DASHBOARD_BASE_URL"),
            dashboard_config_path=_env(
                "DASHBOARD_CONFIG_PATH", "/data/dashboard-config.json"
            ),
            forum_auto_mention_targets=_env("FORUM_AUTO_MENTION_TARGETS"),
            order_reminder_tick_ms=_env_int("ORDER_REMINDER_TICK_MS", 60000),
            sales_report_tick_ms=_env_int("SALES_REPORT_TICK_MS", 60000),
            order_reminder_config_path=_env(
                "ORDER_REMINDER_CONFIG_PATH", "/data/orders-reminder-config.json"
            ),
            order_reminder_default_enabled=_env_bool("ORDER_REMINDER_DEFAULT_ENABLED", True),
            order_reminder_default_time=_env("ORDER_REMINDER_DEFAULT_TIME", "08:00"),
            order_reminder_default_timezone=_env(
                "ORDER_REMINDER_DEFAULT_TIMEZONE", "Asia/Manila"
            ),
            order_reminder_default_routes=_env("ORDER_REMINDER_DEFAULT_ROUTES"),
            sales_report_config_path=_env(
                "SALES_REPORT_CONFIG_PATH", "/data/sales-report-config.json"
            ),
            sales_report_default_enabled=_env_bool("SALES_REPORT_DEFAULT_ENABLED", True),
            sales_report_default_timezone=_env(
                "SALES_REPORT_DEFAULT_TIMEZONE", "Asia/Manila"
            ),
            sales_report_default_times=_env(
                "SALES_REPORT_DEFAULT_TIMES", "09:00,12:00,15:00,18:00,21:00"
            ),
            sales_report_default_target_channel_id=_env(
                "SALES_REPORT_DEFAULT_TARGET_CHANNEL_ID"
            ),
            sales_report_default_overview_time=_env("SALES_REPORT_DEFAULT_OVERVIEW_TIME"),
            sales_report_default_overview_target_channel_id=_env(
                "SALES_REPORT_DEFAULT_OVERVIEW_TARGET_CHANNEL_ID"
            ),
            sales_report_default_tone=_env("SALES_REPORT_DEFAULT_TONE", "casual"),
            sales_report_default_signature=_env(
                "SALES_REPORT_DEFAULT_SIGNATURE", "Thanks, Primo"
            ),
            meta_webhook_verify_token=_env("META_WEBHOOK_VERIFY_TOKEN"),
            meta_webhook_target_channel_id=_env("META_WEBHOOK_TARGET_CHANNEL_ID"),
            meta_app_secret=_env("META_APP_SECRET"),
            meta_access_token=_env("META_ACCESS_TOKEN"),
            meta_graph_version=_env("META_GRAPH_VERSION", "v24.0"),
            meta_api_base_url=_env("META_API_BASE_URL", "https://graph.facebook.com"),
            meta_unread_tick_ms=_env_int("META_UNREAD_TICK_MS", 60000),
            meta_unread_config_path=_env(
                "META_UNREAD_CONFIG_PATH", "/data/meta-unread-config.json"
            ),
            meta_unread_default_enabled=_env_bool("META_UNREAD_DEFAULT_ENABLED", False),
            meta_unread_default_interval_minutes=_env_int(
                "META_UNREAD_DEFAULT_INTERVAL_MINUTES", 15
            ),
            meta_unread_default_target_channel_id=_env(
                "META_UNREAD_DEFAULT_TARGET_CHANNEL_ID"
            ),
        )
