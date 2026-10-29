import os
from fastapi import FastAPI
from dotenv import load_dotenv
from app.routers import model

load_dotenv()

app = FastAPI(
    title="MotionSpeak ML Service",
    description="Machine learning microservice for MotionSpeak — handles model training, testing, and deployment.",
    version="1.0.0"
)


# ── Include routers ───────────────────────────────────────────
app.include_router(model.router)

# ── Health check ──────────────────────────────────────────────
@app.get("/")
def root():
    return {
        "service": "MotionSpeak ML Service",
        "status":  "running",
        "version": "1.0.0"
    }

@app.get("/health")
def health():
    return { "status": "ok" }