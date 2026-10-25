from fastapi import FastAPI

app = FastAPI(title="ml-scoring")


@app.get("/scores/{user_id}")
def score(user_id: str) -> dict:
    return {"userId": user_id, "score": 0.42}
