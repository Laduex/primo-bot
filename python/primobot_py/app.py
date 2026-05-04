from __future__ import annotations

import asyncio
import logging

from fastapi import FastAPI, Header, HTTPException, Query, Request, Response, status

from .bot import PrimoBot
from .meta_webhook import MetaWebhookEventExtractor, MetaWebhookRelayService
from .ops_alerts import OpsAlertRequest, OpsAlertService
from .settings import Settings

logging.basicConfig(level=logging.INFO)

settings = Settings.from_env()
bot = PrimoBot(settings)
meta_webhook_service = MetaWebhookRelayService(
    bot=bot,
    extractor=MetaWebhookEventExtractor(),
    default_guild_id=settings.discord_guild_id,
    verify_token=settings.meta_webhook_verify_token,
    fallback_target_channel_id=settings.meta_webhook_target_channel_id,
    app_secret=settings.meta_app_secret,
)
ops_alert_service = OpsAlertService(bot=bot, target_channel_id=settings.ops_alert_channel_id)
app = FastAPI(title="primo-bot-python", version="0.1.0")
_bot_task: asyncio.Task[None] | None = None


@app.on_event("startup")
async def startup() -> None:
    global _bot_task
    if not settings.discord_token:
        raise RuntimeError("The environment variable DISCORD_TOKEN is not set.")
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


@app.post("/ops/alerts")
async def send_ops_alert(
    request: OpsAlertRequest,
    x_ops_alert_token: str | None = Header(default=None),
) -> Response:
    if settings.ops_alert_token and settings.ops_alert_token != (x_ops_alert_token or ""):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid token")

    result = await ops_alert_service.send_alert(request)
    if not result.sent:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=result.message)
    return Response(content=result.message, media_type="text/plain")
