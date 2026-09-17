import httpx

from fleet_copilot.app import create_app

from .conftest import ScriptedModel, answer


async def _client(copilot):
    app = create_app(copilot=copilot)
    return httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test")


async def test_chat_keeps_the_evaluation_contract(data, make):
    copilot = make(ScriptedModel(answer("ok")))
    async with await _client(copilot) as http:
        app = http._transport.app
        app.state.copilot = copilot
        response = await http.post("/copilot/chat", json={"tenantId": 1, "userId": "ops", "message": "hi"})
        assert response.status_code == 200
        body = response.json()
        assert set(body) == {"conversationId", "answer", "toolsUsed", "evaluation", "queryBasis"}
        for bad in ({"tenantId": "1", "userId": "ops", "message": "hi"},
                    {"tenantId": 0, "userId": "ops", "message": "hi"},
                    {"tenantId": 1, "userId": " ", "message": "hi"},
                    {"tenantId": 1, "userId": "ops", "message": "x" * 4001}):
            rejected = await http.post("/copilot/chat", json=bad)
            assert rejected.status_code == 400, bad


async def test_rate_limit_returns_429_with_retry_after(data, make):
    data.rate = 1
    copilot = make(ScriptedModel(answer("ok")))
    async with await _client(copilot) as http:
        http._transport.app.state.copilot = copilot
        await http.post("/copilot/chat", json={"tenantId": 1, "userId": "ops", "message": "a"})
        limited = await http.post("/copilot/chat", json={"tenantId": 1, "userId": "ops", "message": "b"})
        assert limited.status_code == 429 and "retry-after" in limited.headers
