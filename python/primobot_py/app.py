from fastapi import FastAPI

app = FastAPI(title="primo-bot-python", version="0.1.0")


@app.get("/actuator/health")
async def health() -> dict[str, str]:
    return {"status": "UP"}
