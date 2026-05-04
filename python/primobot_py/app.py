from __future__ import annotations

import asyncio
import logging
import time
from typing import Any

from fastapi import FastAPI, Header, HTTPException, Query, Request, Response, status
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse

from .bot import PrimoBot
from .dashboard import (
    DashboardAccessError,
    DashboardConfigService,
    DashboardConfigStore,
    DashboardSession,
    DashboardSessionSigner,
    DiscordGuildAccess,
    DiscordGuildAccessCache,
    DiscordOAuthClient,
    render_dashboard_login_page,
    render_guild_dashboard_page,
    render_guild_selector_page,
)
from .meta_webhook import MetaWebhookEventExtractor, MetaWebhookRelayService
from .settings import Settings

logging.basicConfig(level=logging.INFO)

SESSION_COOKIE_NAME = "primo_dashboard_session"
STATE_COOKIE_NAME = "primo_dashboard_state"

settings = Settings.from_env()
bot = PrimoBot(settings)
dashboard_store = DashboardConfigStore(
    config_path=settings.dashboard_config_path,
    default_forum_auto_mention_targets_raw=settings.forum_auto_mention_targets,
    default_meta_webhook_target_channel_id=settings.meta_webhook_target_channel_id,
)
meta_webhook_service = MetaWebhookRelayService(
    bot=bot,
    extractor=MetaWebhookEventExtractor(),
    default_guild_id=settings.discord_guild_id,
    verify_token=settings.meta_webhook_verify_token,
    fallback_target_channel_id=settings.meta_webhook_target_channel_id,
    app_secret=settings.meta_app_secret,
)
dashboard_config_service = DashboardConfigService(
    bot=bot,
    orders_reminder_store=bot.orders_reminder_store,
    orders_reminder_service=bot.orders_reminder_service,
    sales_report_store=bot.sales_report_store,
    sales_command_service=bot.sales_command_service,
    meta_unread_store=bot.meta_unread_store,
    dashboard_store=dashboard_store,
)
app = FastAPI(title="primo-bot-python", version="0.2.0")
_bot_task: asyncio.Task[None] | None = None
_guild_access_cache = DiscordGuildAccessCache(ttl_seconds=60)


def _build_signer() -> DashboardSessionSigner:
    if not settings.dashboard_session_secret:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="DASHBOARD_SESSION_SECRET is not configured.",
        )
    return DashboardSessionSigner(settings.dashboard_session_secret)


def _build_oauth_client() -> DiscordOAuthClient:
    missing = [
        name
        for name, value in [
            ("DISCORD_CLIENT_ID", settings.discord_client_id),
            ("DISCORD_CLIENT_SECRET", settings.discord_client_secret),
            ("DISCORD_REDIRECT_URI", settings.discord_redirect_uri),
            ("DASHBOARD_SESSION_SECRET", settings.dashboard_session_secret),
        ]
        if not value
    ]
    if missing:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Dashboard OAuth is missing: " + ", ".join(missing),
        )
    return DiscordOAuthClient(
        client_id=settings.discord_client_id,
        client_secret=settings.discord_client_secret,
        redirect_uri=settings.discord_redirect_uri,
    )


def _json_response(payload: dict[str, Any]) -> JSONResponse:
    return JSONResponse(content=payload)


def _load_session(request: Request) -> DashboardSession:
    signer = _build_signer()
    raw = request.cookies.get(SESSION_COOKIE_NAME, "")
    session = signer.loads(raw)
    if session is None or session.is_expired():
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Dashboard session is missing or expired.",
        )
    return session


def _set_cookie(response: Response, name: str, value: str, max_age_seconds: int) -> None:
    response.set_cookie(
        key=name,
        value=value,
        max_age=max_age_seconds,
        httponly=True,
        secure=False,
        samesite="lax",
        path="/",
    )


def _clear_cookie(response: Response, name: str) -> None:
    response.delete_cookie(key=name, path="/")


def _oauth_guilds(session: DashboardSession) -> list[DiscordGuildAccess]:
    now_epoch_ms = int(time.time() * 1000)
    cached = _guild_access_cache.get(session.access_token, now_epoch_ms)
    if cached is not None:
        return cached
    try:
        guilds = _build_oauth_client().list_user_guilds(session.access_token)
        _guild_access_cache.put(
            session.access_token,
            guilds,
            now_epoch_ms,
            session.expires_at_epoch_ms,
        )
        return guilds
    except Exception as ex:
        if cached is not None:
            return cached
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Failed to load Discord guild access: " + str(ex),
        ) from ex


def _authorized_guild(request: Request, guild_id: str) -> tuple[DashboardSession, Any]:
    session = _load_session(request)
    oauth_guilds = _oauth_guilds(session)
    try:
        guild = dashboard_config_service.require_manageable_guild(
            guild_id,
            oauth_guilds,
            settings.discord_guild_id,
        )
    except DashboardAccessError as ex:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=str(ex),
        ) from ex
    return session, guild


async def _dashboard_bootstrap(guild: Any) -> dict[str, Any]:
    return dashboard_config_service.build_bootstrap(guild)


async def _apply_dashboard_runtime_settings() -> None:
    snapshot = await dashboard_store.get_snapshot()
    bot.set_forum_auto_mention_targets(snapshot.forumAutoMentionTargets)
    meta_webhook_service.set_fallback_target_channel_id(snapshot.metaWebhookTargetChannelId)


@app.on_event("startup")
async def startup() -> None:
    global _bot_task
    if not settings.discord_token:
        raise RuntimeError("The environment variable DISCORD_TOKEN is not set.")
    await dashboard_store.initialize()
    await _apply_dashboard_runtime_settings()
    if _bot_task is None or _bot_task.done():
        _bot_task = asyncio.create_task(bot.start(settings.discord_token))


@app.on_event("shutdown")
async def shutdown() -> None:
    global _bot_task
    await bot.close()
    if _bot_task is not None:
        try:
            await _bot_task
        except Exception:
            pass
        _bot_task = None


@app.get("/actuator/health")
async def health() -> dict[str, str]:
    return {"status": "UP"}


@app.get("/dashboard", response_class=HTMLResponse)
async def dashboard_home(request: Request) -> HTMLResponse:
    try:
        session = _load_session(request)
    except HTTPException:
        return HTMLResponse(render_dashboard_login_page("/dashboard/login"))
    oauth_guilds = _oauth_guilds(session)
    manageable = dashboard_config_service.list_manageable_shared_guilds(
        oauth_guilds,
        settings.discord_guild_id,
    )
    return HTMLResponse(
        render_guild_selector_page(manageable, session.display_name, "/dashboard/logout")
    )


@app.get("/dashboard/login")
async def dashboard_login() -> Response:
    signer = _build_signer()
    oauth_client = _build_oauth_client()
    state = signer.issue_state()
    response = RedirectResponse(url=oauth_client.build_authorize_url(state), status_code=302)
    _set_cookie(response, STATE_COOKIE_NAME, state, 900)
    return response


@app.get("/dashboard/oauth/callback")
async def dashboard_callback(
    request: Request,
    code: str | None = Query(default=None),
    state: str | None = Query(default=None),
) -> Response:
    signer = _build_signer()
    expected_state = request.cookies.get(STATE_COOKIE_NAME, "")
    if not code or not state or state != expected_state:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="OAuth callback state validation failed.",
        )
    oauth_client = _build_oauth_client()
    try:
        session = oauth_client.exchange_code(code)
    except Exception as ex:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Discord OAuth exchange failed: " + str(ex),
        ) from ex
    session = DashboardSession(
        state=state,
        user_id=session.user_id,
        username=session.username,
        global_name=session.global_name,
        access_token=session.access_token,
        refresh_token=session.refresh_token,
        expires_at_epoch_ms=session.expires_at_epoch_ms,
    )
    response = RedirectResponse(url="/dashboard/guilds", status_code=302)
    _set_cookie(
        response,
        SESSION_COOKIE_NAME,
        signer.dumps(session),
        max(60, int((session.expires_at_epoch_ms / 1000) - time.time())),
    )
    _clear_cookie(response, STATE_COOKIE_NAME)
    return response


@app.post("/dashboard/logout")
async def dashboard_logout() -> Response:
    response = RedirectResponse(url="/dashboard", status_code=302)
    _clear_cookie(response, SESSION_COOKIE_NAME)
    _clear_cookie(response, STATE_COOKIE_NAME)
    return response


@app.get("/dashboard/guilds", response_class=HTMLResponse)
async def dashboard_guilds(request: Request) -> HTMLResponse:
    session = _load_session(request)
    oauth_guilds = _oauth_guilds(session)
    manageable = dashboard_config_service.list_manageable_shared_guilds(
        oauth_guilds,
        settings.discord_guild_id,
    )
    return HTMLResponse(
        render_guild_selector_page(manageable, session.display_name, "/dashboard/logout")
    )


@app.get("/dashboard/guilds/{guild_id}", response_class=HTMLResponse)
async def dashboard_guild(request: Request, guild_id: str) -> HTMLResponse:
    session, guild = _authorized_guild(request, guild_id)
    return HTMLResponse(render_guild_dashboard_page(str(guild.id), guild.name, session.display_name))


@app.get("/api/dashboard/guilds/{guild_id}/bootstrap")
async def dashboard_bootstrap_api(request: Request, guild_id: str) -> JSONResponse:
    _, guild = _authorized_guild(request, guild_id)
    return _json_response(await _dashboard_bootstrap(guild))


@app.get("/api/dashboard/guilds/{guild_id}/orders-reminders")
async def dashboard_orders_reminders_get(request: Request, guild_id: str) -> JSONResponse:
    _authorized_guild(request, guild_id)
    return _json_response(await dashboard_config_service.get_orders_reminders())


@app.put("/api/dashboard/guilds/{guild_id}/orders-reminders")
async def dashboard_orders_reminders_put(
    request: Request, guild_id: str, payload: dict[str, Any]
) -> JSONResponse:
    _, guild = _authorized_guild(request, guild_id)
    try:
        return _json_response(await dashboard_config_service.update_orders_reminders(guild, payload))
    except DashboardAccessError as ex:
        raise HTTPException(status_code=400, detail=str(ex)) from ex


@app.get("/api/dashboard/guilds/{guild_id}/sales-settings")
async def dashboard_sales_settings_get(request: Request, guild_id: str) -> JSONResponse:
    _authorized_guild(request, guild_id)
    return _json_response(await dashboard_config_service.get_sales_settings())


@app.put("/api/dashboard/guilds/{guild_id}/sales-settings")
async def dashboard_sales_settings_put(
    request: Request, guild_id: str, payload: dict[str, Any]
) -> JSONResponse:
    _, guild = _authorized_guild(request, guild_id)
    try:
        return _json_response(await dashboard_config_service.update_sales_settings(guild, payload))
    except DashboardAccessError as ex:
        raise HTTPException(status_code=400, detail=str(ex)) from ex


@app.get("/api/dashboard/guilds/{guild_id}/sales-accounts")
async def dashboard_sales_accounts_get(request: Request, guild_id: str) -> JSONResponse:
    _authorized_guild(request, guild_id)
    return _json_response(await dashboard_config_service.get_sales_accounts())


@app.put("/api/dashboard/guilds/{guild_id}/sales-accounts")
async def dashboard_sales_accounts_put(
    request: Request, guild_id: str, payload: dict[str, Any]
) -> JSONResponse:
    _authorized_guild(request, guild_id)
    try:
        return _json_response(await dashboard_config_service.update_sales_accounts(payload))
    except DashboardAccessError as ex:
        raise HTTPException(status_code=400, detail=str(ex)) from ex


@app.get("/api/dashboard/guilds/{guild_id}/forum-auto-mentions")
async def dashboard_forum_auto_mentions_get(
    request: Request, guild_id: str
) -> JSONResponse:
    _authorized_guild(request, guild_id)
    return _json_response(await dashboard_config_service.get_forum_auto_mentions())


@app.put("/api/dashboard/guilds/{guild_id}/forum-auto-mentions")
async def dashboard_forum_auto_mentions_put(
    request: Request, guild_id: str, payload: dict[str, Any]
) -> JSONResponse:
    _, guild = _authorized_guild(request, guild_id)
    try:
        response = await dashboard_config_service.update_forum_auto_mentions(guild, payload)
    except DashboardAccessError as ex:
        raise HTTPException(status_code=400, detail=str(ex)) from ex
    await _apply_dashboard_runtime_settings()
    return _json_response(response)


@app.get("/api/dashboard/guilds/{guild_id}/meta-unread")
async def dashboard_meta_unread_get(request: Request, guild_id: str) -> JSONResponse:
    _authorized_guild(request, guild_id)
    return _json_response(await dashboard_config_service.get_meta_unread())


@app.put("/api/dashboard/guilds/{guild_id}/meta-unread")
async def dashboard_meta_unread_put(
    request: Request, guild_id: str, payload: dict[str, Any]
) -> JSONResponse:
    _, guild = _authorized_guild(request, guild_id)
    try:
        return _json_response(await dashboard_config_service.update_meta_unread(guild, payload))
    except DashboardAccessError as ex:
        raise HTTPException(status_code=400, detail=str(ex)) from ex


@app.get("/api/dashboard/guilds/{guild_id}/meta-webhook")
async def dashboard_meta_webhook_get(request: Request, guild_id: str) -> JSONResponse:
    _authorized_guild(request, guild_id)
    return _json_response(await dashboard_config_service.get_meta_webhook())


@app.put("/api/dashboard/guilds/{guild_id}/meta-webhook")
async def dashboard_meta_webhook_put(
    request: Request, guild_id: str, payload: dict[str, Any]
) -> JSONResponse:
    _, guild = _authorized_guild(request, guild_id)
    try:
        response = await dashboard_config_service.update_meta_webhook(guild, payload)
    except DashboardAccessError as ex:
        raise HTTPException(status_code=400, detail=str(ex)) from ex
    await _apply_dashboard_runtime_settings()
    return _json_response(response)


@app.get("/webhooks/meta")
async def verify_meta_webhook(
    hub_mode: str | None = Query(default=None, alias="hub.mode"),
    hub_verify_token: str | None = Query(default=None, alias="hub.verify_token"),
    hub_challenge: str | None = Query(default=None, alias="hub.challenge"),
) -> Response:
    if meta_webhook_service.is_verification_request_valid(hub_mode, hub_verify_token):
        return Response(content=hub_challenge or "", media_type="text/plain")
    return Response(content="forbidden", status_code=status.HTTP_403_FORBIDDEN)


@app.post("/webhooks/meta")
async def receive_meta_webhook(
    request: Request, x_hub_signature_256: str | None = Header(default=None)
) -> Response:
    body = (await request.body()).decode("utf-8")
    if not meta_webhook_service.is_signature_valid(body, x_hub_signature_256):
        return Response(
            content="invalid signature",
            status_code=status.HTTP_401_UNAUTHORIZED,
        )
    await meta_webhook_service.relay_inbound_chat_payload(body)
    return Response(content="EVENT_RECEIVED", media_type="text/plain")
